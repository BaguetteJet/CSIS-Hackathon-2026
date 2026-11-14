package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.api.ApiPaths
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
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
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
import java.util.concurrent.TimeUnit

/**
 * POST /internal/ledger/holds (LED-03, LED-04). Internal only: no token.
 * Body parsed by hand: the contract wants 422 with errors[] (not Spring's 415) and 413 over 64 KB.
 */
@RestController
class HoldController(private val holds: Holds, private val mapper: ObjectMapper) {

    @PostMapping(ApiPaths.PLACE_HOLD)
    fun place(request: HttpServletRequest): HoldResponse = holds.place(read(request))

    /** Turn the raw request into a [HoldRequest] or throw 413/422 naming every bad field. */
    private fun read(request: HttpServletRequest): HoldRequest {
        // Reject early when Content-Length is too big...
        if (request.contentLengthLong > MAX_BODY_BYTES) throw tooLarge()
        val type = runCatching { MediaType.parseMediaType(request.contentType) }.getOrNull()
        if (type == null || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)) {
            throw Invalid("The Content-Type must be application/json", listOf(FieldError("Content-Type", "must be application/json")))
        }
        // ...and cap the read when it isn't sent.
        val bytes = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw tooLarge()
        val body = try { mapper.readTree(bytes) } catch (e: JacksonException) { null }
        if (body == null || !body.isObject) throw Invalid("The request body is not a JSON object")

