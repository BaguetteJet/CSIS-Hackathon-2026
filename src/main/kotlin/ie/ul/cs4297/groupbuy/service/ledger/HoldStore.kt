package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * The first answer to one hold call.
 *
 * [first] is true only for the call that decided it; every repeat gets the same result with
 * `first = false` and must not publish another event. [availableCents] and [heldCents] are the
 * student's balances at decision time, which is what the hold events carry.
 */
data class HoldReservation(
    val orderId: UUID,
    val result: HoldResult,
    val availableCents: Long,
    val heldCents: Long,
    val first: Boolean,
)

/**
 * The hold handoff for the synchronous hold call (LED-03, LED-04).
 *
 * [reserve] decides PLACED or INSUFFICIENT_FUNDS and moves the money; [refuse] records a
 * DEAL_CLOSED or ORDER_CLOSED decision that moves no money. Both keep the first answer for an
 * orderId in `ledger_hold_decision`, so a repeat with another amount, student or deal returns the
 * first answer and publishes nothing. The hold API checks [first] before checking closures, then
 * calls [refuse] for a closed order/deal or [reserve] otherwise.
 *
 * Both operations use `TransactionTemplate` with REQUIRED propagation. Call them inside the
 * transaction that publishes HoldPlaced/HoldRejected, so the decision and the event commit
 * together.
 */
@Component
class HoldStore(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {

    /** The first decision for [orderId], or null when this is the first call. */
    fun first(orderId: UUID): HoldReservation? = read(orderId, lock = false)

    /**
     * Decides PLACED or INSUFFICIENT_FUNDS for a first call and moves available cents to held
     * when it is placed. A repeat returns the stored first answer.
     */
    fun reserve(orderId: UUID, dealId: UUID, studentId: UUID, amountCents: Long): HoldReservation {
        require(amountCents > 0) { "a hold amount must be positive" }
        return transactions.execute { reserveInTransaction(orderId, dealId, studentId, amountCents) }!!
    }

    /**
     * Records DEAL_CLOSED or ORDER_CLOSED for a first call. No money moves. A repeat returns the
     * stored first answer, whatever result this call asks for.
     */
    fun refuse(
        orderId: UUID,
        dealId: UUID,
        studentId: UUID,
        amountCents: Long,
        result: HoldResult,
    ): HoldReservation {
        require(amountCents > 0) { "a hold amount must be positive" }
        require(result == HoldResult.DEAL_CLOSED || result == HoldResult.ORDER_CLOSED) {
            "a refusal is DEAL_CLOSED or ORDER_CLOSED, not $result"
        }
        return transactions.execute { refuseInTransaction(orderId, dealId, studentId, amountCents, result) }!!
    }

    private fun reserveInTransaction(
        orderId: UUID,
        dealId: UUID,
        studentId: UUID,
        amountCents: Long,
    ): HoldReservation {
        if (!claim(orderId, dealId, studentId, amountCents)) {
            return read(orderId, lock = true) ?: error("the hold decision for $orderId disappeared")
        }

        // Claiming the orderId first, then locking the student, is the same order in reserve and
        // refuse, so a reserve and a refuse for one order cannot deadlock each other.
        val before = accounts.lockStudent(studentId)
        if (before.availableCents < amountCents) {
            finish(orderId, HoldResult.INSUFFICIENT_FUNDS, before.availableCents, before.heldCents)
            return HoldReservation(orderId, HoldResult.INSUFFICIENT_FUNDS, before.availableCents, before.heldCents, first = true)
        }

        val posted = postings.post(
            reference = MovementRef(MovementType.HOLD, orderId),
            lines = listOf(
                PostingLine(AccountRef.studentAvailable(studentId), -amountCents),
                PostingLine(AccountRef.studentHeld(studentId), amountCents),
            ),
        )
        check(posted) { "the hold movement for $orderId was already posted without a decision" }

        val after = accounts.balance(studentId)
        finish(orderId, HoldResult.PLACED, after.availableCents, after.heldCents)
        return HoldReservation(orderId, HoldResult.PLACED, after.availableCents, after.heldCents, first = true)
    }

    private fun refuseInTransaction(
        orderId: UUID,
        dealId: UUID,
        studentId: UUID,
        amountCents: Long,
        result: HoldResult,
    ): HoldReservation {
        if (!claim(orderId, dealId, studentId, amountCents)) {
            return read(orderId, lock = true) ?: error("the hold decision for $orderId disappeared")
        }
        val before = accounts.lockStudent(studentId)
        finish(orderId, result, before.availableCents, before.heldCents)
        return HoldReservation(orderId, result, before.availableCents, before.heldCents, first = true)
    }

    /**
     * Claims the orderId with a PENDING row. The orderId primary key makes the first caller the
     * winner; a concurrent caller blocks here until the winner commits, then reads the answer.
     */
    private fun claim(orderId: UUID, dealId: UUID, studentId: UUID, amountCents: Long): Boolean = try {
        jdbc.update(
            "INSERT INTO ledger_hold_decision " +
                "(order_id, deal_id, student_id, amount_cents, result, available_cents, held_cents, decided_at) " +
                "VALUES (?, ?, ?, ?, 'PENDING', 0, 0, ?)",
            orderId.toString(), dealId.toString(), studentId.toString(), amountCents,
            Timestamp.from(clock.instant()),
        )
        true
    } catch (e: DuplicateKeyException) {
        false
    }

    private fun finish(orderId: UUID, result: HoldResult, availableCents: Long, heldCents: Long) {
        jdbc.update(
            "UPDATE ledger_hold_decision SET result = ?, available_cents = ?, held_cents = ? WHERE order_id = ?",
            result.name, availableCents, heldCents, orderId.toString(),
        )
    }

    private fun read(orderId: UUID, lock: Boolean): HoldReservation? {
        val sql = "SELECT order_id, result, available_cents, held_cents FROM ledger_hold_decision WHERE order_id = ?" +
            if (lock) " FOR UPDATE" else ""
        return jdbc.query(
            sql,
            { rs, _ ->
                HoldReservation(
                    orderId = UUID.fromString(rs.getString(1)),
                    result = HoldResult.valueOf(rs.getString(2)),
                    availableCents = rs.getLong(3),
                    heldCents = rs.getLong(4),
                    first = false,
                )
            },
            orderId.toString(),
        ).firstOrNull()
    }
}
