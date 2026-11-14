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

/** An event that breaks the contract (e.g. zero amount): never retried, goes straight to ledger.dlt (LED-19). */
class InvalidEvent(message: String) : MessageConversionException(message)

/** The only payments.v1 listener: a second one would split the partitions. Add cash-outs and payouts here. */
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
            else -> {} // cash-outs and payouts (LED-15 to 17)
        }
    }

    /** LED-02: credit any sequence (reset is 0), amount or student. Limits and blocks: Payments' job (D-A4). */
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
