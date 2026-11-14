package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.JsonNode
import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalCompleted
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalFailed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import ie.ul.cs4297.groupbuy.contracts.testkit.assertMatchesContract
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [LED-15] - Reserve a cash-out
 * [LED-16] - Pay out or return a cash-out
 * [LED-17] - Restaurant payouts
 * Cash-outs and restaurant payouts, through the payments.v1 listener.
 */
class WithdrawalsTest : LedgerFlowTest() {

    @Autowired lateinit var withdrawals: Withdrawals

    private val staff = UUID.randomUUID()

    /** Sends each envelope to payments.v1 and waits until PaymentsConsumer has processed all of them. */
    private fun pay(vararg envelopes: Envelope<ContractEvent>) {
        envelopes.forEach { send(it) }
        await atMost Duration.ofSeconds(30) untilAsserted {
            envelopes.map { it.eventId }.distinct().forEach { id ->
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                    Int::class.java, PaymentsConsumer.CONSUMER, id.toString()), "processed $id")
            }
        }
    }

    /** The payloads of [type] events whose payload [field] is [id], oldest first; each one checked against the contract. */
    private fun published(type: String, field: String, id: UUID): List<JsonNode> = jdbc.queryForList(
        "SELECT message_json FROM outbox_event WHERE event_type = ? AND JSON_UNQUOTE(JSON_EXTRACT(message_json, CONCAT('$.payload.', ?))) = ? ORDER BY id",
        String::class.java, type, field, id.toString(),
    ).map { json -> assertMatchesContract(json); mapper.readTree(json)["payload"] }

    private fun cashOutEvents(withdrawal: UUID): List<String> = jdbc.queryForList(
        "SELECT event_type FROM outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(message_json, '$.payload.withdrawalId')) = ? ORDER BY id",
        String::class.java, withdrawal.toString(),
    )

    private fun stateOfWithdrawal(withdrawal: UUID): String? =
        jdbc.queryForList("SELECT state FROM withdrawal WHERE withdrawal_id = ?", String::class.java, withdrawal.toString()).singleOrNull()

    private fun unionCash(): Long = accounts.postedBalance(AccountRef.UNION_CASH)

    private fun requested(withdrawal: UUID, student: UUID, cents: Long) = envelope(WithdrawalRequested(withdrawal, student, cents, staff))

    /** The contracts' example student: €20 topped up at the desk, €6 set aside for an order, so €14 available. */
    private fun studentWithAnOrder(): UUID = funded(2000).also { student ->
        assertEquals(HoldResult.PLACED, hold(UUID.randomUUID(), student, 600, UUID.randomUUID()))
    }

    @Test
    fun `a cash-out with enough money is set aside, and WithdrawalDebited carries the balance after`() {
        val student = studentWithAnOrder()
        val withdrawal = UUID.randomUUID()

        pay(requested(withdrawal, student, 1000))

        assertEquals(400L to 1600L, balanceOf(student))
        assertEquals("RESERVED", stateOfWithdrawal(withdrawal))
        assertEquals(1, movements(MovementType.WITHDRAWAL, withdrawal))
        val debited = published("WithdrawalDebited", "withdrawalId", withdrawal).single()
        assertEquals(listOf(1000, 400, 1600), listOf("amountCents", "availableCents", "heldCents").map { debited[it].asInt() })
        assertEquals("WITHDRAWAL" to "Cash-out at the desk", statement(student).last())
        assertBooksBalance()
    }

    @Test
    fun `a cash-out above the available money is refused, nothing moves, and the refusal is recorded`() {
        val student = studentWithAnOrder()
        val withdrawal = UUID.randomUUID()

        // All €20 they topped up, but €6 of it is set aside for the order.
        pay(requested(withdrawal, student, 2000))

        assertEquals(1400L to 600L, balanceOf(student))
        assertEquals("REFUSED", stateOfWithdrawal(withdrawal))
        assertEquals(0, movements(MovementType.WITHDRAWAL, withdrawal))
        val refused = published("WithdrawalRefused", "withdrawalId", withdrawal).single()
        assertEquals("INSUFFICIENT_FUNDS", refused["reason"].asText())
        assertEquals(1400 to 600, refused["availableCents"].asInt() to refused["heldCents"].asInt())
        assertBooksBalance()
    }

    @Test
    fun `the same withdrawalId again, as the same or a new envelope, is set aside once`() {
        val student = studentWithAnOrder()
        val withdrawal = UUID.randomUUID()
        val first = requested(withdrawal, student, 1000)

        pay(first, first, requested(withdrawal, student, 1400))

        assertEquals(400L to 1600L, balanceOf(student))
        assertEquals(1, movements(MovementType.WITHDRAWAL, withdrawal))
        assertEquals(listOf("WithdrawalDebited"), cashOutEvents(withdrawal))
        assertBooksBalance()
    }

    @Test
    fun `completed pays the reserved amount out to Union cash once, and a later failed changes nothing`() {
        val student = studentWithAnOrder()
        val withdrawal = UUID.randomUUID()
        pay(requested(withdrawal, student, 1000))
        val cashBefore = unionCash()

        // The completed event carries another amount: the reserved €10 wins.
        pay(envelope(WithdrawalCompleted(withdrawal, student, 2000, staff)))
        pay(envelope(WithdrawalCompleted(withdrawal, student, 1000, staff)), envelope(WithdrawalFailed(withdrawal, student, 1000, "Explained in a few words")))

        assertEquals(400L to 600L, balanceOf(student))
        assertEquals("PAID_OUT", stateOfWithdrawal(withdrawal))
        assertEquals(1000L, unionCash() - cashBefore)
        assertEquals(1, movements(MovementType.WITHDRAWAL_PAYOUT, withdrawal))
        assertEquals(0, movements(MovementType.WITHDRAWAL_RETURN, withdrawal))
        val paid = published("WithdrawalPaidOut", "withdrawalId", withdrawal).single()
        assertEquals(listOf(1000, 400, 600), listOf("amountCents", "availableCents", "heldCents").map { paid[it].asInt() })
        assertEquals(listOf("WithdrawalDebited", "WithdrawalPaidOut"), cashOutEvents(withdrawal))
        assertBooksBalance()
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `an amount on completed or failed that differs from the reserved one is logged, and the reserved amount is used`(output: CapturedOutput) {
        val paidOut = UUID.randomUUID()
        val returned = UUID.randomUUID()
        val student = funded(5000)
        pay(requested(paidOut, student, 1000), requested(returned, student, 1000))

        pay(envelope(WithdrawalCompleted(paidOut, student, 2000, staff)), envelope(WithdrawalFailed(returned, student, 700, "Explained in a few words")))

        assertEquals(4000L to 0L, balanceOf(student))
        assertTrue(output.out.contains("Cash-out $paidOut: WithdrawalCompleted says 2000 cents, 1000 were reserved; the reserved amount is used"), "completed difference logged")
        assertTrue(output.out.contains("Cash-out $returned: WithdrawalFailed says 700 cents, 1000 were reserved; the reserved amount is used"), "failed difference logged")
        assertBooksBalance()
    }

    @Test
    fun `failed returns the money to available once, and a later completed changes nothing`() {
        val student = studentWithAnOrder()
        val withdrawal = UUID.randomUUID()
        pay(requested(withdrawal, student, 1000))
        val cashBefore = unionCash()

        pay(envelope(WithdrawalFailed(withdrawal, student, 1000, "Explained in a few words")))
        pay(envelope(WithdrawalCompleted(withdrawal, student, 1000, staff)))

        assertEquals(1400L to 600L, balanceOf(student))
        assertEquals("RETURNED", stateOfWithdrawal(withdrawal))
        assertEquals(0L, unionCash() - cashBefore)
        assertEquals(1, movements(MovementType.WITHDRAWAL_RETURN, withdrawal))
        assertEquals(0, movements(MovementType.WITHDRAWAL_PAYOUT, withdrawal))
        val returned = published("WithdrawalReturned", "withdrawalId", withdrawal).single()
        assertEquals(listOf(1000, 1400, 600), listOf("amountCents", "availableCents", "heldCents").map { returned[it].asInt() })
        assertEquals("WITHDRAWAL_RETURNED" to "Cash-out cancelled: the money is back in your Account", statement(student).last())
        assertBooksBalance()
    }

    @Test
    fun `completed or failed for a cash-out that was refused or never seen moves nothing`() {
        val student = studentWithAnOrder()
        val refused = UUID.randomUUID()
        val unknown = UUID.randomUUID()
        pay(requested(refused, student, 2000))

        pay(
            envelope(WithdrawalCompleted(refused, student, 2000, staff)),
            envelope(WithdrawalCompleted(unknown, student, 1000, staff)),
            envelope(WithdrawalFailed(unknown, student, 1000, "Explained in a few words")),
        )

        assertEquals(1400L to 600L, balanceOf(student))
        assertEquals("REFUSED", stateOfWithdrawal(refused))
        assertEquals(null, stateOfWithdrawal(unknown))
        assertEquals(listOf("WithdrawalRefused"), cashOutEvents(refused))
        assertEquals(emptyList<String>(), cashOutEvents(unknown))
        assertBooksBalance()
    }

    @Test
    fun `a cash-out racing an order for the same money has one winner`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(5) {
                // €14 available: a €10 cash-out and a second €6 order can't both fit.
                val student = studentWithAnOrder()
                val withdrawal = UUID.randomUUID()
                val order = UUID.randomUUID()
                val start = CountDownLatch(1)
                val cashOut = pool.submit<Unit> { start.await(); tx.execute { withdrawals.requested(WithdrawalRequested(withdrawal, student, 1000, staff), UUID.randomUUID()) } }
                val hold = pool.submit<HoldResult> { start.await(); hold(order, student, 600, UUID.randomUUID()) }
                start.countDown()
                cashOut.get(10, TimeUnit.SECONDS)
                val holdResult = hold.get(10, TimeUnit.SECONDS)

                val cashOutWon = stateOfWithdrawal(withdrawal) == "RESERVED"
                assertTrue(cashOutWon != (holdResult == HoldResult.PLACED), "exactly one wins: cash-out ${stateOfWithdrawal(withdrawal)}, hold $holdResult")
                assertEquals(if (cashOutWon) 400L to 1600L else 800L to 1200L, balanceOf(student))
            }
        } finally {
            pool.shutdownNow()
        }
        assertBooksBalance()
    }

    @Test
    fun `a payout is taken from the restaurant's payable once, and may take it below zero`() {
        val restaurant = UUID.randomUUID()
        val payout = UUID.randomUUID()
        val cashBefore = unionCash()
        val first = envelope(PayoutConfirmed(payout, restaurant, 12960))

        pay(first, first, envelope(PayoutConfirmed(payout, restaurant, 14400)))

        assertEquals(-12960L, accounts.postedBalance(AccountRef.restaurantPayable(restaurant)))
        assertEquals(12960L, unionCash() - cashBefore)
        assertEquals(1, movements(MovementType.PAYOUT, payout))
        val debited = published("PayoutDebited", "payoutId", payout).single()
        assertEquals(restaurant.toString(), debited["restaurantId"].asText())
        assertEquals(12960, debited["amountCents"].asInt())
        assertBooksBalance()
    }
}
