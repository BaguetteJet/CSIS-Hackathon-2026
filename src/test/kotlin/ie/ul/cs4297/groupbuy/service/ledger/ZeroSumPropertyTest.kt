package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundApprovedReason
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalCompleted
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalFailed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.random.Random

/**
 * Item 7, LED-20 (marked): after any sequence of money operations, no money is created or lost
 * and no student balance is negative. Each step calls the real service, in a transaction, the way its consumer does.
 * On failure the message names the seed: put it in [REPLAY] to run that exact sequence again.
 * [RUNS] is 50 so the suite stays quick; `ZERO_SUM_RUNS=200 ./gradlew containerTest` for the evidence run.
 */
class ZeroSumPropertyTest : LedgerFlowTest() {

    @Autowired lateinit var settlement: Settlement
    @Autowired lateinit var releases: Releases
    @Autowired lateinit var refunds: Refunds
    @Autowired lateinit var withdrawals: Withdrawals
    @Autowired lateinit var payouts: Payouts

    private class Order(val order: UUID, val student: UUID, val deal: UUID)

    /** One run's world: a few students, the deal being joined, and what is still open, charged or reserved. */
    private inner class Run(val random: Random) {
        val students = List(3) { UUID.randomUUID() }
        val restaurant: UUID = UUID.randomUUID()
        var deal: UUID = UUID.randomUUID()
        val open = mutableListOf<Order>()
        val charged = mutableListOf<Order>()
        val reserved = mutableListOf<Pair<UUID, UUID>>()

        init {
            // Start with money, so holds, charges and refunds happen often enough to matter.
            for (s in students) {
                tx.execute { topUps.creditTopUp(UUID.randomUUID(), s, 5_000) }
            }
        }

        fun student() = students[random.nextInt(students.size)]

        fun step(): String {
            when (random.nextInt(8)) {
                0 -> {
                    val cents = random.nextLong(500, 20_001)
                    tx.execute { topUps.creditTopUp(UUID.randomUUID(), student(), cents) }
                    return "top-up $cents"
                }
                1 -> {
                    val o = Order(UUID.randomUUID(), student(), deal)
                    val cents = random.nextLong(100, 801)
                    val result = hold(o.order, o.student, cents, o.deal)
                    if (result == HoldResult.PLACED) open += o
                    return "hold $cents: $result"
                }
                2 -> {
                    val o = open.randomOrNull(random) ?: return "release: nothing open"
                    tx.execute { releases.orderClosed(o.order, o.deal, HoldReleasedReason.WITHDRAWN, UUID.randomUUID()) }
                    open -= o
                    return "release ${o.order}"
                }
                3 -> {
                    // Some open orders make the list; the rest are released NOT_ON_LIST. A price above a hold is a SettlementIssue, and released too.
                    val listed = open.filter { random.nextBoolean() }
                    val price = random.nextLong(100, 801)
                    val basisPoints = random.nextInt(0, 3001)
                    tx.execute { settlement.settle(DealConfirmed(deal, restaurant, price, basisPoints, listed.map { OrderRef(it.order, it.student) }), UUID.randomUUID()) }
                    charged += listed
                    open.clear()
                    deal = UUID.randomUUID()
                    return "settle ${listed.size} orders at $price, $basisPoints bp"
                }
                4 -> {
                    val o = charged.randomOrNull(random) ?: return "refund: nothing charged"
                    val cents = random.nextLong(1, 801)
                    val e = RefundApproved(UUID.randomUUID(), o.order, o.deal, o.student, cents, RefundApprovedReason.COMPLAINT, "support001")
                    tx.execute { refunds.approved(envelope(e), e) }
                    return "refund $cents"
                }
                5 -> {
                    val withdrawal = UUID.randomUUID()
                    val student = student()
                    val cents = random.nextLong(100, 5_001)
                    tx.execute { withdrawals.requested(WithdrawalRequested(withdrawal, student, cents, UUID.randomUUID()), UUID.randomUUID()) }
                    reserved += withdrawal to student
                    return "cash-out request $cents"
                }
                6 -> {
                    val (withdrawal, student) = reserved.randomOrNull(random) ?: return "cash-out end: nothing reserved"
                    reserved.remove(withdrawal to student)
                    if (random.nextBoolean()) {
                        tx.execute { withdrawals.completed(WithdrawalCompleted(withdrawal, student, 1, UUID.randomUUID()), UUID.randomUUID()) }
                        return "cash-out paid out"
                    }
                    tx.execute { withdrawals.failed(WithdrawalFailed(withdrawal, student, 1, "Changed their mind"), UUID.randomUUID()) }
                    return "cash-out returned"
                }
                else -> {
                    val cents = random.nextLong(1, 5_001)
                    tx.execute { payouts.confirmed(PayoutConfirmed(UUID.randomUUID(), restaurant, cents), UUID.randomUUID()) }
                    return "payout $cents"
                }
            }
        }
    }

    @Test
    fun `no money is created or lost and no student goes negative, after any sequence of operations`() {
        val seeds = if (REPLAY != null) listOf(REPLAY) else List(RUNS) { Random.nextLong() }
        val done = sortedMapOf<String, Int>()
        for (seed in seeds) {
            val run = Run(Random(seed))
            val steps = mutableListOf<String>()
            try {
                repeat(run.random.nextInt(10, 41)) {
                    steps += run.step()
                    assertEquals(0L, accounts.sumOfAllAccounts(), "all postings sum to 0")
                    assertEquals(0, count("SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'STUDENT' AND balance_cents < 0"), "no student balance below 0")
                }
                // Heavier checks once per run: each movement balances, and each student's running balance matches its postings.
                assertEquals(0, count("SELECT COUNT(*) FROM (SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents) <> 0) x"), "every movement sums to 0")
                for (student in run.students) {
                    for (ref in listOf(AccountRef.studentAvailable(student), AccountRef.studentHeld(student))) {
                        val running = jdbc.queryForList("SELECT balance_cents FROM ledger_account WHERE owner_type = 'STUDENT' AND owner_id = ? AND purpose = ?",
                            Long::class.java, student.toString(), ref.purpose.name).singleOrNull() ?: 0L
                        assertEquals(accounts.postedBalance(ref), running, "$ref running balance equals its postings")
                    }
                }
            } catch (e: Throwable) {
                throw AssertionError("Failed with seed $seed after ${steps.size} steps (set REPLAY = ${seed}L to rerun): ${steps.joinToString(" | ")}", e)
            }
            for (s in steps) {
                val kind = s.replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27}|\\d+"), "").replace(Regex(" *([,:]) *"), "$1 ").replace(Regex("\\s+"), " ").trim()
                done[kind] = (done[kind] ?: 0) + 1
            }
        }
        // Printed so the evidence shows every kind of operation really ran.
        println("ZeroSumPropertyTest: ${seeds.size} runs, ${done.values.sum()} steps: $done")
        assertBooksBalance()
    }

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    companion object {
        val RUNS = System.getenv("ZERO_SUM_RUNS")?.toIntOrNull() ?: 50

        /** A seed from a failure message, to replay that one run; null runs [RUNS] random ones. */
        val REPLAY: Long? = null
    }
}
