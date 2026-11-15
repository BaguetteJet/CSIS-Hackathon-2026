package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalCompleted
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalDebited
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalFailed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalPaidOut
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRefused
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRefusedReason
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalReturned
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Handles cash-out requests and their outcomes:
 * - [LED-15]  reserve the requested money, or refuse if funds are insufficient.
 * - [LED-16] record the cash being paid out, or return the reserved money.
 *
 * PaymentsConsumer receives the events and calls these functions inside an inbox transaction. Request amounts are validated before reaching this code.
 * WithdrawalStore implements the actions, records money movements and queues outgoing events.
 */
interface Withdrawals {
    /**
     * Receives a cash-out request.
     * If enough money is available, reserve it and queue WithdrawalDebited.
     * Otherwise, record a refusal and queue WithdrawalRefused.
     */
    fun requested(e: WithdrawalRequested, causationId: UUID)

    /**
     * Receives confirmation that the desk handed over the cash.
     * Remove the reserved amount from held and queue WithdrawalPaidOut.
     */
    fun completed(e: WithdrawalCompleted, causationId: UUID)

    /**
     * Receives confirmation that the cash-out failed.
     * Return the reserved amount to available and queue WithdrawalReturned.
     */
    fun failed(e: WithdrawalFailed, causationId: UUID)
}

/**
 * Set aside first, then paid out or returned, never both (item 8).
 * Locks are taken in this order:
 * withdrawal record, student accounts, then the outgoing event counter.
 */
 
