package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.util.UUID

// [LED-05] - Settle a confirmed deal; [LED-06] - Settle exactly once; [LED-10] - Settlement problems: each listed order pays the tier price once, the rest comes back, problems are reported (item 5 review).
class SettlementTest : LedgerFlowTest() {

    @Autowired lateinit var settlement: Settlement

    @Test
    fun `the worked example to the cent, with no RestaurantRegistered first`() {
        val student = funded(5000)
        val deal = UUID.randomUUID()
        val restaurant = UUID.randomUUID()
        val order = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        val commissionBefore = accounts.postedBalance(AccountRef.UNION_COMMISSION)

        sendAndWait(envelope(DealConfirmed(deal, restaurant, 475, 1000, listOf(OrderRef(order, student)))))

        assertEquals(4525L to 0L, balanceOf(student))
        assertEquals("CHARGED", stateOf(order))
        val charged = events("OrderCharged", deal).single()
        assertEquals(listOf(475, 48, 125, 4525, 0), listOf("priceCents", "commissionCents", "returnedCents", "availableCents", "heldCents").map { charged[it].asInt() })
        val settled = events("DealSettled", deal).single()
        assertEquals(listOf(1, 475, 48), listOf("count", "totalChargedCents", "totalCommissionCents").map { settled[it].asInt() })
        assertEquals(427L, accounts.postedBalance(AccountRef.restaurantPayable(restaurant)))
        assertEquals(48L, accounts.postedBalance(AccountRef.UNION_COMMISSION) - commissionBefore)
        assertEquals("RETURNED" to "Paid €4.75, €1.25 back in your Account", statement(student).last())
        assertBooksBalance()
    }

