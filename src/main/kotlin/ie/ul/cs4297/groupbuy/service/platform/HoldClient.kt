package ie.ul.cs4297.groupbuy.service.platform

import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.api.ApiPaths
import ie.ul.cs4297.groupbuy.contracts.api.HoldRequest
import ie.ul.cs4297.groupbuy.contracts.api.HoldResponse
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The hold call, Orders to Ledger (Appendix A, "The hold call"). Only Orders uses it.
 *
 * [placeHold] answers PLACED, INSUFFICIENT_FUNDS, DEAL_CLOSED or ORDER_CLOSED. Repeating it with
 * the same orderId gives the same answer, so retrying is always safe. It waits at most 2 seconds
 * and then throws [HoldUnavailable]: what Orders does then is Orders' design.
 *
 * With LEDGER_URL set, it calls LEDGER_URL (the dev kit's hold stub, the fake Ledger or the real
 * one). Without it, it answers inside the service, with HOLD_STUB_MODE placed, insufficient, slow
 * or down. HOLD_CLIENT=http or HOLD_CLIENT=stub forces either.
 */
interface HoldClient {
    fun placeHold(request: HoldRequest): HoldResponse
}

/** The Ledger did not answer within 2 seconds, or could not be reached. */
class HoldUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class HttpHoldClient(ledgerUrl: String, private val timeout: Duration = Duration.ofSeconds(2)) : HoldClient {
    private val uri = URI.create(ledgerUrl.trimEnd('/') + ApiPaths.PLACE_HOLD)
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()

    override fun placeHold(request: HoldRequest): HoldResponse {
        val call = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(ContractJson.mapper.writeValueAsString(request)))
            .build()
        val response = try {
            http.send(call, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw HoldUnavailable("the Ledger did not answer within ${timeout.toMillis()} ms", e)
        } catch (e: java.io.IOException) {
            throw HoldUnavailable("the Ledger could not be reached at $uri: ${e.message}", e)
        }
        if (response.statusCode() != 200) throw HoldUnavailable("the Ledger answered ${response.statusCode()}: ${response.body().take(300)}")
        return ContractJson.mapper.readValue(response.body(), HoldResponse::class.java)
    }
}

/** Answers inside the service, for tests and for working without a Ledger. Same answer per orderId. */
class StubHoldClient(var mode: String = "placed", private val availableCents: Long = 1_000) : HoldClient {
    private val answers = ConcurrentHashMap<UUID, HoldResponse>()

    override fun placeHold(request: HoldRequest): HoldResponse = when (mode) {
        "down" -> throw HoldUnavailable("the hold stub is in mode 'down'")
        "slow" -> { Thread.sleep(3_000); throw HoldUnavailable("the hold stub is in mode 'slow' (3 s, over the 2 s limit)") }
        else -> answers.computeIfAbsent(request.orderId) {
            when (mode) {
                "insufficient" -> HoldResponse(request.orderId, HoldResult.INSUFFICIENT_FUNDS, availableCents)
                else -> HoldResponse(request.orderId, HoldResult.PLACED)
            }
        }
    }
}

@Configuration
class HoldClientConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun holdClient(settings: PlatformSettings): HoldClient = when (choose(settings.hold.client, settings.hold.ledgerUrl)) {
        "http" -> {
            require(settings.hold.ledgerUrl.isNotBlank()) { "HOLD_CLIENT=http needs LEDGER_URL, for example http://ledger:8080" }
            log.info("Hold client: HTTP to {}", settings.hold.ledgerUrl)
            HttpHoldClient(settings.hold.ledgerUrl)
        }
        else -> StubHoldClient(settings.hold.stubMode).also {
            if (settings.serviceName == "orders") {
                log.warn("Hold client: the in-process STUB (mode {}). No hold call reaches a Ledger. Set LEDGER_URL (service.env) to call one.", settings.hold.stubMode)
            } else {
                log.info("Hold client: stub, mode {}", settings.hold.stubMode)
            }
        }
    }

    companion object {
        /** HOLD_CLIENT if set; otherwise http when LEDGER_URL is set, stub when it is not. */
        fun choose(client: String, ledgerUrl: String): String = when (client.trim()) {
            "http", "stub" -> client.trim()
            "" -> if (ledgerUrl.isNotBlank()) "http" else "stub"
            else -> throw IllegalArgumentException("HOLD_CLIENT is '$client'; use http, stub, or leave it unset (http when LEDGER_URL is set)")
        }
    }
}
