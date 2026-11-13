package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.TopUpCredited
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.converter.MessageConversionException
import org.springframework.stereotype.Component

/**
 * An event that breaks its contract, such as a zero amount. Spring Kafka's error handler never
 * retries a MessageConversionException, so the starter sends it straight to ledger.dlt (LED-19).
 */
class InvalidEvent(message: String) : MessageConversionException(message)

/**
 * The Ledger's payments.v1 consumer. Keep it the only listener on this topic: a second one in the
 * shared group would split the topic's partitions with this one, and each would miss the other's
 * events. Cash-outs and payouts belong here as more branches of the `when`.
 */
@Component
class PaymentsConsumer(
    private val inbox: EventInbox,
    private val topUps: TopUpPosting,
    private val outbox: Outbox,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CONSUMER = "payments-v1"
    }

    @KafkaListener(id = CONSUMER, idIsGroup = false, topics = [Topics.PAYMENTS_V1])
    fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
        val envelope = record.value() ?: return 
        when (val e = envelope.payload) {
            is TopUpRecorded -> topUp(envelope, e)
            else -> {} // other payments.v1 events: TODO
        }
    }

    /**
     * LED-02. Credited whatever its sequence, its amount or its student.
     */
    private fun topUp(envelope: Envelope<ContractEvent>, e: TopUpRecorded) {
        if (e.amountCents < 1) throw InvalidEvent("TopUpRecorded ${envelope.eventId}: amountCents must be 1 or more")
        inbox.once(CONSUMER, envelope) {
            val after = topUps.creditTopUp(e.topUpId, e.studentId, e.amountCents)
            if (after.newlyApplied) {
                outbox.publish(
                    TopUpCredited(e.topUpId, e.studentId, e.amountCents, after.availableCents, after.heldCents),
                    causationId = envelope.eventId,
                )
            } else {
                log.info("Top-up {} was already credited: event {} changes nothing", e.topUpId, envelope.eventId)
            }
        }
    }
}
