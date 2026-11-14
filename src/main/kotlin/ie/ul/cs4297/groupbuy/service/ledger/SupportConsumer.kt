package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/** The only support.v1 listener: a second one would split the partitions (decision 0006). Only refunds move money. */
@Component
class SupportConsumer(
    private val inbox: EventInbox,
    private val refunds: Refunds,
) {
    companion object {
        const val CONSUMER = "support-v1"
    }

    @KafkaListener(id = CONSUMER, idIsGroup = false, topics = [Topics.SUPPORT_V1])
    fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
        val envelope = record.value() ?: return
        when (val e = envelope.payload) {
            is RefundApproved -> {
                if (e.amountCents < 1) throw InvalidEvent("RefundApproved ${envelope.eventId}: amountCents must be 1 or more")
                inbox.once(CONSUMER, envelope) { refunds.approved(envelope, e) }
            }
            else -> {} // complaints, blocks, deletions: Support's, no money moves
        }
    }
}