    @Test
    fun `holds not on the list are released, nothing returned adds no entry, and new holds are refused`() {
        val deal = UUID.randomUUID()
        val listed = funded(1000)
        val unlisted = funded(1000)
        val listedOrder = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(listedOrder, listed, 475, deal))
        assertEquals(HoldResult.PLACED, hold(UUID.randomUUID(), unlisted, 600, deal))

        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 475, 1000, listOf(OrderRef(listedOrder, listed)))))

        assertEquals(525L to 0L, balanceOf(listed))
        assertEquals("HOLD", statement(listed).last().first) // exactly the price: no RETURNED entry
        assertEquals(1000L to 0L, balanceOf(unlisted))
        assertEquals("NOT_ON_LIST", events("HoldReleased", deal).single()["reason"].asText())
        assertEquals(HoldResult.DEAL_CLOSED, hold(UUID.randomUUID(), unlisted, 100, deal))
        assertBooksBalance()
    }

    @Test
    fun `listed orders that can't be charged are reported, left as they are, and left out of DealSettled`() {
        val deal = UUID.randomUUID()
        val good = funded(1000) to UUID.randomUUID()
        val released = funded(1000) to UUID.randomUUID()
        val otherDeal = funded(1000) to UUID.randomUUID()
        val otherStudent = funded(1000) to UUID.randomUUID()
        val below = funded(1000) to UUID.randomUUID()
        val noHold = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(good.second, good.first, 600, deal))
        assertEquals(HoldResult.PLACED, hold(released.second, released.first, 600, deal))
        sendAndWait(envelope(OrderWithdrawn(released.second, deal, released.first)))
        assertEquals(HoldResult.PLACED, hold(otherDeal.second, otherDeal.first, 600, UUID.randomUUID()))
        assertEquals(HoldResult.PLACED, hold(otherStudent.second, otherStudent.first, 600, deal))
        assertEquals(HoldResult.PLACED, hold(below.second, below.first, 400, deal))

        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 475, 1000, listOf(
            OrderRef(good.second, good.first), OrderRef(released.second, released.first), OrderRef(otherDeal.second, otherDeal.first),
            OrderRef(otherStudent.second, UUID.randomUUID()), OrderRef(below.second, below.first), OrderRef(noHold, UUID.randomUUID()),
        ))))

        assertEquals(1, events("OrderCharged", deal).size)
        val problems = events("SettlementIssue", deal).associate { UUID.fromString(it["orderId"].asText()) to it["problem"].asText() }
        assertEquals(setOf(released.second, otherDeal.second, otherStudent.second, below.second, noHold), problems.keys)
        assertTrue(problems.getValue(otherStudent.second).contains("another student"))
        assertTrue(problems.getValue(below.second).contains("below the price"))
        assertEquals(listOf("OPEN", "OPEN", "OPEN"), listOf(otherDeal, otherStudent, below).map { stateOf(it.second) })
        assertEquals(400L to 600L, balanceOf(otherStudent.first))
        val settled = events("DealSettled", deal).single()
        assertEquals(listOf(1, 475, 48), listOf("count", "totalChargedCents", "totalCommissionCents").map { settled[it].asInt() })
        assertBooksBalance()
    }

    @Test
    fun `DealConfirmed again, as the same or a new envelope, settles once`() {
        val student = funded(1000)
        val deal = UUID.randomUUID()
        val order = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        val confirmed = DealConfirmed(deal, UUID.randomUUID(), 475, 1000, listOf(OrderRef(order, student)))
        val first = envelope(confirmed)
        sendAndWait(first, first, envelope(confirmed))

        assertEquals(1, events("OrderCharged", deal).size)
        assertEquals(1, events("DealSettled", deal).size)
        assertEquals(1, movements(MovementType.ORDER_CHARGE, order))
        assertEquals(525L to 0L, balanceOf(student))
        assertBooksBalance()
    }

    @Test
    fun `a crash halfway charges nothing, and the retry settles the whole deal once`() {
        val deal = UUID.randomUUID()
        val orders = List(3) { UUID.randomUUID() to funded(1000) }.sortedBy { it.first.toString() }
        orders.forEach { (order, student) -> assertEquals(HoldResult.PLACED, hold(order, student, 600, deal)) }
        val confirmed = DealConfirmed(deal, UUID.randomUUID(), 475, 1000, orders.map { OrderRef(it.first, it.second) })

        // Make the second order's charge fail, after the first was charged in the same transaction.
        val middle = orders[1].first
        jdbc.update(
            "INSERT INTO order_charge (order_id, deal_id, student_id, restaurant_id, price_cents, commission_cents, returned_cents, charged_at) VALUES (?, ?, ?, ?, 0, 0, 0, ?)",
            middle.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), Timestamp.from(java.time.Instant.now()),
        )
        assertThrows<Exception> { tx.execute { settlement.settle(confirmed, UUID.randomUUID()) } }

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM deal_settlement WHERE deal_id = ?", Int::class.java, deal.toString()))
        orders.forEach { (order, student) ->
            assertEquals("OPEN", stateOf(order))
            assertEquals(0, movements(MovementType.ORDER_CHARGE, order))
            assertEquals(400L to 600L, balanceOf(student))
        }
        assertTrue(events("OrderCharged", deal).isEmpty())

        jdbc.update("DELETE FROM order_charge WHERE order_id = ?", middle.toString())
        tx.execute { settlement.settle(confirmed, UUID.randomUUID()) }
        tx.execute { settlement.settle(confirmed, UUID.randomUUID()) }
        orders.forEach { (order, student) ->
            assertEquals(1, movements(MovementType.ORDER_CHARGE, order))
            assertEquals(525L to 0L, balanceOf(student))
        }
        assertEquals(3, events("OrderCharged", deal).size)
        assertEquals(1, events("DealSettled", deal).size)
        assertBooksBalance()
    }

    @Test
    // 200: the biggest deal any test runs (Platform's load test, PLT-14)
    fun `a deal at its cap settles in one transaction, in under 3 seconds`() {
        val deal = UUID.randomUUID()
        val orders = List(200) { UUID.randomUUID() to funded(1000) }
        orders.forEach { (order, student) -> assertEquals(HoldResult.PLACED, hold(order, student, 600, deal)) }
        val confirmed = DealConfirmed(deal, UUID.randomUUID(), 475, 1000, orders.map { OrderRef(it.first, it.second) })

        val started = System.nanoTime()
        tx.execute { settlement.settle(confirmed, UUID.randomUUID()) }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("Settled 200 orders in $millis ms")

        assertEquals(200, jdbc.queryForObject("SELECT charged_count FROM deal_settlement WHERE deal_id = ?", Int::class.java, deal.toString()))
        assertTrue(millis < 3_000, "settling 200 orders took $millis ms")
        assertBooksBalance()
    }
}
