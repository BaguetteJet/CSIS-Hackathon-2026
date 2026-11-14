package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.Balance
import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleased
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** One hold_decision row, read under its lock. */
data class HoldRow(
    val orderId: UUID,
    val dealId: UUID,
    val studentId: UUID,
    val amountCents: Long,
    val result: String,
    val state: String?,
) {
    val isOpen get() = state == "OPEN"
}

/**
 * Gives set-aside money back when an order or deal won't go ahead (LED-08, LED-09).
 *
 * Lock order (decision 0004): hold rows, then the closure row, then students.
 * Order closed: the hold row first, so a hold racing the closure waits for it and is refused, or finishes first and is released.
 * Deal closed: the deal's rows through the plain deal_id index, so a hold in flight finishes first and a new one waits, then gets DEAL_CLOSED.
 * A hold ends once: OPEN to RELEASED by a conditional update (decision 0008).
 */
@Component
class Releases(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
    private val outbox: Outbox,
    private val clock: Clock,
    private val wakeups: RefundWakeups,
) {

    /** OrderWithdrawn or OrderAbandoned: close the order and release its hold if it's still open. */
    fun orderClosed(orderId: UUID, dealId: UUID, reason: HoldReleasedReason, causationId: UUID) {
        val open = holdsForOrders(listOf(orderId)).filter { it.isOpen }
        closeOrder(orderId, dealId, reason.name)
        release(open, reason, causationId, accounts.lockStudents(open.map { it.studentId }))
    }

    /** DealFailed or DealCancelled: close the deal and release every open hold for it. Never on a timer (D-E5). */
    fun dealClosed(dealId: UUID, reason: HoldReleasedReason, closure: String, causationId: UUID) {
        val open = holdsForDeal(dealId).filter { it.isOpen }
        closeDeal(dealId, closure)
        release(open, reason, causationId, accounts.lockStudents(open.map { it.studentId }))
        wakeups.dealDecided(dealId)
    }

    /**
     * Move each open hold back to available and publish HoldReleased.
     * [balances]: the students' locked balances, kept current as we go so each event chains (LED-20).
     */
    fun release(holds: List<HoldRow>, reason: HoldReleasedReason, causationId: UUID, balances: MutableMap<UUID, Balance>) {
        for (h in holds) {
            // Lost to a charge or an earlier release: nothing to do.
            if (jdbc.update("UPDATE hold_decision SET state = 'RELEASED' WHERE order_id = ? AND state = 'OPEN'", h.orderId.toString()) != 1) continue
            check(postings.post(MovementRef(MovementType.RELEASE, h.orderId), listOf(
                PostingLine(AccountRef.studentHeld(h.studentId), -h.amountCents),
                PostingLine(AccountRef.studentAvailable(h.studentId), h.amountCents,
                    EntryDetails(LedgerEntryKind.RELEASE, wording(reason), h.dealId, h.orderId)),
            ))) { "order ${h.orderId} was released twice" }
            val before = balances.getValue(h.studentId)
            val after = Balance(h.studentId, before.availableCents + h.amountCents, before.heldCents - h.amountCents)
            balances[h.studentId] = after
            outbox.publish(HoldReleased(h.orderId, h.dealId, h.studentId, h.amountCents, reason, after.availableCents, after.heldCents), causationId)
            wakeups.holdReleased(h.orderId)
        }
    }

    /** A deal's holds, locked. FORCE INDEX: the deal_id-only index, so a hold changing state doesn't touch what we locked. */
    fun holdsForDeal(dealId: UUID): List<HoldRow> = jdbc.query(
        "$SELECT_HOLD FORCE INDEX (idx_hold_decision_deal) WHERE deal_id = ? ORDER BY order_id FOR UPDATE",
        ::holdRow, dealId.toString(),
    )

    /** Some orders' holds, locked in order_id order. Missing orders lock their gap, so a new hold for them waits. */
    fun holdsForOrders(orderIds: Collection<UUID>): List<HoldRow> {
        if (orderIds.isEmpty()) return emptyList()
        val ids = orderIds.map { it.toString() }.sorted()
        return jdbc.query("$SELECT_HOLD WHERE order_id IN (${ids.joinToString(",") { "?" }}) ORDER BY order_id FOR UPDATE", ::holdRow, *ids.toTypedArray())
    }

    // First closing event wins.
    fun closeOrder(orderId: UUID, dealId: UUID, reason: String) = jdbc.update(
        "INSERT INTO order_closure (order_id, deal_id, reason, closed_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE order_id = order_id",
        orderId.toString(), dealId.toString(), reason, Timestamp.from(clock.instant()),
    )

    fun closeDeal(dealId: UUID, reason: String) = jdbc.update(
        "INSERT INTO deal_closure (deal_id, reason, closed_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE deal_id = deal_id",
        dealId.toString(), reason, Timestamp.from(clock.instant()),
    )

    private fun holdRow(rs: java.sql.ResultSet, row: Int) = HoldRow(
        UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
        rs.getLong(4), rs.getString(5), rs.getString(6),
    )

    companion object {
        private const val SELECT_HOLD = "SELECT order_id, deal_id, student_id, amount_cents, result, state FROM hold_decision"

        /** Statement wording: student words, never "hold" (DEC Product). */
        fun wording(reason: HoldReleasedReason) = when (reason) {
            HoldReleasedReason.WITHDRAWN -> "Set-aside money back: you left the deal"
            HoldReleasedReason.ABANDONED -> "Set-aside money back: your order wasn't completed"
            HoldReleasedReason.DEAL_FAILED -> "Set-aside money back: the deal didn't go ahead"
            HoldReleasedReason.DEAL_CANCELLED -> "Set-aside money back: the deal was cancelled"
            HoldReleasedReason.NOT_ON_LIST -> "Set-aside money back: your order wasn't in the final deal"
        }
    }
}
