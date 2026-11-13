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
 * Remembers which orders and deals are closed, so the hold call can refuse them (LED-04).
 * Owner: Hersh (the hold side). Releasing holds that already exist (LED-08, LED-09) belongs to
 * the releases work after slice 1; it can hook in here, after the closure row is written.
 *
 * Writing the row and the hold call's check meet on the same key: the hold reads it FOR SHARE,
 * so a closure and a hold for the same order are always one after the other. Either the closure
 * lands first and the hold is refused, or the hold lands first and the release finds it.
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
            else -> {} // every other orders.v1 and deals.v1 event: nothing to do here
        }
    }

    // The first closing event wins; a later one (say DealCancelled after DealConfirmed) changes nothing.
    private fun closeOrder(orderId: UUID, dealId: UUID, reason: String) = jdbc.update(
        "INSERT INTO order_closure (order_id, deal_id, reason, closed_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE order_id = order_id",
        orderId.toString(), dealId.toString(), reason, Timestamp.from(clock.instant()),
    )

    private fun closeDeal(dealId: UUID, reason: String) = jdbc.update(
        "INSERT INTO deal_closure (deal_id, reason, closed_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE deal_id = deal_id",
        dealId.toString(), reason, Timestamp.from(clock.instant()),
    )
}
