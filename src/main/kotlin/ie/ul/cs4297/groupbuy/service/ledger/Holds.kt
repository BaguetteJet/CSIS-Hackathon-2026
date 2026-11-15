package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.api.ApiPaths
import ie.ul.cs4297.groupbuy.contracts.api.Balance
import ie.ul.cs4297.groupbuy.contracts.api.HoldRequest
import ie.ul.cs4297.groupbuy.contracts.api.HoldResponse
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import ie.ul.cs4297.groupbuy.contracts.events.HoldPlaced
import ie.ul.cs4297.groupbuy.contracts.events.HoldRejected
import ie.ul.cs4297.groupbuy.contracts.events.HoldRejectedReason
import ie.ul.cs4297.groupbuy.service.platform.ApiProblem
import ie.ul.cs4297.groupbuy.service.platform.ApiProblem.FieldError
import ie.ul.cs4297.groupbuy.service.platform.Invalid
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [LED-03] - Place a hold
 * [LED-04] - Hold repeats and refusals
 * [LED-19] - Bad input
 * POST /internal/ledger/holds. Internal only: no token.
 * Body read by hand: the contract wants 422 with errors[] (not Spring's 415) and 413 over 64 KB.
 */
@RestController
class HoldController(private val holds: Holds, mapper: ObjectMapper) {
    // Exactly one JSON object: Jackson's default reads `{...} garbage` as the object, and a field given twice as its last value.
    private val reader = mapper.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @PostMapping(ApiPaths.PLACE_HOLD)
    fun place(request: HttpServletRequest): HoldResponse = holds.placeWithinDeadline(read(request))

    /** The body as a [HoldRequest], or 413 / 422. */
    private fun read(request: HttpServletRequest): HoldRequest {
        // Too big: refuse early when the size is sent, and never read more than 64 KB + 1 byte when it isn't.
        if (request.contentLengthLong > MAX_BODY_BYTES) throw tooLarge()
        if (!isJson(request.contentType)) {
            throw Invalid("The Content-Type must be application/json", listOf(FieldError("Content-Type", "must be application/json")))
        }
        val bytes = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw tooLarge()

        val body: JsonNode? = try {
            reader.readTree(bytes)
        } catch (e: JacksonException) {
            null
        }
        if (body == null || !body.isObject) throw Invalid("The request body is not a JSON object")
        return HoldFields(body).read()
    }

    private fun isJson(contentType: String?): Boolean {
        if (contentType == null) return false
        return try {
            MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType))
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun tooLarge() = ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request body is over 64 KB")

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
    }
}

/** Reads a hold body field by field, collecting every problem so Orders sees them all at once. Unknown fields are ignored. */
private class HoldFields(private val body: JsonNode) {
    private val errors = mutableListOf<FieldError>()

    fun read(): HoldRequest {
        val orderId = uuid("orderId")
        val studentId = uuid("studentId")
        val dealId = uuid("dealId")
        val cutoffAt = utcTime("cutoffAt") // checked but not used: the Ledger never refuses for time (DEC X1)
        val amountCents = wholeCents("amountCents")
        if (errors.isNotEmpty()) throw Invalid("The request has invalid fields", errors)
        return HoldRequest(orderId!!, studentId!!, dealId!!, cutoffAt!!, amountCents!!)
    }

    private fun uuid(field: String): UUID? {
        val node = required(field) ?: return null
        if (!node.isTextual || !UUID_PATTERN.matches(node.textValue())) {
            errors.add(FieldError(field, "must be a UUID"))
            return null
        }
        return UUID.fromString(node.textValue())
    }

    private fun utcTime(field: String): Instant? {
        val node = required(field) ?: return null
        if (node.isTextual && UTC_PATTERN.matches(node.textValue())) {
            try {
                return Instant.parse(node.textValue())
            } catch (e: java.time.format.DateTimeParseException) {
                // a well-shaped but impossible date, like 2026-13-40: falls through to the error
            }
        }
        errors.add(FieldError(field, "must be a UTC date-time ending in Z"))
        return null
    }

