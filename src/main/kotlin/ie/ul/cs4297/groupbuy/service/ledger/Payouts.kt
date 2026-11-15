package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.PayoutDebited
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [LED-17] - Restaurant payouts
 * Restaurant payouts. PaymentsConsumer calls this inside the inbox transaction, amount already
 * checked ([LED-19] - Bad input); the implementation posts and publishes: [PayoutStore].
 */
interface Payouts {
    /** Payable to Union cash once per payoutId, even below 0, then PayoutDebited. */
    fun confirmed(e: PayoutConfirmed, causationId: UUID)
}

/** Payments decides the amount, so the payable may go below zero; the movement key (payoutId) makes it once. */
@Component
class PayoutStore(
    private val postings: LedgerPostingService,
    private val outbox: Outbox,
    private val jdbc: JdbcTemplate,
) : Payouts {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun confirmed(e: PayoutConfirmed, causationId: UUID) {
        val posted = postings.post(MovementRef(MovementType.PAYOUT, e.payoutId), listOf(
            PostingLine(AccountRef.restaurantPayable(e.restaurantId), -e.amountCents),
            PostingLine(AccountRef.UNION_CASH, e.amountCents),
        ))
        if (!posted) {
            // Warn on another restaurant: Payments thinks it paid them, so reconciliation must see it (EDGE-CASES Q4).
            val taken = takenFrom(e.payoutId)
            if (taken != e.restaurantId.toString()) {
                log.warn("Payout {} was taken from restaurant {}; this repeat names {} and changes nothing", e.payoutId, taken, e.restaurantId)
            } else {
                log.info("Payout {} was already taken: a repeat changes nothing", e.payoutId)
            }
            return
        }
        outbox.publish(PayoutDebited(e.payoutId, e.restaurantId, e.amountCents), causationId)
    }

    private fun takenFrom(payoutId: UUID): String? = jdbc.query(
        "SELECT a.owner_id FROM ledger_movement m JOIN ledger_posting p ON p.movement_id = m.id JOIN ledger_account a ON a.id = p.account_id " +
            "WHERE m.reference_type = 'PAYOUT' AND m.reference_id = ? AND a.owner_type = 'RESTAURANT'",
        { rs, _ -> rs.getString(1) }, payoutId.toString(),
    ).firstOrNull()
}
