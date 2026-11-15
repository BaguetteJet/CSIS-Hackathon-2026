package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.Money
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.DealFailed
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Item 9: rows of docs/EDGE-CASES.md the code already handles, each now pinned by a test (row ids in the names).
 * Races run as transactions at once on separate connections: two instances are two connections to one MySQL, and its locks decide (decision 0004).
 */
class EdgeCasesTest : LedgerFlowTest() {

    @Autowired lateinit var settlement: Settlement
    @Autowired lateinit var releases: Releases
    @Autowired lateinit var inbox: EventInbox

    /** A deal with [n] orders, each student funded 1000 with 600 set aside. */
    private fun heldDeal(n: Int): Pair<UUID, List<Pair<UUID, UUID>>> {
        val deal = UUID.randomUUID()
        val orders = List(n) { UUID.randomUUID() to funded(1000) }
        orders.forEach { (order, student) -> assertEquals(HoldResult.PLACED, hold(order, student, 600, deal)) }
        return deal to orders
    }

    private fun confirmed(deal: UUID, orders: List<Pair<UUID, UUID>>, price: Long = 475, basisPoints: Int = 1000) =
        DealConfirmed(deal, UUID.randomUUID(), price, basisPoints, orders.map { OrderRef(it.first, it.second) })

    @Test
    fun `X18 commission at the edges matches Money commission, and every cent goes to the restaurant or the Union`() {
        // A half cent rounds up, a ten-thousandth rounds down, then 0% and 100%.
        for ((price, basisPoints) in listOf(5L to 1000, 1L to 5000, 1L to 1, 475L to 0, 475L to 10_000)) {
            val restaurant = UUID.randomUUID()
            val (deal, orders) = heldDeal(1)
            tx.execute { settlement.settle(DealConfirmed(deal, restaurant, price, basisPoints, orders.map { OrderRef(it.first, it.second) }), UUID.randomUUID()) }
            val commission = Money.commission(price, basisPoints)
            assertEquals(commission, events("OrderCharged", deal).single()["commissionCents"].asLong(), "$price at $basisPoints bp")
            assertEquals(price - commission, accounts.postedBalance(AccountRef.restaurantPayable(restaurant)), "$price at $basisPoints bp")
            assertEquals(1000L - price to 0L, balanceOf(orders.single().second))
        }
        assertBooksBalance()
    }

    @Test
    fun `D6 DealFailed after DealConfirmed moves nothing`() {
        val (deal, orders) = heldDeal(2)
        sendAndWait(envelope(confirmed(deal, orders)))
        sendAndWait(envelope(DealFailed(deal, 2)))

        orders.forEach { (order, student) ->
            assertEquals("CHARGED", stateOf(order))
            assertEquals(525L to 0L, balanceOf(student))
        }
        assertTrue(events("HoldReleased", deal).isEmpty())
        assertBooksBalance()
    }

    @Test
    fun `D7 DealConfirmed after DealFailed charges nothing and reports each listed order`() {
        val (deal, orders) = heldDeal(2)
        sendAndWait(envelope(DealFailed(deal, 2)))
        sendAndWait(envelope(confirmed(deal, orders)))

        orders.forEach { (order, student) ->
            assertEquals("RELEASED", stateOf(order))
            assertEquals(1000L to 0L, balanceOf(student))
        }
        assertTrue(events("OrderCharged", deal).isEmpty())
        assertEquals(orders.size, events("SettlementIssue", deal).size)
        assertBooksBalance()
    }

    @Test
    fun `D8 a withdrawal racing its deal's settlement ends the order once, released or charged`() {
        repeat(5) {
            val (deal, orders) = heldDeal(4)
            val withdrawn = orders.take(2)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(3)
            val jobs = withdrawn.map { (order, _) ->
                pool.submit { start.await(); tx.execute { releases.orderClosed(order, deal, HoldReleasedReason.WITHDRAWN, UUID.randomUUID()) } }
            } + pool.submit { start.await(); tx.execute { settlement.settle(confirmed(deal, orders), UUID.randomUUID()) } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()

            for ((order, student) in withdrawn) {
                val state = stateOf(order)
                assertTrue(state == "RELEASED" || state == "CHARGED", "$order ended $state")
                assertEquals(if (state == "RELEASED") 1000L to 0L else 525L to 0L, balanceOf(student))
            }
            orders.drop(2).forEach { assertEquals("CHARGED", stateOf(it.first)) }
        }
        assertBooksBalance()
    }

    @Test
    fun `D18 a withdrawal at the same moment as its hold leaves nothing set aside`() {
        val deal = UUID.randomUUID()
        val orders = List(10) { UUID.randomUUID() to funded(1000) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        val placed = orders.map { (order, student) -> pool.submit<HoldResult> { start.await(); hold(order, student, 600, deal) } }
        val closed = orders.map { (order, _) ->
            pool.submit { start.await(); tx.execute { releases.orderClosed(order, deal, HoldReleasedReason.WITHDRAWN, UUID.randomUUID()) } }
        }
        start.countDown()
        val results = placed.map { it.get(30, TimeUnit.SECONDS) }
        closed.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(results.all { it == HoldResult.PLACED || it == HoldResult.ORDER_CLOSED }, results.toString())
        orders.forEach { (order, student) ->
            assertTrue(stateOf(order) != "OPEN", "$order still set aside")
            assertEquals(1000L to 0L, balanceOf(student))
        }
        assertEquals(results.count { it == HoldResult.PLACED }, events("HoldReleased", deal).size)
        assertBooksBalance()
    }

    @Test
    fun `D20 DealFailed racing DealConfirmed charges the whole deal or releases it, never both`() {
        repeat(5) {
            val (deal, orders) = heldDeal(3)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val jobs = listOf(
                pool.submit { start.await(); tx.execute { releases.dealClosed(deal, HoldReleasedReason.DEAL_FAILED, "FAILED", UUID.randomUUID()) } },
                pool.submit { start.await(); tx.execute { settlement.settle(confirmed(deal, orders), UUID.randomUUID()) } },
            )
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()

            val states = orders.map { stateOf(it.first) }.toSet()
            assertTrue(states == setOf("CHARGED") || states == setOf("RELEASED"), "$deal ended $states")
        }
        assertBooksBalance()
    }

    @Test
    fun `T7 a top-up racing a hold for exactly the new balance is placed or refused, never below zero`() {
        repeat(10) {
            val student = funded(400)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val topUp = pool.submit { start.await(); tx.execute { topUps.creditTopUp(UUID.randomUUID(), student, 600) } }
            val held = pool.submit<HoldResult> { start.await(); hold(UUID.randomUUID(), student, 1000, UUID.randomUUID()) }
            start.countDown()
            topUp.get(30, TimeUnit.SECONDS)
            val result = held.get(30, TimeUnit.SECONDS)
            pool.shutdown()

            assertTrue(result == HoldResult.PLACED || result == HoldResult.INSUFFICIENT_FUNDS, "$result")
            assertEquals(if (result == HoldResult.PLACED) 0L to 1000L else 1000L to 0L, balanceOf(student))
        }
        assertBooksBalance()
    }

    @Test
    fun `K3 the same event on both instances at once is applied once`() {
        val event = envelope(OrderWithdrawn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        val runs = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        // The first holds its inbox row while it works; the second waits on that row, then finds it taken.
        val applied = List(2) {
            pool.submit<Boolean> { start.await(); inbox.once("edge-cases-k3", event) { runs.incrementAndGet(); Thread.sleep(300) } }
        }
        start.countDown()
        val results = applied.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, runs.get())
        assertEquals(listOf(false, true), results.sorted())
    }
}
