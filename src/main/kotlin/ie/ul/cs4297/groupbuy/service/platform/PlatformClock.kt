package ie.ul.cs4297.groupbuy.service.platform

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * The platform clock (spec B, rule 4). Every instance of every service reads the same time, so a
 * cut-off happens at one moment everywhere, and the organisers can move time forward in a demo.
 *
 * Never call Instant.now() or LocalDateTime.now(): inject `java.time.Clock` and call clock.instant().
 * In tests, the test support's TestClock replaces it: move time on with TestClock.advance(...).
 *
 * GET CLOCK_URL answers the time as JSON {"now": "2026-11-14T12:00:00Z"} or as plain ISO text.
 * The offset from this machine's clock is refreshed every 30 seconds.
 */
class PlatformClock(private val zone: ZoneId = ZoneOffset.UTC, private val offsetMillis: AtomicLong = AtomicLong(0)) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = PlatformClock(zone, offsetMillis)
    override fun instant(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() + offsetMillis.get())
    override fun millis(): Long = System.currentTimeMillis() + offsetMillis.get()

    /** How far the platform clock is ahead of this machine (negative when behind). */
    fun offset(): Duration = Duration.ofMillis(offsetMillis.get())

    internal fun setOffset(millis: Long) = offsetMillis.set(millis)
}

@Configuration
class ClockConfig {
    @Bean
    fun platformClock(): PlatformClock = PlatformClock()
}

/**
 * CLOCK_URL is required (spec B, Technical conventions): without it the service would quietly use
 * this machine's clock, and its cut-offs and timers would disagree with every other service. So the
 * service refuses to start without it. Only the tests (profile `test`) run without it, on TestClock.
 */
@Component
class ClockUrlCheck(settings: PlatformSettings, environment: Environment) {
    init {
        check(settings.clockUrl.isNotBlank() || environment.acceptsProfiles(Profiles.of("test"))) {
            "CLOCK_URL is not set. Every service must read the platform clock: set CLOCK_URL=http://clock:8080/internal/clock " +
                "in Docker (service.env), or http://localhost:8090/internal/clock when running in IntelliJ (local.env)"
        }
    }
}

@Component
class PlatformClockSync(
    private val clock: PlatformClock,
    private val settings: PlatformSettings,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private var warned = false

    @Scheduled(fixedDelay = 30_000, initialDelay = 0)
    fun refresh() {
        if (settings.clockUrl.isBlank()) {
            if (!warned) log.warn("CLOCK_URL is not set (allowed in tests only): using this machine's clock, not the platform clock")
            warned = true
            return
        }
        try {
            val sent = System.currentTimeMillis()
            val response = http.send(
                HttpRequest.newBuilder(URI.create(settings.clockUrl)).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val received = System.currentTimeMillis()
            check(response.statusCode() == 200) { "the clock answered ${response.statusCode()}" }
            val now = parse(response.body())
            clock.setOffset(now.toEpochMilli() - (sent + received) / 2)
        } catch (e: Exception) {
            // Keep the last offset: a clock that is briefly unreachable must not move time.
            log.warn("Could not read the platform clock at {} ({}); keeping offset {}", settings.clockUrl, e.message, clock.offset())
        }
    }

    private fun parse(body: String): Instant {
        val text = body.trim()
        if (text.startsWith("{")) {
            val node = mapper.readTree(text)
            val value = listOf("now", "instant", "time").firstNotNullOfOrNull { node.get(it)?.asText() }
                ?: error("the clock's JSON has no \"now\" field")
            return Instant.parse(value)
        }
        return Instant.parse(text.trim('"'))
    }
}
