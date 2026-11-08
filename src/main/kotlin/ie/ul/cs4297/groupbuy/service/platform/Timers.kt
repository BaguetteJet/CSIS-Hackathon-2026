package ie.ul.cs4297.groupbuy.service.platform

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * One thing that happens at a time, without a user (spec B, "Timers"). Implement it as a Spring
 * bean; [type] names the timers it handles.
 *
 * [fire] runs inside the same transaction that marks the timer fired, so your effect and "fired"
 * commit together: if the instance dies halfway, nothing is committed and the other instance fires
 * it instead. Publish events from [fire] with the Outbox as usual.
 */
interface TimerHandler {
    val type: String
    fun fire(timer: DueTimer)
}

/** A timer whose time has come. [key] is what you gave [Timers.schedule], for example the deal ID. */
data class DueTimer(val type: String, val key: String, val fireAt: Instant, val payload: String?, val attempt: Int)

/**
 * Schedules timers. They are rows in the `timer` table, so they survive restarts and fire once
 * across both instances (each due row is claimed with SELECT ... FOR UPDATE SKIP LOCKED).
 *
 * ```
 * timers.schedule("cut-off", dealId.toString(), deal.cutoffAt)
 * ```
 *
 * Times come from the platform clock: in tests, move the MutableClock forward and the timer fires.
 */
@Component
class Timers(private val jdbc: JdbcTemplate) {

    /**
     * Schedules the timer [type] for [key] at [fireAt]. One timer per (type, key): scheduling it
     * again moves a timer that has not fired yet, and leaves a fired one alone.
     */
    fun schedule(type: String, key: String, fireAt: Instant, payload: String? = null) {
        jdbc.update(
            "INSERT INTO timer (timer_type, timer_key, fire_at, payload) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE fire_at = IF(status = 'PENDING', VALUES(fire_at), fire_at), " +
                "payload = IF(status = 'PENDING', VALUES(payload), payload)",
            type, key, Timestamp.from(fireAt), payload,
        )
    }

    /** Cancels a timer that has not fired. Returns false if there was none, or it had fired. */
    fun cancel(type: String, key: String): Boolean =
        jdbc.update("UPDATE timer SET status = 'CANCELLED' WHERE timer_type = ? AND timer_key = ? AND status = 'PENDING'", type, key) == 1

    /** PENDING, FIRED, CANCELLED or FAILED; null if there is no such timer. */
    fun status(type: String, key: String): String? =
        jdbc.query("SELECT status FROM timer WHERE timer_type = ? AND timer_key = ?", { rs, _ -> rs.getString(1) }, type, key).firstOrNull()
}

/** Fires due timers. Both instances run it; the row lock makes sure each timer fires once. */
@Component
class TimerRunner(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val settings: PlatformSettings,
    handlers: List<TimerHandler>,
    meters: io.micrometer.core.instrument.MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlers: Map<String, TimerHandler> = handlers.associateBy { it.type }.also {
        require(it.size == handlers.size) { "Two TimerHandlers have the same type: ${handlers.groupBy { h -> h.type }.filter { g -> g.value.size > 1 }.keys}" }
    }

    init {
        meters.gauge("timers.pending.due", this) { runner ->
            runCatching {
                runner.jdbc.queryForObject("SELECT COUNT(*) FROM timer WHERE status = 'PENDING' AND fire_at <= ?", Long::class.java, Timestamp.from(runner.clock.instant()))!!.toDouble()
            }.getOrDefault(Double.NaN)
        }
    }

    @Scheduled(fixedDelayString = "\${groupbuy.timers.poll-millis:500}")
    fun fireDue() {
        try {
            repeat(50) { if (!fireOne()) return }
        } catch (e: Exception) {
            log.warn("Timer runner paused: {}", e.message)
        }
    }

    /** Claims one due timer and fires it. False when none is due. */
    private fun fireOne(): Boolean {
        if (handlers.isEmpty()) return false
        var failed: Pair<Long, Exception>? = null
        val fired = tx.execute {
            // Only types this instance can handle: during a deploy, an older instance never takes
            // a newer instance's timers. A type with no handler anywhere waits; see the metric timers_pending_due on /actuator/prometheus.
            val types = handlers.keys.joinToString(",") { "?" }
            val due = jdbc.query(
                "SELECT id, timer_type, timer_key, fire_at, payload, attempts FROM timer " +
                    "WHERE status = 'PENDING' AND fire_at <= ? AND timer_type IN ($types) ORDER BY fire_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
                { rs, _ -> rs.getLong(1) to DueTimer(rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getInt(6) + 1) },
                Timestamp.from(clock.instant()), *handlers.keys.toTypedArray(),
            ).firstOrNull() ?: return@execute false
            val (id, timer) = due
            val handler = handlers.getValue(timer.type)
            try {
                handler.fire(timer)
            } catch (e: Exception) {
                failed = id to e
                it.setRollbackOnly()
                return@execute true
            }
            jdbc.update(
                "UPDATE timer SET status = 'FIRED', fired_at = ?, fired_by = ?, attempts = attempts + 1 WHERE id = ?",
                Timestamp.from(clock.instant()), settings.instanceId, id,
            )
            true
        }!!
        failed?.let { (id, e) -> retryLater(id, e) }
        return fired
    }

    /** A handler that throws is tried again later: after 1, 2, 4 ... seconds, up to 10 attempts. */
    private fun retryLater(id: Long, e: Exception) {
        val attempts = jdbc.queryForObject("SELECT attempts FROM timer WHERE id = ?", Int::class.java, id)!! + 1
        if (attempts >= 10) {
            jdbc.update("UPDATE timer SET status = 'FAILED', attempts = ?, last_error = ? WHERE id = ?", attempts, e.toString().take(2000), id)
            log.error("Timer {} failed 10 times and was given up: {}", id, e.toString())
        } else {
            val next = clock.instant().plus(Duration.ofSeconds(1L shl (attempts - 1)))
            jdbc.update("UPDATE timer SET attempts = ?, last_error = ?, fire_at = ? WHERE id = ?", attempts, e.toString().take(2000), Timestamp.from(next), id)
            log.warn("Timer {} failed (attempt {}), trying again at {}: {}", id, attempts, next, e.toString())
        }
    }
}
