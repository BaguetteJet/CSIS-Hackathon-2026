package ie.ul.cs4297.groupbuy.service

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Money
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.DealSettled
import ie.ul.cs4297.groupbuy.contracts.events.EventCatalogue
import ie.ul.cs4297.groupbuy.contracts.events.HoldPlaced
import ie.ul.cs4297.groupbuy.contracts.events.HoldRejected
import ie.ul.cs4297.groupbuy.contracts.events.HoldRejectedReason
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleased
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.contracts.events.OrderCharged
import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.PayoutDebited
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundApprovedReason
import ie.ul.cs4297.groupbuy.contracts.events.RefundPosted
import ie.ul.cs4297.groupbuy.contracts.events.RefundRefused
import ie.ul.cs4297.groupbuy.contracts.events.RefundRefusedReason
import ie.ul.cs4297.groupbuy.contracts.events.SettlementIssue
import ie.ul.cs4297.groupbuy.contracts.events.TopUpCredited
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalCompleted
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalDebited
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalFailed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalPaidOut
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRefused
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRefusedReason
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalReturned
import ie.ul.cs4297.groupbuy.contracts.testkit.ContractSamples
import ie.ul.cs4297.groupbuy.contracts.testkit.assertMatchesContract
import ie.ul.cs4297.groupbuy.service.ledger.InvalidEvent
import ie.ul.cs4297.groupbuy.service.ledger.PaymentsConsumer
import ie.ul.cs4297.groupbuy.service.ledger.Payouts
import ie.ul.cs4297.groupbuy.service.ledger.Refunds
import ie.ul.cs4297.groupbuy.service.ledger.Releases
import ie.ul.cs4297.groupbuy.service.ledger.SupportConsumer
import ie.ul.cs4297.groupbuy.service.ledger.TopUpPosting
import ie.ul.cs4297.groupbuy.service.ledger.TopUpResult
import ie.ul.cs4297.groupbuy.service.ledger.Withdrawals
import ie.ul.cs4297.groupbuy.service.platform.EnvelopeDeserializer
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import io.mockk.every
import io.mockk.mockk
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.kafka.listener.ExceptionClassifier
import java.time.Instant
import java.util.UUID

/** Contract tests (CI, every push): published events match, consumed ones parse, replays publish nothing. */
@Tag("contract")
class ContractTest {

    private val now = Instant.parse("2026-11-14T12:00:00Z")
    private val student = UUID.randomUUID()
    private val credited = mutableMapOf<UUID, Long>()
    private val published = mutableListOf<Envelope<ContractEvent>>()
    private val seen = mutableSetOf<UUID>()
    private val routed = mutableListOf<ContractEvent>() // what reached the cash-out and payout stubs
    private val consumer = PaymentsConsumer(
        inbox = mockk<EventInbox> {
            every { once(any(), any(), any()) } answers {
                seen.add(secondArg<Envelope<*>>().eventId).also { first -> if (first) thirdArg<() -> Unit>().invoke() }
            }
        },
        topUps = object : TopUpPosting {
            override fun creditTopUp(topUpId: UUID, studentId: UUID, amountCents: Long): TopUpResult {
                val new = credited.putIfAbsent(topUpId, amountCents) == null
                return TopUpResult(new, availableCents = credited.values.sum(), heldCents = 0)
            }
        },
        outbox = mockk<Outbox> {
            every { publish(any<ContractEvent>(), any()) } answers {
                Envelope.of(firstArg<ContractEvent>(), published.size + 1L, now, secondArg<UUID?>()).also { published += it }
            }
        },
        withdrawals = object : Withdrawals {
            override fun requested(e: WithdrawalRequested, causationId: UUID) { routed += e }
            override fun completed(e: WithdrawalCompleted, causationId: UUID) { routed += e }
            override fun failed(e: WithdrawalFailed, causationId: UUID) { routed += e }
        },
        payouts = object : Payouts {
            override fun confirmed(e: PayoutConfirmed, causationId: UUID) { routed += e }
        },
    )

    private fun topUp(amountCents: Long, sequence: Long = 1) =
        Envelope.of(TopUpRecorded(UUID.randomUUID(), student, amountCents, UUID.randomUUID()), sequence, now)

    private fun send(envelope: Envelope<ContractEvent>) =
        consumer.on(ConsumerRecord(Topics.PAYMENTS_V1, 0, 0L, envelope.key, envelope))

    @Test
    fun `TopUpCredited as the top-up consumer publishes it matches the contract`() {
        val recorded = topUp(5000, sequence = 0)
        send(recorded)

        val envelope = published.single()
        assertMatchesContract(envelope)
        assertEquals(TopUpCredited(recorded.payload.topUpId, student, 5000, availableCents = 5000, heldCents = 0), envelope.payload)
        assertEquals(recorded.eventId, envelope.causationId)
    }

