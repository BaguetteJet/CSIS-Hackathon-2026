package ie.ul.cs4297.groupbuy.service.ledger

import java.util.UUID

/**
 * [LED-14] - Refund limits and early refunds: settlement and releases wake refunds parked for an order or deal (decision 0009).
 * Called in the same transaction as the charge or release; [Refunds] implements it.
 */
interface RefundWakeups {
    fun ordersCharged(orderIds: Collection<UUID>)
    fun holdReleased(orderId: UUID)
    fun dealDecided(dealId: UUID)
}
