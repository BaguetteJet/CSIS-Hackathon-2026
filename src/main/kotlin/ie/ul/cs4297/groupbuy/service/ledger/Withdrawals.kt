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
 * Cash-outs (LED-15, 16). PaymentsConsumer calls these inside the inbox transaction, amounts already
 * checked (LED-19); the implementation posts and publishes: [WithdrawalStore].
 */
interface Withdrawals {
    /** Reserve: available to held, then WithdrawalDebited; not enough, WithdrawalRefused. */
    fun requested(e: WithdrawalRequested, causationId: UUID)

    /** Held to Union cash at the reserved amount, then WithdrawalPaidOut. */
    fun completed(e: WithdrawalCompleted, causationId: UUID)

    /** Held back to available at the reserved amount, then WithdrawalReturned. */
    fun failed(e: WithdrawalFailed, causationId: UUID)
}

/**
 * Set aside first, then paid out or returned, never both (item 8).
 * Lock order (decision 0004): the withdrawal row, then the student, then publish.
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

    // The first request for a withdrawalId decides; a repeat does nothing.
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

    override fun completed(e: WithdrawalCompleted, causationId: UUID) {
        val reserved = finish(e.withdrawalId, "PAID_OUT") ?: return
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

    override fun failed(e: WithdrawalFailed, causationId: UUID) {
        val reserved = finish(e.withdrawalId, "RETURNED") ?: return
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

    private fun claim(e: WithdrawalRequested): Boolean = try {
        jdbc.update(
            "INSERT INTO withdrawal (withdrawal_id, student_id, amount_cents, state, decided_at) VALUES (?, ?, ?, 'PENDING', ?)",
            e.withdrawalId.toString(), e.studentId.toString(), e.amountCents, Timestamp.from(clock.instant()),
        )
        true
    } catch (duplicate: DuplicateKeyException) {
        false
    }

    private fun setState(withdrawalId: UUID, state: String) =
        jdbc.update("UPDATE withdrawal SET state = ? WHERE withdrawal_id = ?", state, withdrawalId.toString())

    private data class Reserved(val studentId: UUID, val amountCents: Long)

    /** Moves a RESERVED cash-out to [state] and returns it; null if it was never reserved or already ended (the first ending wins). */
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
