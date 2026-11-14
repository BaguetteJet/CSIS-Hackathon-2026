package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.CancelReason
import ie.ul.cs4297.groupbuy.contracts.events.DealCancelled
import ie.ul.cs4297.groupbuy.contracts.events.DealFailed
import ie.ul.cs4297.groupbuy.contracts.events.OrderAbandoned
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Item 5 review, LED-08 and LED-09: set-aside money comes back in full, once.
class ReleasesTest : LedgerFlowTest() {

    @Test
    fun `a withdrawal releases the hold in full, once, however often it arrives`() {
        val student = funded(1000)
        val deal = UUID.randomUUID()
        val order = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        val withdrawn = envelope(OrderWithdrawn(order, deal, student))
        sendAndWait(withdrawn, withdrawn, envelope(OrderWithdrawn(order, deal, student)))

        assertEquals(1000L to 0L, balanceOf(student))
        assertEquals("RELEASED", stateOf(order))
        assertEquals(1, movements(MovementType.RELEASE, order))
        val released = events("HoldReleased", deal).single()
        assertEquals("WITHDRAWN", released["reason"].asText())
        assertEquals(600, released["amountCents"].asInt())
        assertEquals(1000, released["availableCents"].asInt())
        assertEquals(0, released["heldCents"].asInt())
        val entry = statement(student).last()
        assertEquals("RELEASE", entry.first)
        assertFalse(entry.second.contains("hold", ignoreCase = true), entry.second)
        assertBooksBalance()
    }

    @Test
    fun `an abandoned order with no hold releases nothing, and a hold placed before OrderAbandoned is processed is released`() {
        val student = funded(1000)
        val deal = UUID.randomUUID()
        val noHold = UUID.randomUUID()
        val late = UUID.randomUUID()
        sendAndWait(envelope(OrderAbandoned(noHold, deal, student)))
        assertTrue(events("HoldReleased", deal).isEmpty())
        assertEquals(HoldResult.ORDER_CLOSED, hold(noHold, student, 100, deal))

        // D-C3: Orders abandoned the order, but its hold reached us before the event did.
        assertEquals(HoldResult.PLACED, hold(late, student, 400, deal))
        sendAndWait(envelope(OrderAbandoned(late, deal, student)))
        assertEquals("ABANDONED", events("HoldReleased", deal).single()["reason"].asText())
        assertEquals(1000L to 0L, balanceOf(student))
        assertBooksBalance()
    }

    @Test
    fun `a failed or cancelled deal releases every open hold, leaves refusals alone, and refuses new holds`() {
        for ((closing, reason) in listOf<Pair<(UUID) -> ie.ul.cs4297.groupbuy.contracts.ContractEvent, String>>(
            { d: UUID -> DealFailed(d, 2) } to "DEAL_FAILED",
            { d: UUID -> DealCancelled(d, CancelReason.DECLINED) } to "DEAL_CANCELLED",
        )) {
            val deal = UUID.randomUUID()
            val students = List(3) { funded(1000) }
            students.forEach { assertEquals(HoldResult.PLACED, hold(UUID.randomUUID(), it, 600, deal)) }
            val broke = funded(0)
            assertEquals(HoldResult.INSUFFICIENT_FUNDS, hold(UUID.randomUUID(), broke, 600, deal))

            sendAndWait(envelope(closing(deal)))
            val released = events("HoldReleased", deal)
            assertEquals(3, released.size)
            assertTrue(released.all { it["reason"].asText() == reason })
            students.forEach { assertEquals(1000L to 0L, balanceOf(it)) }
            assertEquals(HoldResult.DEAL_CLOSED, hold(UUID.randomUUID(), students[0], 100, deal))
        }
        assertBooksBalance()
    }

    @Test
    fun `holds racing a failing deal all end refused or released`() {
        val deal = UUID.randomUUID()
        val students = List(30) { funded(1000) }
        val pool = Executors.newFixedThreadPool(10)
        val futures = students.mapIndexed { i, student ->
            pool.submit<HoldResult> {
                if (i == 10) send(envelope(DealFailed(deal, 10)))
                hold(UUID.randomUUID(), student, 600, deal)
            }
        }
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        await atMost Duration.ofSeconds(30) untilAsserted {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM deal_closure WHERE deal_id = ?", Int::class.java, deal.toString()))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM hold_decision WHERE deal_id = ? AND state = 'OPEN'", Int::class.java, deal.toString()))
        }
        assertTrue(results.all { it == HoldResult.PLACED || it == HoldResult.DEAL_CLOSED }, results.toString())
        assertEquals(results.count { it == HoldResult.PLACED }, events("HoldReleased", deal).size)
        students.forEach { assertEquals(1000L to 0L, balanceOf(it)) }
        assertBooksBalance()
    }
}
