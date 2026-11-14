package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Restaurant payouts (LED-17). PaymentsConsumer calls this inside the inbox transaction, amount already
 * checked (LED-19); the implementation posts and publishes. Item 8 replaces [NoPayoutsYet].
 */
interface Payouts {
    /** Payable to Union cash once per payoutId, even below 0, then PayoutDebited. */
    fun confirmed(e: PayoutConfirmed, causationId: UUID)
}

/** Until payouts exist: nothing moves. Logged so it shows in integration. */
@Component
class NoPayoutsYet : Payouts {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun confirmed(e: PayoutConfirmed, causationId: UUID) = log.warn("Payouts not built yet: PayoutConfirmed {} ignored", e.payoutId)
}
