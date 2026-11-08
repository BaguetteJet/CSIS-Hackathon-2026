package ie.ul.cs4297.groupbuy.service.templates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * TEMPLATE for the Ledger: a zero-sum property test. Other teams can delete this file.
 *
 * Double-entry bookkeeping means every movement has two sides that add up to zero, so the sum of
 * every account is always zero, whatever happens. This test throws many random sequences of
 * operations at your ledger and checks that after every single one.
 *
 * To use it: implement [LedgerUnderTest] over your own Ledger code (in memory, or against the
 * database in a container test), give each operation real meaning, and remove @Disabled.
 * When it fails, it prints the seed: run that seed again to reproduce the exact sequence.
 */
@Disabled("Template: implement LedgerUnderTest for your Ledger, then remove @Disabled")
class ZeroSumPropertyTest {

    /** What the test needs from your Ledger. Signatures are yours to change. */
    interface LedgerUnderTest {
        fun topUp(student: Int, cents: Long)
        fun hold(student: Int, order: Int, cents: Long): Boolean
        fun release(order: Int)
        fun settle(order: Int, priceCents: Long, commissionBasisPoints: Int)
        fun refund(order: Int, cents: Long): Boolean
        /** The sum of every account's balance, in cents, signed as your ledger signs them. */
        fun sumOfAllAccounts(): Long
    }

    /** Your Ledger, fresh for each run. */
    private fun newLedger(): LedgerUnderTest = TODO("return your Ledger here")

    @Test
    fun `every account adds up to zero after any sequence of operations`() {
        repeat(200) { run ->
            val seed = Random.nextLong()
            val random = Random(seed)
            val ledger = newLedger()
            val orders = mutableListOf<Int>()
            repeat(random.nextInt(10, 200)) { step ->
                val student = random.nextInt(5)
                when (random.nextInt(5)) {
                    0 -> ledger.topUp(student, random.nextLong(500, 20_001))
                    1 -> { val order = run * 1000 + step; if (ledger.hold(student, order, 600)) orders += order }
                    2 -> orders.randomOrNull(random)?.let { ledger.release(it) }
                    3 -> orders.randomOrNull(random)?.let { ledger.settle(it, random.nextLong(100, 601), 1000) }
                    4 -> orders.randomOrNull(random)?.let { ledger.refund(it, random.nextLong(1, 601)) }
                }
                assertEquals(0L, ledger.sumOfAllAccounts(), "not zero-sum after step $step; run again with seed $seed")
            }
        }
    }
}
