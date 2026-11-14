package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.DealCancelled
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.DealFailed
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.contracts.events.OrderAbandoned
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import ie.ul.cs4297.groupbuy.contracts.events.RestaurantRegistered
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * The one listener on orders.v1 and deals.v1: it only routes, inside the inbox (decision 0006).
 * Closures refuse late holds (LED-04); releases and settlement move the money (LED-05, 08, 09).
 */
@Component
class ClosuresConsumer(
    private val inbox: EventInbox,
    private val accounts: LedgerAccounts,
    private val releases: Releases,
    private val settlement: Settlement,
) {

    companion object {
        const val CONSUMER = "closures"
    }

    @KafkaListener(id = CONSUMER, idIsGroup = false, topics = [Topics.ORDERS_V1, Topics.DEALS_V1])
    fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
        val envelope = record.value() ?: return
        val cause = envelope.eventId
        when (val e = envelope.payload) {
            is OrderWithdrawn -> inbox.once(CONSUMER, envelope) { releases.orderClosed(e.orderId, e.dealId, HoldReleasedReason.WITHDRAWN, cause) }
            is OrderAbandoned -> inbox.once(CONSUMER, envelope) { releases.orderClosed(e.orderId, e.dealId, HoldReleasedReason.ABANDONED, cause) }
            is DealConfirmed -> inbox.once(CONSUMER, envelope) { settlement.settle(e, cause) }
            is DealFailed -> inbox.once(CONSUMER, envelope) { releases.dealClosed(e.dealId, HoldReleasedReason.DEAL_FAILED, "FAILED", cause) }
            is DealCancelled -> inbox.once(CONSUMER, envelope) { releases.dealClosed(e.dealId, HoldReleasedReason.DEAL_CANCELLED, "CANCELLED", cause) }
            is RestaurantRegistered -> inbox.once(CONSUMER, envelope) { accounts.openRestaurant(e.restaurantId) } // LED-01
            else -> {}
        }
    }
}
