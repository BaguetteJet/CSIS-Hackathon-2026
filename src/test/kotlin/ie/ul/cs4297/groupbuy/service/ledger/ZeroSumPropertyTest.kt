package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.api.HoldRequest
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.DealFailed
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundApprovedReason
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalCompleted
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalFailed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import ie.ul.cs4297.groupbuy.service.support.TestClock
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.random.Random

/**
 * [LED-20] - Two instances, one truth: the marked property. Random top-ups, holds, releases, settlements, refunds and
 * cash-outs, and after every step the books sum to zero, no student is below zero, and each kept balance equals its postings.
 * Each step goes through the real consumer or hold call, so the inbox, locks and transactions are the production ones; only Kafka is skipped.
 * On a failure the message has the seed: put it in [seeds] to replay that exact run.
 */
class ZeroSumPropertyTest : LedgerFlowTest() {

    @Autowired lateinit var closures: ClosuresConsumer
    @Autowired lateinit var payments: PaymentsConsumer
    @Autowired lateinit var support: SupportConsumer

    private val staff = UUID.randomUUID()

    private fun record(event: ContractEvent): ConsumerRecord<String, Envelope<ContractEvent>?> =
        ConsumerRecord(event.topic(), 0, 0L, event.key(), envelope(event))

    private fun deliver(event: ContractEvent) = when (event.topic()) {
        Topics.PAYMENTS_V1 -> payments.on(record(event))
        Topics.SUPPORT_V1 -> support.on(record(event))
        else -> closures.on(record(event))
    }

    private class Placed(val order: UUID, val student: UUID, val deal: UUID)

    private fun seeds(): List<Long> = List(RUNS) { Random.nextLong() }

    @Test
    fun `any mix of money movements keeps the books at zero and every student at or above zero`() {
        for (seed in seeds()) {
            val random = Random(seed)
            val students = List(4) { UUID.randomUUID() }
            val deals = List(3) { UUID.randomUUID() }
            val placed = mutableListOf<Placed>()
            val cashOuts = mutableListOf<Pair<UUID, UUID>>()
            repeat(random.nextInt(STEPS / 2, STEPS)) { step ->
                val student = students.random(random)
                when (random.nextInt(9)) {
                    0, 1 -> deliver(TopUpRecorded(UUID.randomUUID(), student, random.nextLong(100, 3001), staff))
                    2, 3 -> {
                        val order = UUID.randomUUID()
                        val deal = deals.random(random)
                        holds.place(HoldRequest(order, student, deal, TestClock.instant(), random.nextLong(100, 801)))
                        placed += Placed(order, student, deal)
                    }
                    4 -> placed.randomOrNull(random)?.let { deliver(OrderWithdrawn(it.order, it.deal, it.student)) }
                    5 -> {
                        // List a random part of one deal's orders, at a price that may be above some holds.
                        val deal = deals.random(random)
                        val listed = placed.filter { it.deal == deal && random.nextBoolean() }.map { OrderRef(it.order, it.student) }
                        if (random.nextInt(4) == 0) deliver(DealFailed(deal, listed.size))
                        else deliver(DealConfirmed(deal, UUID.randomUUID(), random.nextLong(100, 701), random.nextInt(0, 2001), listed))
                    }
                    6 -> placed.randomOrNull(random)?.let {
                        deliver(RefundApproved(UUID.randomUUID(), it.order, it.deal, it.student, random.nextLong(1, 701), RefundApprovedReason.COMPLAINT, "support001"))
                    }
                    7 -> UUID.randomUUID().let { id ->
                        deliver(WithdrawalRequested(id, student, random.nextLong(100, 3001), staff))
                        cashOuts += id to student
                    }
                    8 -> cashOuts.randomOrNull(random)?.let { (id, owner) ->
                        if (random.nextBoolean()) deliver(WithdrawalCompleted(id, owner, 0, staff))
                        else deliver(WithdrawalFailed(id, owner, 0, "Explained in a few words"))
                    }
                }
                assertBooks(students, "step $step of seed $seed")
            }
        }
    }

    private fun assertBooks(students: List<UUID>, where: String) {
        assertEquals(0L, accounts.sumOfAllAccounts(), "the books don't sum to zero after $where")
        val ids = students.map { it.toString() }
        val placeholders = ids.joinToString(",") { "?" }
        val below = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'STUDENT' AND owner_id IN ($placeholders) AND balance_cents < 0",
            Int::class.java, *ids.toTypedArray(),
        )
        assertEquals(0, below, "a student is below zero after $where")
        val drifted = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_account a WHERE a.owner_type = 'STUDENT' AND a.owner_id IN ($placeholders) " +
                "AND a.balance_cents <> (SELECT COALESCE(SUM(p.amount_cents), 0) FROM ledger_posting p WHERE p.account_id = a.id)",
            Int::class.java, *ids.toTypedArray(),
        )
        assertEquals(0, drifted, "a kept balance differs from its postings after $where")
    }

    companion object {
        private const val RUNS = 15
        private const val STEPS = 120
    }
}