    // Whole cents only: 6.5, "600" and overflow are refused, not rounded.
    private fun wholeCents(field: String): Long? {
        val node = required(field) ?: return null
        if (!node.isIntegralNumber || !node.canConvertToLong()) {
            errors.add(FieldError(field, "must be a whole number of cents"))
            return null
        }
        if (node.longValue() < 1) {
            errors.add(FieldError(field, "must be at least 1"))
            return null
        }
        return node.longValue()
    }

    private fun required(field: String): JsonNode? {
        val node = body.get(field)
        if (node == null || node.isNull) {
            errors.add(FieldError(field, "is required"))
            return null
        }
        return node
    }

    companion object {
        // Same patterns as ledger-internal.yaml.
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val UTC_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$""")
    }
}

/**
 * Decides each order's hold once, on either instance, in one transaction (decision 0003):
 * claim the orderId, refuse if closed or short, otherwise move AVAILABLE to HELD; the answer and its event commit with the money.
 * Orders waits 2 s: 1 s timeout, deadlocks retried, any other DB error is a 503 that Orders retries.
 * The answer never waits past [deadlineMillis]: a pool wait, the timeout's rounding and a retry could add up to more than 2 s.
 */
@Service
class Holds(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
    private val outbox: Outbox,
    private val clock: Clock,
    private val meters: MeterRegistry,
    transactions: PlatformTransactionManager,
    // 1.5 s, not nearer 2: a 0.36 s stop-the-world GC pause came on top of it in the 2,000-order drill (EDGE-CASES Q25).
    @Value("\${groupbuy.ledger.hold-deadline-ms:1500}") private val deadlineMillis: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactions).apply { timeout = 1 }

    // Bounded: with MySQL frozen, calls past the queue answer 503 at once instead of piling up threads.
    private val deciders = ThreadPoolExecutor(20, 20, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(200)) { r ->
        Thread(r, "hold-decision").apply { isDaemon = true }
    }

    @PreDestroy
    fun stop() = deciders.shutdown()

    /**
     * The HTTP answer: [place] on its own thread, waited for until the deadline. Past it, answer 503 and let the decision
     * finish: it commits or rolls back whole, and Orders' retry with the same orderId gets the stored answer or decides afresh (decision 0003).
     */
    fun placeWithinDeadline(request: HoldRequest): HoldResponse {
        val started = System.nanoTime()
        val decision = try {
            CompletableFuture.supplyAsync({
                // Queued past the deadline: nobody is waiting for this answer, and Orders will retry.
                if (System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(deadlineMillis)) throw unavailable(TimeoutException("queued past the deadline"))
                place(request)
            }, deciders)
        } catch (e: RejectedExecutionException) {
            throw unavailable(e)
        }
        try {
            return decision.get(deadlineMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw unavailable(TimeoutException("no decision within $deadlineMillis ms"))
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Decides in the caller's thread, joining its transaction if it has one. */
    fun place(request: HoldRequest): HoldResponse {
        val started = System.nanoTime()
        var result = "UNAVAILABLE"
        try {
            val response = decide(request, started)
            result = response.result.name
            return response
        } finally {
            // [LED-23] - Observability: ledger_holds_seconds{result} for Grafana
            meters.timer("ledger.holds", "result", result).record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
    }

    private fun decide(request: HoldRequest, started: Long): HoldResponse {
        while (true) {
            try {
                return tx.execute { decideOnce(request) }!!
            } catch (e: PessimisticLockingFailureException) {
                // Deadlock: nothing saved, safe to retry.
                if (System.nanoTime() - started > RETRY_WINDOW.toNanos()) throw unavailable(e)
            } catch (e: DataAccessException) {
                throw unavailable(e)
            } catch (e: TransactionException) {
                throw unavailable(e) // e.g. no connection within 1 s
            }
        }
    }

    private fun decideOnce(r: HoldRequest): HoldResponse {
        // A repeat: the first answer, whatever the body says.
        if (!claim(r)) return storedAnswer(r.orderId)

        val closed = closedReason(r)
        // [LED-20] - Two instances, one truth: every answer's event carries this balance. A student we've never seen starts at 0.
        val balance = accounts.lockStudent(r.studentId)

        if (closed != null) return refuse(r, closed, balance)
        if (balance.availableCents < r.amountCents) return refuse(r, HoldRejectedReason.INSUFFICIENT_FUNDS, balance)
        return placeHold(r, balance)
    }

    /** Insert the order's row, or false if another call already did. PENDING never commits. */
    private fun claim(r: HoldRequest): Boolean {
        try {
            jdbc.update(
                "INSERT INTO hold_decision (order_id, student_id, deal_id, amount_cents, result, decided_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                r.orderId.toString(), r.studentId.toString(), r.dealId.toString(), r.amountCents, Timestamp.from(clock.instant()),
            )
            return true
        } catch (e: DuplicateKeyException) {
            return false
        }
    }

    /** ORDER_CLOSED before DEAL_CLOSED (DEC Ledger). FOR SHARE: wait for a closure in progress, and block a new one until we commit. */
    private fun closedReason(r: HoldRequest): HoldRejectedReason? {
        if (exists("SELECT 1 FROM order_closure WHERE order_id = ? FOR SHARE", r.orderId)) return HoldRejectedReason.ORDER_CLOSED
        if (exists("SELECT 1 FROM deal_closure WHERE deal_id = ? FOR SHARE", r.dealId)) return HoldRejectedReason.DEAL_CLOSED
        return null
    }

    private fun refuse(r: HoldRequest, reason: HoldRejectedReason, balance: Balance): HoldResponse {
        // Only a funds refusal tells Orders how much is available.
        val response = if (reason == HoldRejectedReason.INSUFFICIENT_FUNDS) {
            HoldResponse(r.orderId, HoldResult.INSUFFICIENT_FUNDS, balance.availableCents)
        } else {
            HoldResponse(r.orderId, HoldResult.valueOf(reason.name))
        }
        val event = HoldRejected(r.orderId, r.dealId, r.studentId, reason, balance.availableCents, balance.heldCents)
        return save(r, response, event)
    }

    private fun placeHold(r: HoldRequest, balance: Balance): HoldResponse {
        val lines = listOf(
            PostingLine(AccountRef.studentAvailable(r.studentId), -r.amountCents,
                EntryDetails(LedgerEntryKind.HOLD, "Set aside for your order", r.dealId, r.orderId)),
            PostingLine(AccountRef.studentHeld(r.studentId), r.amountCents),
        )
        val posted = postings.post(MovementRef(MovementType.HOLD, r.orderId), lines)
        check(posted) { "order ${r.orderId} already had a hold movement" } // can't happen: the orderId was claimed above

        val event = HoldPlaced(r.orderId, r.dealId, r.studentId, r.amountCents,
            balance.availableCents - r.amountCents, balance.heldCents + r.amountCents)
        return save(r, HoldResponse(r.orderId, HoldResult.PLACED), event)
    }

    /** Save the answer for repeats and queue the event: both commit with the money. A placed hold starts OPEN (V6). */
    private fun save(r: HoldRequest, response: HoldResponse, event: ContractEvent): HoldResponse {
        val state = if (response.result == HoldResult.PLACED) "OPEN" else null
        jdbc.update(
            "UPDATE hold_decision SET result = ?, available_cents = ?, state = ? WHERE order_id = ?",
            response.result.name, response.availableCents, state, r.orderId.toString(),
        )
        outbox.publish(event)
        return response
    }

    /** The first call's answer. FOR SHARE: read the commit we just waited for. */
    private fun storedAnswer(orderId: UUID): HoldResponse = jdbc.queryForObject(
        "SELECT result, available_cents FROM hold_decision WHERE order_id = ? FOR SHARE",
        { rs, _ ->
            val result = HoldResult.valueOf(rs.getString(1))
            val available = rs.getLong(2)
            HoldResponse(orderId, result, if (rs.wasNull()) null else available)
        },
        orderId.toString(),
    )!!

    private fun exists(sql: String, id: UUID): Boolean = jdbc.queryForList(sql, id.toString()).isNotEmpty()

    private fun unavailable(e: Exception): ApiProblem {
        log.warn("Hold call answered 503: {}", e.message)
        return ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "The Ledger is briefly unavailable; try again")
    }

    companion object {
        /** Retry deadlocks for up to 1 s: still inside Orders' 2 s. */
        private val RETRY_WINDOW: Duration = Duration.ofMillis(1000)
    }
}
