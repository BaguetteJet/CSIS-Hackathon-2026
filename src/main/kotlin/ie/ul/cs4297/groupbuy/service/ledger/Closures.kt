package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.DealCancelled
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.DealFailed
import ie.ul.cs4297.groupbuy.contracts.events.OrderAbandoned
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * Record closed orders and deals so the hold can refuse them (LED-04).
 * Only listener on orders.v1 and deals.v1: add releases and settlement here.
 * The hold reads these rows FOR SHARE: a closure and a hold for the same order never overlap.
 */
@Component
class ClosuresConsumer(private val inbox: EventInbox, private val jdbc: JdbcTemplate, private val clock: Clock) {

    companion object {
        const val CONSUMER = "closures"
    }

    @KafkaListener(id = CONSUMER, idIsGroup = false, topics = [Topics.ORDERS_V1, Topics.DEALS_V1])
    fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
        val envelope = record.value() ?: return
        when (val e = envelope.payload) {
            is OrderWithdrawn -> inbox.once(CONSUMER, envelope) { closeOrder(e.orderId, e.dealId, "WITHDRAWN") }
            is OrderAbandoned -> inbox.once(CONSUMER, envelope) { closeOrder(e.orderId, e.dealId, "ABANDONED") }
            is DealConfirmed -> inbox.once(CONSUMER, envelope) { closeDeal(e.dealId, "CONFIRMED") }
            is DealFailed -> inbox.once(CONSUMER, envelope) { closeDeal(e.dealId, "FAILED") }
            is DealCancelled -> inbox.once(CONSUMER, envelope) { closeDeal(e.dealId, "CANCELLED") }
            else -> {}
        }
    }

    // First closing event wins.
    private fun closeOrder(orderId: UUID, dealId: UUID, reason: String) = jdbc.update(
        "INSERT INTO order_closure (order_id, deal_id, reason, closed_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE order_id = order_id",
        orderId.toString(), dealId.toString(), reason, Timestamp.from(clock.instant()),
    )

    private fun closeDeal(dealId: UUID, reason: String) = jdbc.update(
        "INSERT INTO deal_closure (deal_id, reason, closed_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE deal_id = deal_id",
        dealId.toString(), reason, Timestamp.from(clock.instant()),
    )
}
