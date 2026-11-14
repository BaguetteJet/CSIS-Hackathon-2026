package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundApprovedReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

// Item 6 review, LED-13: a refund goes back to the student, never more than the order was charged (marked property).
class RefundsTest : LedgerFlowTest() {

    private class Charged(val student: UUID, val order: UUID, val deal: UUID, val restaurant: UUID)

    /** One order set aside at 600 and charged [price] at [basisPoints] commission. */
    private fun charged(price: Long, basisPoints: Int): Charged {
        val c = Charged(funded(1000), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        assertEquals(HoldResult.PLACED, hold(c.order, c.student, 600, c.deal))
        sendAndWait(envelope(DealConfirmed(c.deal, c.restaurant, price, basisPoints, listOf(OrderRef(c.order, c.student)))))
        return c
    }

    private fun approved(c: Charged, cents: Long, refund: UUID = UUID.randomUUID()) =
        RefundApproved(refund, c.order, c.deal, c.student, cents, RefundApprovedReason.COMPLAINT, "support001")

    private fun refundedSoFar(order: UUID): Pair<Long, Long> = jdbc.queryForMap(
        "SELECT refunded_cents, commission_reversed_cents FROM order_charge WHERE order_id = ?", order.toString(),
    ).let { (it["refunded_cents"] as Number).toLong() to (it["commission_reversed_cents"] as Number).toLong() }

    @Test
    fun `a refund goes back to the student, taken from the commission and the restaurant in proportion`() {
        val c = charged(500, 1000) // 50 commission, 450 to the restaurant, 100 back: 500 available
        val commissionBefore = accounts.postedBalance(AccountRef.UNION_COMMISSION)

        sendAndWait(envelope(approved(c, 200)), consumer = SupportConsumer.CONSUMER)

        assertEquals(700L to 0L, balanceOf(c.student))
        assertEquals(270L, accounts.postedBalance(AccountRef.restaurantPayable(c.restaurant)))
        assertEquals(-20L, accounts.postedBalance(AccountRef.UNION_COMMISSION) - commissionBefore)
        val posted = events("RefundPosted", c.deal).single()
        assertEquals(listOf(200, 20, 700, 0), listOf("amountCents", "commissionReversedCents", "availableCents", "heldCents").map { posted[it].asInt() })
        assertEquals(c.restaurant.toString(), posted["restaurantId"].asText())
        assertEquals("REFUND" to "Refund: €2.00 back in your Account", statement(c.student).last())
        assertEquals(200L to 20L, refundedSoFar(c.order))
        assertBooksBalance()
    }

    @Test
    fun `the same refund twice moves money once, as the same or a new envelope`() {
        val c = charged(500, 1000)
        val refund = approved(c, 200)
        val first = envelope(refund)

        sendAndWait(first, first, envelope(refund), consumer = SupportConsumer.CONSUMER)

        assertEquals(1, movements(MovementType.REFUND, refund.refundId))
        assertEquals(1, events("RefundPosted", c.deal).size)
        assertEquals(700L to 0L, balanceOf(c.student))
        assertBooksBalance()
    }

    @Test
    fun `part refunds round on the running total, and one past the charge is refused`() {
        val c = charged(450, 1000) // 45 commission

        sendAndWait(envelope(approved(c, 5)), consumer = SupportConsumer.CONSUMER)
        sendAndWait(envelope(approved(c, 445)), consumer = SupportConsumer.CONSUMER)
        val over = approved(c, 1)
        sendAndWait(envelope(over), consumer = SupportConsumer.CONSUMER)

        assertEquals(listOf(1, 44), events("RefundPosted", c.deal).map { it["commissionReversedCents"].asInt() })
        assertEquals(450L to 45L, refundedSoFar(c.order))
        val refused = events("RefundRefused", c.deal).single()
        assertEquals("EXCEEDS_CHARGE", refused["reason"].asText())
        assertEquals(0, movements(MovementType.REFUND, over.refundId))
        assertEquals(1000L to 0L, balanceOf(c.student)) // 550 after the charge, then 450 back
        assertBooksBalance()
    }

    @Test
    fun `a refund over the charge is refused and moves nothing`() {
        val c = charged(500, 1000)
        val refund = approved(c, 501)

        sendAndWait(envelope(refund), consumer = SupportConsumer.CONSUMER)

        assertEquals("EXCEEDS_CHARGE", events("RefundRefused", c.deal).single()["reason"].asText())
        assertEquals(0, movements(MovementType.REFUND, refund.refundId))
        assertEquals(500L to 0L, balanceOf(c.student))
        assertEquals(0L to 0L, refundedSoFar(c.order))
        assertBooksBalance()
    }

    @Test
    fun `a refund before its charge waits, parked, and moves nothing`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        val refund = RefundApproved(UUID.randomUUID(), order, deal, student, 200, RefundApprovedReason.KITCHEN_FAILURE, "support001")

        sendAndWait(envelope(refund), consumer = SupportConsumer.CONSUMER)

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM parked_event WHERE consumer = ? AND waits_for = ?",
            Int::class.java, SupportConsumer.CONSUMER, "order:$order"))
        assertEquals(0, movements(MovementType.REFUND, refund.refundId))
        assertEquals(400L to 600L, balanceOf(student))
        assertEquals(0, events("RefundPosted", deal).size + events("RefundRefused", deal).size)
    }
}
