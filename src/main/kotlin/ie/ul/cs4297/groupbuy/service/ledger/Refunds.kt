package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.Money
import ie.ul.cs4297.groupbuy.contracts.api.Balance
import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundPosted
import ie.ul.cs4297.groupbuy.contracts.events.RefundRefused
import ie.ul.cs4297.groupbuy.contracts.events.RefundRefusedReason
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import ie.ul.cs4297.groupbuy.service.platform.ParkedEvents
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** What an order paid and has had back so far: the row a refund locks. */
private class ChargeRow(val studentId: UUID, val dealId: UUID, val restaurantId: UUID, val priceCents: Long,
                        val commissionCents: Long, val refundedCents: Long, val commissionReversedCents: Long)

/**
 * Refunds after a charge, never more than the order was charged in total (LED-13; decision 0009).
 * 1. Lock the order's charge row, so two refunds of one order go one at a time.
 * 2. A refundId already decided stops here. No charge yet: the refund came early, so park it (LED-14).
 * 3. Lock the student, then post REFUND or publish RefundRefused EXCEEDS_CHARGE.
 */
@Component
class Refunds(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
    private val outbox: Outbox,
    private val parking: ParkedEvents,
    private val clock: Clock,
) {

    fun approved(envelope: Envelope<ContractEvent>, e: RefundApproved) {
        val charge = lockCharge(e.orderId)
        if (decided(e.refundId)) return
        if (charge == null) {
            parking.park(SupportConsumer.CONSUMER, "order:${e.orderId}", envelope)
            return
        }
        val before = accounts.lockStudent(charge.studentId)
        if (e.amountCents > charge.priceCents - charge.refundedCents) {
            record(e, 0, "EXCEEDS_CHARGE")
            outbox.publish(RefundRefused(e.refundId, e.orderId, charge.dealId, charge.studentId, RefundRefusedReason.EXCEEDS_CHARGE,
                before.availableCents, before.heldCents), envelope.eventId)
            return
        }

        // Rounded on the running total, so part refunds together reverse exactly the commission charged.
        val split = Money.refundReversal(e.amountCents, charge.priceCents, charge.commissionCents, charge.refundedCents)
        val lines = buildList {
            add(PostingLine(AccountRef.studentAvailable(charge.studentId), e.amountCents,
                EntryDetails(LedgerEntryKind.REFUND, "Refund: ${euros(e.amountCents)} back in your Account", charge.dealId, e.orderId)))
            if (split.commissionReversedCents != 0L) add(PostingLine(AccountRef.UNION_COMMISSION, -split.commissionReversedCents))
            if (split.restaurantReversedCents != 0L) add(PostingLine(AccountRef.restaurantPayable(charge.restaurantId), -split.restaurantReversedCents))
        }
        check(postings.post(MovementRef(MovementType.REFUND, e.refundId), lines)) { "refund ${e.refundId} was posted twice" }
        jdbc.update(
            "UPDATE order_charge SET refunded_cents = refunded_cents + ?, commission_reversed_cents = commission_reversed_cents + ? WHERE order_id = ?",
            e.amountCents, split.commissionReversedCents, e.orderId.toString(),
        )
        record(e, split.commissionReversedCents, "POSTED")
        val after = Balance(charge.studentId, before.availableCents + e.amountCents, before.heldCents)
        outbox.publish(RefundPosted(e.refundId, e.orderId, charge.dealId, charge.studentId, charge.restaurantId, e.amountCents,
            split.commissionReversedCents, after.availableCents, after.heldCents), envelope.eventId)
    }

    private fun lockCharge(orderId: UUID): ChargeRow? = jdbc.query(
        "SELECT student_id, deal_id, restaurant_id, price_cents, commission_cents, refunded_cents, commission_reversed_cents " +
            "FROM order_charge WHERE order_id = ? FOR UPDATE",
        { rs, _ ->
            ChargeRow(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), UUID.fromString(rs.getString(3)),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7))
        },
        orderId.toString(),
    ).singleOrNull()

    private fun decided(refundId: UUID): Boolean =
        jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE refund_id = ?", Int::class.java, refundId.toString())!! > 0

    private fun record(e: RefundApproved, commissionReversedCents: Long, outcome: String) {
        jdbc.update(
            "INSERT INTO refund (refund_id, order_id, deal_id, student_id, amount_cents, commission_reversed_cents, reason, outcome, decided_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            e.refundId.toString(), e.orderId.toString(), e.dealId.toString(), e.studentId.toString(), e.amountCents,
            commissionReversedCents, e.reason.name, outcome, Timestamp.from(clock.instant()),
        )
    }

    private fun euros(cents: Long) = "€%d.%02d".format(cents / 100, cents % 100)
}