    @Test
    fun `HoldPlaced and HoldRejected match the contract`() {
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        listOf(
            HoldPlaced(order, deal, student, amountCents = 600, availableCents = 1400, heldCents = 600),
            HoldPlaced(order, deal, student, amountCents = 2000, availableCents = 0, heldCents = 2000), // exactly the balance
            HoldRejected(order, deal, student, HoldRejectedReason.INSUFFICIENT_FUNDS, availableCents = 1999, heldCents = 0), // a cent short
            HoldRejected(order, deal, student, HoldRejectedReason.INSUFFICIENT_FUNDS, availableCents = 0, heldCents = 0), // no account yet
            HoldRejected(order, deal, student, HoldRejectedReason.ORDER_CLOSED, availableCents = 1000, heldCents = 0), // withdrawn or abandoned first
            HoldRejected(order, deal, student, HoldRejectedReason.DEAL_CLOSED, availableCents = 1000, heldCents = 600), // confirmed, failed or cancelled first
        ).forEach { assertMatchesContract(it) }
    }

    @Test
    fun `the service can read every event type it consumes`() {
        // Read by PaymentsConsumer, ClosuresConsumer and SupportConsumer.
        for (type in listOf("UserRegistered", "UserUpdated", "RoleChanged", "UserDeleted", "TopUpRecorded",
            "WithdrawalRequested", "WithdrawalCompleted", "WithdrawalFailed", "PayoutConfirmed",
            "OrderWithdrawn", "OrderAbandoned", "DealConfirmed", "DealFailed", "DealCancelled", "RestaurantRegistered",
            "RefundApproved")) {
            val envelope = EnvelopeDeserializer().deserialize(EventCatalogue.byType(type)!!.topic, ContractSamples.json(type).toByteArray())
            assertEquals(type, envelope!!.type)
        }
    }

    @Test
    fun `a replayed top-up leaves one credit and one TopUpCredited`() {
        val first = topUp(2000)
        send(first)
        send(first)
        send(Envelope.of(first.payload.copy(amountCents = 5000), 2, now))

        assertEquals(mapOf(first.payload.topUpId to 2000L), credited)
        assertEquals(2000L, (published.single().payload as TopUpCredited).amountCents)
    }

    @Test
    fun `a zero-amount top-up goes straight to the dead-letter topic`() {
        val e = assertThrows<InvalidEvent> { send(topUp(0)) }
        assertTrue(ExceptionClassifier.defaultFatalExceptionsList().any { it.isInstance(e) })
        assertTrue(credited.isEmpty() && published.isEmpty())
    }

    @Test
    fun `cash-outs and payouts reach item 8 once each, and replays reach it not at all`() {
        val withdrawal = UUID.randomUUID()
        val events = listOf(
            WithdrawalRequested(withdrawal, student, 1000, UUID.randomUUID()),
            WithdrawalCompleted(withdrawal, student, 0, UUID.randomUUID()), // 0 is allowed: the reserved amount is used
            WithdrawalFailed(withdrawal, student, 1000, "Cancelled at the desk"),
            PayoutConfirmed(UUID.randomUUID(), UUID.randomUUID(), 12960),
        ).map { Envelope.of(it, 1, now) }
        events.forEach { send(it); send(it) }

        assertEquals(events.map { it.payload }, routed)
        assertTrue(published.isEmpty(), "the consumer publishes nothing for them itself")
    }

    @Test
    fun `a zero-amount cash-out or payout goes straight to the dead-letter topic`() {
        listOf(
            WithdrawalRequested(UUID.randomUUID(), student, 0, UUID.randomUUID()),
            PayoutConfirmed(UUID.randomUUID(), UUID.randomUUID(), 0),
            PayoutConfirmed(UUID.randomUUID(), UUID.randomUUID(), -500),
        ).forEach { event ->
            val e = assertThrows<InvalidEvent> { send(Envelope.of(event, 1, now)) }
            assertTrue(ExceptionClassifier.defaultFatalExceptionsList().any { it.isInstance(e) }, "not retried")
        }
        assertTrue(routed.isEmpty() && seen.isEmpty(), "nothing reached item 8 or the inbox")
    }

