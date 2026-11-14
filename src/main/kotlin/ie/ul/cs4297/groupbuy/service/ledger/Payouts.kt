package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.PayoutDebited
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Restaurant payouts (LED-17). PaymentsConsumer calls this inside the inbox transaction, amount already
 * checked (LED-19); the implementation posts and publishes: [PayoutStore].
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
) : Payouts {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun confirmed(e: PayoutConfirmed, causationId: UUID) {
        val posted = postings.post(MovementRef(MovementType.PAYOUT, e.payoutId), listOf(
            PostingLine(AccountRef.restaurantPayable(e.restaurantId), -e.amountCents),
            PostingLine(AccountRef.UNION_CASH, e.amountCents),
        ))
        if (!posted) {
            log.info("Payout {} was already taken: a repeat changes nothing", e.payoutId)
            return
        }
        outbox.publish(PayoutDebited(e.payoutId, e.restaurantId, e.amountCents), causationId)
    }
}
