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
 * POST /internal/ledger/holds: Orders asks "can this student afford this order?" (LED-03, LED-04).
 *
 * Only Orders calls it, from inside the Docker network, so there is no token. The starter already
 * leaves /internal/ ** open in platform/Security.kt, and the gateway never routes it.
 *
 * We read the body ourselves instead of using @RequestBody. Spring would answer 415 for a wrong
 * Content-Type and has no size limit; the contract wants 422 with errors[] for every bad input
 * (DEC E3) and 413 over 64 KB (DEC E5).
 */
@RestController
class HoldController(private val holds: Holds, private val mapper: ObjectMapper) {

    @PostMapping(ApiPaths.PLACE_HOLD)
    fun place(request: HttpServletRequest): HoldResponse = holds.place(read(request))

    /** Turns the raw request into a [HoldRequest], or throws 413/422 naming every bad field. */
    private fun read(request: HttpServletRequest): HoldRequest {
        // Refuse a big body before reading it when the client tells us its size...
        if (request.contentLengthLong > MAX_BODY_BYTES) throw tooLarge()
        val type = runCatching { MediaType.parseMediaType(request.contentType) }.getOrNull()
        if (type == null || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)) {
            throw Invalid("The Content-Type must be application/json", listOf(FieldError("Content-Type", "must be application/json")))
        }
        // ...and still never read more than 64 KB + 1 byte when it doesn't.
        val bytes = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw tooLarge()
        val body = try { mapper.readTree(bytes) } catch (e: JacksonException) { null }
        if (body == null || !body.isObject) throw Invalid("The request body is not a JSON object")