        // Report every bad field at once. Ignore unknown fields.
        val errors = mutableListOf<FieldError>()
        fun present(field: String): JsonNode? = body.get(field)?.takeUnless { it.isNull }
            .also { if (it == null) errors += FieldError(field, "is required") }
        fun uuid(field: String): UUID? = present(field)?.let { node ->
            node.takeIf { it.isTextual && UUID_PATTERN.matches(it.textValue()) }?.let { UUID.fromString(it.textValue()) }
                .also { if (it == null) errors += FieldError(field, "must be a UUID") }
        }
        val orderId = uuid("orderId")
        val studentId = uuid("studentId")
        val dealId = uuid("dealId")
        // Checked but not used: the Ledger never refuses for time (DEC X1).
        val cutoffAt = present("cutoffAt")?.let { node ->
            node.takeIf { it.isTextual && UTC_PATTERN.matches(it.textValue()) }
                ?.let { runCatching { Instant.parse(it.textValue()) }.getOrNull() }
                .also { if (it == null) errors += FieldError("cutoffAt", "must be a UTC date-time ending in Z") }
        }
        // Whole cents only: 6.5, "600" and overflow are refused, not rounded.
        val amountCents = present("amountCents")?.let { node ->
            when {
                !node.isIntegralNumber || !node.canConvertToLong() -> null.also { errors += FieldError("amountCents", "must be a whole number of cents") }
                node.longValue() < 1 -> null.also { errors += FieldError("amountCents", "must be at least 1") }
                else -> node.longValue()
            }
        }
        if (errors.isNotEmpty()) throw Invalid("The request has invalid fields", errors)
        return HoldRequest(orderId!!, studentId!!, dealId!!, cutoffAt!!, amountCents!!)
    }

    private fun tooLarge() = ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "The request body is over 64 KB")

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        // Same patterns as ledger-internal.yaml.
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val UTC_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$""")
    }
}

/**
 * Decide each order's hold once, on either instance in one transaction:
 * 1. Claim the orderId in hold_decision. Already taken: return the saved answer.
 * 2. Closed order or deal: refuse. Otherwise lock the student and move AVAILABLE to HELD if they can pay.
 * 3. Save the answer and queue the event: both commit with the money.
 *
 * Order before student: same lock order as top-ups, so no deadlock.
 * Orders waits 2 s: 1 s timeout, deadlocks retried, any other DB error is a 503 that Orders retries.
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
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactions).apply { timeout = 1 }

    fun place(request: HoldRequest): HoldResponse {
        val started = System.nanoTime()
        var result = "UNAVAILABLE"
        try {
            return decide(request, started).also { result = it.result.name }
        } finally {
            // ledger_holds_seconds{result} for Grafana (LED-23).
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
        // 1. Claim the order. PENDING never commits.
        try {
            jdbc.update(
                "INSERT INTO hold_decision (order_id, student_id, deal_id, amount_cents, result, decided_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                r.orderId.toString(), r.studentId.toString(), r.dealId.toString(), r.amountCents, Timestamp.from(clock.instant()),
            )
        } catch (e: DuplicateKeyException) {
            return firstAnswer(r.orderId)
        }

        // 2. Decide: ORDER_CLOSED, then DEAL_CLOSED, then funds (DEC Ledger).
        val closed = when {
            isClosed("SELECT 1 FROM order_closure WHERE order_id = ? FOR SHARE", r.orderId) -> HoldRejectedReason.ORDER_CLOSED
            isClosed("SELECT 1 FROM deal_closure WHERE deal_id = ? FOR SHARE", r.dealId) -> HoldRejectedReason.DEAL_CLOSED
            else -> null
        }
        // Locked balance for the event (LED-20). Unknown student: starts at 0 (D-A1).
        val now = accounts.lockStudent(r.studentId)
        if (closed != null) {
            return answer(r, HoldResponse(r.orderId, HoldResult.valueOf(closed.name)),
                HoldRejected(r.orderId, r.dealId, r.studentId, closed, now.availableCents, now.heldCents))
        }
        if (now.availableCents < r.amountCents) {
            return answer(r, HoldResponse(r.orderId, HoldResult.INSUFFICIENT_FUNDS, now.availableCents),
                HoldRejected(r.orderId, r.dealId, r.studentId, HoldRejectedReason.INSUFFICIENT_FUNDS, now.availableCents, now.heldCents))
        }
        // Can't exist yet: the orderId was claimed above.
        check(postings.post(MovementRef(MovementType.HOLD, r.orderId), listOf(
            PostingLine(AccountRef.studentAvailable(r.studentId), -r.amountCents,
                EntryDetails(LedgerEntryKind.HOLD, "Set aside for your order", r.dealId, r.orderId)),
            PostingLine(AccountRef.studentHeld(r.studentId), r.amountCents),
        ))) { "order ${r.orderId} already had a hold movement" }
        return answer(r, HoldResponse(r.orderId, HoldResult.PLACED),
            HoldPlaced(r.orderId, r.dealId, r.studentId, r.amountCents, now.availableCents - r.amountCents, now.heldCents + r.amountCents))
    }

    /** FOR SHARE: wait for a closure in progress, and block a new one until we commit. */
    private fun isClosed(sql: String, id: UUID): Boolean = jdbc.queryForList(sql, id.toString()).isNotEmpty()

    /** 3. Save the answer for repeats and queue the event. */
    private fun answer(r: HoldRequest, response: HoldResponse, event: ContractEvent): HoldResponse {
        // A placed hold starts OPEN; releases and settlement move it on (V6__hold_state.sql).
        jdbc.update(
            "UPDATE hold_decision SET result = ?, available_cents = ?, state = ? WHERE order_id = ?",
            response.result.name, response.availableCents,
            "OPEN".takeIf { response.result == HoldResult.PLACED }, r.orderId.toString(),
        )
        outbox.publish(event)
        return response
    }

    /** The saved answer. FOR SHARE: read the commit we just waited for. */
    private fun firstAnswer(orderId: UUID): HoldResponse = jdbc.queryForObject(
        "SELECT result, available_cents FROM hold_decision WHERE order_id = ? FOR SHARE",
        { rs, _ -> HoldResponse(orderId, HoldResult.valueOf(rs.getString(1)), rs.getLong(2).takeUnless { rs.wasNull() }) },
        orderId.toString(),
    )!!

    private fun unavailable(e: Exception): ApiProblem {
        log.warn("Hold call answered 503: {}", e.message)
        return ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "The Ledger is briefly unavailable; try again")
    }

    companion object {
        /** Retry deadlocks for up to 1 s: still inside Orders' 2 s. */
        private val RETRY_WINDOW: Duration = Duration.ofMillis(1000)
    }
}
