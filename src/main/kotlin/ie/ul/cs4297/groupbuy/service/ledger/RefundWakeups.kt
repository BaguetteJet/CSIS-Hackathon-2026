package ie.ul.cs4297.groupbuy.service.ledger

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Settlement and releases wake refunds parked for an order or deal (LED-14, decision 0009).
 * Called in the same transaction as the charge or release. Item 6 replaces [NoParkedRefunds].
 */
interface RefundWakeups {
    fun orderCharged(orderId: UUID)
    fun holdReleased(orderId: UUID)
    fun dealDecided(dealId: UUID)
}

/** Until refunds exist nothing is ever parked, so there's nothing to wake. */
@Component
class NoParkedRefunds : RefundWakeups {
    override fun orderCharged(orderId: UUID) {}
    override fun holdReleased(orderId: UUID) {}
    override fun dealDecided(dealId: UUID) {}
}
