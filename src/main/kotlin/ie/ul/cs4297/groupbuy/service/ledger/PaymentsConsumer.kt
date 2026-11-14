package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.TopUpCredited
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalCompleted
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalFailed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.converter.MessageConversionException
import org.springframework.stereotype.Component

/** [LED-19] - Bad input: an event that breaks the contract (e.g. zero amount) is never retried, goes straight to ledger.dlt. */
class InvalidEvent(message: String) : MessageConversionException(message)

/** The only payments.v1 listener: a second one would split the partitions (decision 0006). Cash-outs and payouts: item 8. */
@Component
class PaymentsConsumer(
    private val inbox: EventInbox,
    private val topUps: TopUpPosting,
    private val outbox: Outbox,
    private val withdrawals: Withdrawals,
    private val payouts: Payouts,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CONSUMER = "payments-v1"
    }

    @KafkaListener(id = CONSUMER, idIsGroup = false, topics = [Topics.PAYMENTS_V1])
    fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
        val envelope = record.value() ?: return
        val cause = envelope.eventId
        when (val e = envelope.payload) {
            is TopUpRecorded -> topUp(envelope, e)
            is WithdrawalRequested -> {
                atLeastOneCent(envelope, e.amountCents)
                inbox.once(CONSUMER, envelope) { withdrawals.requested(e, cause) }
            }
            // [LED-16] - Pay out or return a cash-out: no amount check, the reserved amount is used, whatever this one says.
            is WithdrawalCompleted -> inbox.once(CONSUMER, envelope) { withdrawals.completed(e, cause) }
            is WithdrawalFailed -> inbox.once(CONSUMER, envelope) { withdrawals.failed(e, cause) }
            is PayoutConfirmed -> {
                atLeastOneCent(envelope, e.amountCents)
                inbox.once(CONSUMER, envelope) { payouts.confirmed(e, cause) }
            }
            else -> {}
        }
    }

    /** Checked before the inbox, so a bad event is never recorded as processed. */
    private fun atLeastOneCent(envelope: Envelope<ContractEvent>, amountCents: Long) {
        if (amountCents < 1) throw InvalidEvent("${envelope.type} ${envelope.eventId}: amountCents must be 1 or more")
    }

    /** [LED-02] - Credit top-ups: credit any sequence (reset is 0), amount or student. Limits and blocks: Payments' job (D-A4). */
    private fun topUp(envelope: Envelope<ContractEvent>, e: TopUpRecorded) {
        atLeastOneCent(envelope, e.amountCents)
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
