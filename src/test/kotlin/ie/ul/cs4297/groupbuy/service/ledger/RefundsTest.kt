package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.CancelReason
import ie.ul.cs4297.groupbuy.contracts.events.DealCancelled
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.DealFailed
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.contracts.events.OrderAbandoned
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundApprovedReason
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

// [LED-13] - Post refunds: a refund goes back to the student, never more than the order was charged (marked property; item 6 review).
class RefundsTest : LedgerFlowTest() {

    @Autowired lateinit var settlement: Settlement
    @Autowired lateinit var releases: Releases

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

    private fun refund(order: UUID, deal: UUID, student: UUID, cents: Long = 200) =
        RefundApproved(UUID.randomUUID(), order, deal, student, cents, RefundApprovedReason.KITCHEN_FAILURE, "SYSTEM")

    /** Refunds still parked for this refund's order or deal. */
    private fun parked(r: RefundApproved): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM parked_event WHERE consumer = ? AND waits_for IN (?, ?)",
        Int::class.java, SupportConsumer.CONSUMER, "order:${r.orderId}", "deal:${r.dealId}",
    )!!

    /** The student's events in outbox order, as (type, availableCents). */
    private fun chain(student: UUID): List<Pair<String, Int>> = jdbc.queryForList(
        "SELECT message_json FROM outbox_event WHERE event_key = ? ORDER BY id", String::class.java, student.toString(),
    ).map { mapper.readTree(it) }.map { it["type"].asText() to it["payload"]["availableCents"].asInt() }

    // [LED-14] - Refund limits and early refunds
    @Test
    fun `a parked refund is posted when its order is charged, after the settlement's own events`() {
        val student = funded(2000)
        val charged = UUID.randomUUID()
        val notListed = UUID.randomUUID()
        val deal = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(charged, student, 600, deal))
        assertEquals(HoldResult.PLACED, hold(notListed, student, 300, deal))
        val early = refund(charged, deal, student)
        sendAndWait(envelope(early), envelope(early), consumer = SupportConsumer.CONSUMER) // twice: decided once

        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 500, 1000, listOf(OrderRef(charged, student)))))

        assertEquals(0, parked(early))
        assertEquals(1, movements(MovementType.REFUND, early.refundId))
        assertEquals(listOf(200, 20), events("RefundPosted", deal).single().let { listOf(it["amountCents"].asInt(), it["commissionReversedCents"].asInt()) })
        assertEquals(1700L to 0L, balanceOf(student))
        // Each event's balance chains from the one before it, the refund last.
        assertEquals(
            listOf("HoldPlaced" to 1400, "HoldPlaced" to 1100, "OrderCharged" to 1200, "HoldReleased" to 1500, "RefundPosted" to 1700),
            chain(student),
        )
        assertBooksBalance()
    }

    @Test
    fun `a parked refund whose hold is released is refused NEVER_CHARGED`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        val early = refund(order, deal, student)
        sendAndWait(envelope(early), consumer = SupportConsumer.CONSUMER)

        sendAndWait(envelope(OrderWithdrawn(order, deal, student)))

        assertEquals(0, parked(early))
        assertEquals("NEVER_CHARGED", events("RefundRefused", deal).single()["reason"].asText())
        assertEquals(0, movements(MovementType.REFUND, early.refundId))
        assertEquals(1000L to 0L, balanceOf(student))
        assertBooksBalance()
    }

    @Test
    fun `a parked refund for an order left off the deal's list is refused NEVER_CHARGED`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        val early = refund(order, deal, student)
        sendAndWait(envelope(early), consumer = SupportConsumer.CONSUMER)

        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 500, 1000, emptyList())))

        assertEquals(0, parked(early))
        assertEquals("NEVER_CHARGED", events("RefundRefused", deal).single()["reason"].asText())
        assertEquals(1000L to 0L, balanceOf(student))
    }

    // EDGE-CASES Q31: the hold stays set aside for staff (Q24), but its deal is decided, so it can't be charged.
    @Test
    fun `a parked refund for a listed order that can't be charged is refused NEVER_CHARGED, and so is a later one`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 400, deal))
        val early = refund(order, deal, student)
        sendAndWait(envelope(early), consumer = SupportConsumer.CONSUMER)

        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 500, 1000, listOf(OrderRef(order, student)))))

        assertEquals(0, parked(early))
        assertEquals("NEVER_CHARGED", events("RefundRefused", deal).single()["reason"].asText())
        assertEquals(600L to 400L, balanceOf(student))

        val late = refund(order, deal, student)
        sendAndWait(envelope(late), consumer = SupportConsumer.CONSUMER)

        assertEquals(0, parked(late))
        assertEquals(2, events("RefundRefused", deal).count { it["reason"].asText() == "NEVER_CHARGED" })
        assertEquals(600L to 400L, balanceOf(student))
        assertBooksBalance()
    }

    @Test
    fun `a parked refund whose hold is for another deal still open keeps waiting`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val holdDeal = UUID.randomUUID()
        val listedIn = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, holdDeal))
        val early = refund(order, listedIn, student)
        sendAndWait(envelope(early), consumer = SupportConsumer.CONSUMER)

        sendAndWait(envelope(DealConfirmed(listedIn, UUID.randomUUID(), 500, 1000, listOf(OrderRef(order, student)))))

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM parked_event WHERE consumer = ? AND waits_for = ?",
            Int::class.java, SupportConsumer.CONSUMER, "order:$order"))
        assertEquals(0, events("RefundRefused", listedIn).size)
    }

    @Test
    fun `a refund for an order already released is refused NEVER_CHARGED at once`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        sendAndWait(envelope(OrderAbandoned(order, deal, student)))

        val late = refund(order, deal, student)
        sendAndWait(envelope(late), consumer = SupportConsumer.CONSUMER)

        assertEquals(0, parked(late))
        assertEquals("NEVER_CHARGED", events("RefundRefused", deal).single()["reason"].asText())
        assertEquals(1000L to 0L, balanceOf(student))
    }

    @Test
    fun `a refund with no hold waits for its deal's outcome, then is refused NEVER_CHARGED`() {
        val student = funded(1000)
        val deal = UUID.randomUUID()
        val noHold = refund(UUID.randomUUID(), deal, student)
        sendAndWait(envelope(noHold), consumer = SupportConsumer.CONSUMER)
        assertEquals(1, parked(noHold))

        sendAndWait(envelope(DealFailed(deal, 2)))

        assertEquals(0, parked(noHold))
        assertEquals("NEVER_CHARGED", events("RefundRefused", deal).single()["reason"].asText())
        assertEquals(1000L to 0L, balanceOf(student))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `deal closure waits for an earlier refund student before locking a later student`(confirm: Boolean) {
        val refundStudent = funded(1000)
        val orderStudent = funded(1000)
        val deal = UUID.randomUUID()
        val order = UUID.randomUUID()
        val approval = refund(UUID.randomUUID(), deal, refundStudent)
        assertEquals(HoldResult.PLACED, hold(order, orderStudent, 600, deal))
        sendAndWait(envelope(approval), consumer = SupportConsumer.CONSUMER)
        assertEquals(1, parked(approval))

        val refundAccount = accounts.ensure(AccountRef.studentAvailable(refundStudent))
        val orderAccount = accounts.ensure(AccountRef.studentAvailable(orderStudent))
        lateinit var closing: CompletableFuture<Void>
        try {
            tx.executeWithoutResult {
                accounts.lockStudent(refundStudent)
                closing = CompletableFuture.runAsync {
                    tx.executeWithoutResult {
                        if (confirm) {
                            settlement.settle(DealConfirmed(deal, UUID.randomUUID(), 500, 1000, listOf(OrderRef(order, orderStudent))), UUID.randomUUID())
                        } else {
                            releases.dealClosed(deal, HoldReleasedReason.DEAL_FAILED, "FAILED", UUID.randomUUID())
                        }
                    }
                }
                // The closing transaction has reached the blocked refund account. It must not hold the later account yet.
                await atMost Duration.ofSeconds(4) untilAsserted {
                    assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.processlist WHERE ID <> CONNECTION_ID() " +
                            "AND INFO LIKE 'SELECT id, balance_cents FROM ledger_account WHERE id IN (%' AND INFO REGEXP ?",
                        Int::class.java, "[^0-9]$refundAccount([^0-9]|$)",
                    ))
                }
                assertEquals(orderAccount, jdbc.queryForObject(
                    "SELECT id FROM ledger_account WHERE id = ? FOR UPDATE NOWAIT", Long::class.java, orderAccount,
                ))
            }
        } finally {
            closing.get(10, SECONDS)
        }

        assertEquals(0, parked(approval))
        assertEquals("NEVER_CHARGED", events("RefundRefused", deal).single()["reason"].asText())
        assertBooksBalance()
    }

    @Test
    fun `a refund with no hold for a deal already decided is refused NEVER_CHARGED at once`() {
        val student = funded(1000)
        val deal = UUID.randomUUID()
        sendAndWait(envelope(DealCancelled(deal, CancelReason.DECLINED)))

        val noHold = refund(UUID.randomUUID(), deal, student)
        sendAndWait(envelope(noHold), consumer = SupportConsumer.CONSUMER)

        assertEquals(0, parked(noHold))
        assertEquals("NEVER_CHARGED", events("RefundRefused", deal).single()["reason"].asText())
    }
}