@Component
class WithdrawalStore(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
    private val outbox: Outbox,
    private val clock: Clock,
) : Withdrawals {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
    * requested() handles a student's cash-out request.
    * - First, claim(e) records the request if it is new.
    * - If it already exists, processing stops so money is not reserved twice.
    * - It locks the student's accounts and checks their available money.
    * - If there is not enough, it records REFUSED and queues WithdrawalRefused.
    * - Otherwise, it moves the requested amount from available to held.
    */
    override fun requested(e: WithdrawalRequested, causationId: UUID) {
        if (!claim(e)) {
            log.info("Cash-out {} was already decided: a repeat changes nothing", e.withdrawalId)
            return
        }
        val balance = accounts.lockStudent(e.studentId)
        if (balance.availableCents < e.amountCents) {
            setState(e.withdrawalId, "REFUSED")
            outbox.publish(
                WithdrawalRefused(e.withdrawalId, e.studentId, WithdrawalRefusedReason.INSUFFICIENT_FUNDS, balance.availableCents, balance.heldCents),
                causationId,
            )
            return
        }
        postings.post(MovementRef(MovementType.WITHDRAWAL, e.withdrawalId), listOf(
            PostingLine(AccountRef.studentAvailable(e.studentId), -e.amountCents, EntryDetails(LedgerEntryKind.WITHDRAWAL, "Cash-out at the desk")),
            PostingLine(AccountRef.studentHeld(e.studentId), e.amountCents),
        ))
        setState(e.withdrawalId, "RESERVED")
        outbox.publish(
            WithdrawalDebited(e.withdrawalId, e.studentId, e.amountCents, balance.availableCents - e.amountCents, balance.heldCents + e.amountCents),
            causationId,
        )
    }

    /**
     * completed() records a successful cash-out.
     * - finish() checks that the withdrawal is still RESERVED and changes it to PAID_OUT.
     * - If it is missing, refused or already finished, stop.
     * - Use the original reserved amount, even if the event says otherwise.
     * - Move that amount from the student's held account to Union cash.
     * - Queue WithdrawalPaidOut with the resulting balances.
     */
    override fun completed(e: WithdrawalCompleted, causationId: UUID) {
        val reserved = finish(e.withdrawalId, "PAID_OUT") ?: return
        logDifference("WithdrawalCompleted", e.withdrawalId, e.amountCents, reserved.amountCents)
        val balance = accounts.lockStudent(reserved.studentId)
        postings.post(MovementRef(MovementType.WITHDRAWAL_PAYOUT, e.withdrawalId), listOf(
            PostingLine(AccountRef.studentHeld(reserved.studentId), -reserved.amountCents),
            PostingLine(AccountRef.UNION_CASH, reserved.amountCents),
        ))
        outbox.publish(
            WithdrawalPaidOut(e.withdrawalId, reserved.studentId, reserved.amountCents, balance.availableCents, balance.heldCents - reserved.amountCents),
            causationId,
        )
    }

    /**
     * failed() records an unsuccessful cash-out.
     * - finish() checks that the withdrawal is still RESERVED and changes it to RETURNED.
     * - If it is missing, refused or already finished, stop.
     * - Return the original reserved amount from held to available.
     * - Queue WithdrawalReturned with the resulting balances.
     */
    override fun failed(e: WithdrawalFailed, causationId: UUID) {
        val reserved = finish(e.withdrawalId, "RETURNED") ?: return
        logDifference("WithdrawalFailed", e.withdrawalId, e.amountCents, reserved.amountCents)
        val balance = accounts.lockStudent(reserved.studentId)
        postings.post(MovementRef(MovementType.WITHDRAWAL_RETURN, e.withdrawalId), listOf(
            PostingLine(AccountRef.studentHeld(reserved.studentId), -reserved.amountCents),
            PostingLine(AccountRef.studentAvailable(reserved.studentId), reserved.amountCents,
                EntryDetails(LedgerEntryKind.WITHDRAWAL_RETURNED, "Cash-out cancelled: the money is back in your Account")),
        ))
        outbox.publish(
            WithdrawalReturned(e.withdrawalId, reserved.studentId, reserved.amountCents,
                balance.availableCents + reserved.amountCents, balance.heldCents - reserved.amountCents),
            causationId,
        )
    }

    /**
    claim(e) checks whether this cash-out request has already been recorded. It does that by trying to add it to the database:
    - If it is new, it records the request as PENDING and returns true.
    - If it already exists, it returns false, and processing stops.
    */
    private fun claim(e: WithdrawalRequested): Boolean = try {
        jdbc.update(
            "INSERT INTO withdrawal (withdrawal_id, student_id, amount_cents, state, decided_at) VALUES (?, ?, ?, 'PENDING', ?)",
            e.withdrawalId.toString(), e.studentId.toString(), e.amountCents, Timestamp.from(clock.instant()),
        )
        true
    } catch (duplicate: DuplicateKeyException) {
        false
    }

    /**
    * setState() updates the saved status of this cash-out.
    * - REFUSED means there was not enough available money.
    * - RESERVED means the requested money has been set aside.
    * This function does not move money.
    */
    private fun setState(withdrawalId: UUID, state: String) =
        jdbc.update("UPDATE withdrawal SET state = ? WHERE withdrawal_id = ?", state, withdrawalId.toString())

    /**
    * logDifference() checks whether the event amount matches the reserved amount.
    * - If they match, do nothing.
    * - If they differ, log a warning so the mismatch can be investigated.
    */
    private fun logDifference(type: String, withdrawalId: UUID, eventCents: Long, reservedCents: Long) {
        if (eventCents == reservedCents) return
        log.warn("Cash-out {}: {} says {} cents, {} were reserved; the reserved amount is used", withdrawalId, type, eventCents, reservedCents)
    }

    private data class Reserved(val studentId: UUID, val amountCents: Long)

    /**
    * finish() checks whether a cash-out can still be paid out or returned.
    * - Read and lock its withdrawal record so another transaction must wait.
    * - If the record is missing or its state is not RESERVED, return null.
    *   The calling function then stops without moving money.
    * - Otherwise, change its state to PAID_OUT or RETURNED, as supplied by the caller.
    * - Return the saved student identifier and original reserved amount.
    *
    * Only one ending can succeed because the next transaction sees the updated state.
    * The state change rolls back if the surrounding transaction fails.
    */
    private fun finish(withdrawalId: UUID, state: String): Reserved? {
        val row = jdbc.query(
            "SELECT student_id, amount_cents, state FROM withdrawal WHERE withdrawal_id = ? FOR UPDATE",
            { rs, _ -> Triple(UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3)) },
            withdrawalId.toString(),
        ).singleOrNull()
        if (row == null || row.third != "RESERVED") {
            log.warn("Cash-out {} is {}: {} changes nothing", withdrawalId, row?.third ?: "unknown", state)
            return null
        }
        jdbc.update("UPDATE withdrawal SET state = ? WHERE withdrawal_id = ?", state, withdrawalId.toString())
        return Reserved(row.first, row.second)
    }
}