    @Test
    fun `cash-out events match the contract`() {
        val withdrawal = UUID.randomUUID()
        // 2400 available, 1000 out: reserved, then handed over or returned (LED-15, 16).
        assertMatchesContract(WithdrawalDebited(withdrawal, student, 1000, availableCents = 1400, heldCents = 1000))
        assertMatchesContract(WithdrawalDebited(withdrawal, student, 2400, availableCents = 0, heldCents = 2400)) // exactly the balance
        assertMatchesContract(WithdrawalPaidOut(withdrawal, student, 1000, availableCents = 1400, heldCents = 0))
        assertMatchesContract(WithdrawalReturned(withdrawal, student, 1000, availableCents = 2400, heldCents = 0))
        assertMatchesContract(WithdrawalRefused(withdrawal, student, WithdrawalRefusedReason.INSUFFICIENT_FUNDS, availableCents = 999, heldCents = 0))
        assertMatchesContract(WithdrawalRefused(withdrawal, student, WithdrawalRefusedReason.INSUFFICIENT_FUNDS, availableCents = 0, heldCents = 0)) // no account yet
    }

    @Test
    fun `payout events match the contract`() {
        val restaurant = UUID.randomUUID()
        assertMatchesContract(PayoutDebited(UUID.randomUUID(), restaurant, 12960))
        assertMatchesContract(PayoutDebited(UUID.randomUUID(), restaurant, 1)) // the payable may go below 0: nothing on the event shows it
    }

    @Test
    fun `refund events match the contract`() {
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        val restaurant = UUID.randomUUID()
        // 5000 topped up, a 450 order with 45 commission charged, refunded 5 then 445: reverses 1 then 44 (LED-13).
        val first = Money.refundReversal(5, 450, 45)
        val second = Money.refundReversal(445, 450, 45, refundedBeforeCents = 5)
        assertEquals(1L to 44L, first.commissionReversedCents to second.commissionReversedCents)
        assertMatchesContract(RefundPosted(UUID.randomUUID(), order, deal, student, restaurant, 5, first.commissionReversedCents, availableCents = 4555, heldCents = 0))
        assertMatchesContract(RefundPosted(UUID.randomUUID(), order, deal, student, restaurant, 445, second.commissionReversedCents, availableCents = 5000, heldCents = 0))
        // Over the charge, or never charged (LED-14): nothing moves.
        RefundRefusedReason.entries.forEach { assertMatchesContract(RefundRefused(UUID.randomUUID(), order, deal, student, it, availableCents = 5000, heldCents = 0)) }
    }

    @Test
    fun `release and settlement events match the contract`() {
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        val restaurant = UUID.randomUUID()
        // Every release reason, with the balance after it (Releases.release).
        HoldReleasedReason.entries.forEach { assertMatchesContract(HoldReleased(order, deal, student, 600, it, availableCents = 1000, heldCents = 0)) }
        // The worked example (Settlement.charge), and a charge that returns nothing.
        assertMatchesContract(OrderCharged(order, deal, student, restaurant, 475, 48, 125, availableCents = 4525, heldCents = 0))
        assertMatchesContract(OrderCharged(order, deal, student, restaurant, 475, 48, 0, availableCents = 525, heldCents = 0))
        assertMatchesContract(DealSettled(deal, restaurant, 1, 475, 48))
        assertMatchesContract(DealSettled(deal, restaurant, 0, 0, 0)) // nothing chargeable
        assertMatchesContract(SettlementIssue(deal, order, "The money set aside is for another student"))
        assertTrue(HoldReleasedReason.entries.none { Releases.wording(it).contains("hold", ignoreCase = true) })
    }

    @Test
    fun `refunds as Refunds publishes them match the contract`() {
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        val restaurant = UUID.randomUUID()
        val refund = UUID.randomUUID()
        // The €2.00 of €5.00 example (Refunds.approved), and a refund that reverses no commission.
        assertMatchesContract(RefundPosted(refund, order, deal, student, restaurant, 200, 20, availableCents = 700, heldCents = 0))
        assertMatchesContract(RefundPosted(refund, order, deal, student, restaurant, 1, 0, availableCents = 1, heldCents = 600))
        RefundRefusedReason.entries.forEach { assertMatchesContract(RefundRefused(refund, order, deal, student, it, availableCents = 500, heldCents = 0)) }
    }

    @Test
    fun `a zero-amount refund goes straight to the dead-letter topic`() {
        val refunds = mockk<Refunds>(relaxed = true)
        val support = SupportConsumer(mockk<EventInbox>(relaxed = true), refunds)
        val zero = Envelope.of(RefundApproved(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), student, 0,
            RefundApprovedReason.COMPLAINT, "support001"), 1, now)

        val e = assertThrows<InvalidEvent> { support.on(ConsumerRecord(Topics.SUPPORT_V1, 0, 0L, zero.key, zero)) }
        assertTrue(ExceptionClassifier.defaultFatalExceptionsList().any { it.isInstance(e) })
    }
}