        // Collect every problem, not just the first, so Orders sees them all at once.
        // Unknown fields are ignored, as the contract says.
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
        // We don't act on cutoffAt (the Ledger never refuses for time, DEC X1), but a malformed one is still bad input.
        val cutoffAt = present("cutoffAt")?.let { node ->
            node.takeIf { it.isTextual && UTC_PATTERN.matches(it.textValue()) }
                ?.let { runCatching { Instant.parse(it.textValue()) }.getOrNull() }
                .also { if (it == null) errors += FieldError("cutoffAt", "must be a UTC date-time ending in Z") }
        }
        // Whole cents only: 6.5, "600" and anything past a Long are refused, not rounded.
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
        // The same patterns as ledger-internal.yaml.
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val UTC_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$""")
    }
}

/**
 * Decides each order's hold exactly once, however many times and on whichever instance Orders asks.
 *
 * One transaction does three things:
 *  1. Claim the orderId by inserting its hold_decision row. If the row already exists, this is a
 *     repeat: return the saved answer and do nothing else, even if the body changed. If another
 *     instance is deciding this order right now, the insert waits for it to commit, then lands here.
 *  2. Refuse a closed order or deal ([ClosuresConsumer] records those), otherwise lock the
 *     student's accounts ([LedgerAccounts.lockStudent]) and, if there's enough available, post
 *     the movement from AVAILABLE to HELD ([LedgerPostingService]). Two different orders for the
 *     same student are decided one after the other.
 *  3. Save the answer and put HoldPlaced or HoldRejected in the outbox. The event only exists if
 *     the money moved, and it carries the balance after the decision.
 *
 * Why the orderId comes first: a retry with a different studentId never touches the second
 * student, and the lock order (business key, then student) is the same as for top-ups, so the
 * two can't deadlock each other.
 *
 * Orders gives up after 2 seconds. So the transaction times out after 1 second, a deadlock is
 * retried while there's still time, and anything else from the database is a 503 UNAVAILABLE,
 * which Orders treats as "ask again later". A 503 saves nothing, so the retry decides afresh.
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
            // Shows up as ledger_holds_seconds{result=...}: call rate, latency and outcome for Grafana (LED-23).
            meters.timer("ledger.holds", "result", result).record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
    }

    private fun decide(request: HoldRequest, started: Long): HoldResponse {
        while (true) {
            try {
                return tx.execute { decideOnce(request) }!!
            } catch (e: PessimisticLockingFailureException) {
                // MySQL broke a deadlock by rolling us back. Nothing was saved, so trying again is safe.
                if (System.nanoTime() - started > RETRY_WINDOW.toNanos()) throw unavailable(e)
            } catch (e: DataAccessException) {
                throw unavailable(e)
            } catch (e: TransactionException) {
                throw unavailable(e) // includes "couldn't even get a connection"
            }
        }
    }

    private fun decideOnce(r: HoldRequest): HoldResponse {
        // 1. Claim the order. PENDING is never seen by anyone else: it's replaced before we commit.
        try {
            jdbc.update(
                "INSERT INTO hold_decision (order_id, student_id, deal_id, amount_cents, result, decided_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                r.orderId.toString(), r.studentId.toString(), r.dealId.toString(), r.amountCents, Timestamp.from(clock.instant()),
            )
        } catch (e: DuplicateKeyException) {
            return firstAnswer(r.orderId)
        }

        // 2. Decide. Closed beats broke: ORDER_CLOSED, then DEAL_CLOSED, then funds (DEC Ledger).
        val closed = when {
            isClosed("SELECT 1 FROM order_closure WHERE order_id = ? FOR SHARE", r.orderId) -> HoldRejectedReason.ORDER_CLOSED
            isClosed("SELECT 1 FROM deal_closure WHERE deal_id = ? FOR SHARE", r.dealId) -> HoldRejectedReason.DEAL_CLOSED
            else -> null
        }
        // Every answer's event carries the balance, read under the student's lock (LED-20). A
        // student we've never seen gets accounts at 0, so they're refused (D-A1, LED-01).
        val now = accounts.lockStudent(r.studentId)
        if (closed != null) {
            return answer(r, HoldResponse(r.orderId, HoldResult.valueOf(closed.name)),
                HoldRejected(r.orderId, r.dealId, r.studentId, closed, now.availableCents, now.heldCents))
        }
        if (now.availableCents < r.amountCents) {
            return answer(r, HoldResponse(r.orderId, HoldResult.INSUFFICIENT_FUNDS, now.availableCents),
                HoldRejected(r.orderId, r.dealId, r.studentId, HoldRejectedReason.INSUFFICIENT_FUNDS, now.availableCents, now.heldCents))
        }
        // The orderId was claimed above, so this movement can't already exist.
        check(postings.post(MovementRef(MovementType.HOLD, r.orderId), listOf(
            PostingLine(AccountRef.studentAvailable(r.studentId), -r.amountCents,
                EntryDetails(LedgerEntryKind.HOLD, "Set aside for your order", r.dealId, r.orderId)),
            PostingLine(AccountRef.studentHeld(r.studentId), r.amountCents),
        ))) { "order ${r.orderId} already had a hold movement" }
        return answer(r, HoldResponse(r.orderId, HoldResult.PLACED),
            HoldPlaced(r.orderId, r.dealId, r.studentId, r.amountCents, now.availableCents - r.amountCents, now.heldCents + r.amountCents))
    }

    /**
     * FOR SHARE, not a plain read: if the closing event is being written right now, wait for it.
     * With no row it also blocks a closure from being written until we commit, so the release
     * work (later) is guaranteed to see this hold.
     */
    private fun isClosed(sql: String, id: UUID): Boolean = jdbc.queryForList(sql, id.toString()).isNotEmpty()

    /** 3. Remember the answer for repeats, and announce it. Both commit with the money or not at all. */
    private fun answer(r: HoldRequest, response: HoldResponse, event: ContractEvent): HoldResponse {
        jdbc.update(
            "UPDATE hold_decision SET result = ?, available_cents = ? WHERE order_id = ?",
            response.result.name, response.availableCents, r.orderId.toString(),
        )
        outbox.publish(event)
        return response
    }

    /** The answer the first call saved. FOR SHARE makes sure we read the commit we just waited for. */
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
        /** Deadlocks are detected instantly, so retrying for up to a second still answers inside Orders' 2 s. */
        private val RETRY_WINDOW: Duration = Duration.ofMillis(1000)
    }
}
