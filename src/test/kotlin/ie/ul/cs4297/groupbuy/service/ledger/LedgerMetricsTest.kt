package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.DealSettled
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundApprovedReason
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import ie.ul.cs4297.groupbuy.service.platform.DueTimer
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import ie.ul.cs4297.groupbuy.service.platform.Timers
import ie.ul.cs4297.groupbuy.service.support.TestClock
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID

/**
 * [LED-23] - Observability: parked refunds, the oldest one's age and dead letters on /actuator/prometheus.
 * [LED-24] - Operability and data: the daily check is scheduled for 02:00 Irish time, and finds a balance that drifted.
 */
class LedgerMetricsTest : LedgerFlowTest() {

    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var timers: Timers
    @Autowired lateinit var outbox: Outbox
    @Autowired lateinit var scheduled: ScheduledTaskHolder

    private fun gauge(name: String): Double = meters.get(name).gauge().value()

    @Test
    fun `a parked refund shows in the count, and its age passes 6 hours when it has waited that long`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val deal = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        val before = gauge("ledger.refunds.parked")

        val refund = RefundApproved(UUID.randomUUID(), order, deal, student, 200, RefundApprovedReason.COMPLAINT, "support001")
        val parked = envelope(refund)
        sendAndWait(parked, consumer = SupportConsumer.CONSUMER)
        jdbc.update("UPDATE parked_event SET parked_at = ? WHERE event_id = ?",
            Timestamp.from(TestClock.instant().minus(Duration.ofHours(7))), parked.eventId.toString())

        assertEquals(before + 1, gauge("ledger.refunds.parked"))
        assertTrue(gauge("ledger.refunds.parked.oldest") >= Duration.ofHours(7).seconds, "the oldest parked refund is over 6 h old")
    }

    @Test
    fun `a reserved cash-out shows in the count, and its age passes 6 hours when it has waited that long`() {
        val student = funded(1000)
        val before = gauge("ledger.withdrawals.reserved")
        val withdrawal = UUID.randomUUID()

        sendAndWait(envelope(WithdrawalRequested(withdrawal, student, 500, UUID.randomUUID())), consumer = PaymentsConsumer.CONSUMER)
        jdbc.update("UPDATE withdrawal SET decided_at = ? WHERE withdrawal_id = ?",
            Timestamp.from(TestClock.instant().minus(Duration.ofHours(7))), withdrawal.toString())

        assertEquals(before + 1, gauge("ledger.withdrawals.reserved"))
        assertTrue(gauge("ledger.withdrawals.reserved.oldest") >= Duration.ofHours(7).seconds, "the oldest reserved cash-out is over 6 h old")
    }

    @Test
    fun `an event left unpublished shows its age, and drops back to 0 once Kafka takes it`() {
        // Another holder takes the relay's lease, as if Kafka were unreachable: nothing is published meanwhile.
        jdbc.update("UPDATE outbox_lease SET holder = 'test', expires_at = UTC_TIMESTAMP(6) + INTERVAL 1 HOUR WHERE name = 'outbox-relay'")
        try {
            tx.executeWithoutResult { outbox.publish(DealSettled(UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0)) }
            jdbc.update("UPDATE outbox_event SET created_at = ? WHERE published_at IS NULL",
                Timestamp.from(TestClock.instant().minus(Duration.ofMinutes(10))))
            assertTrue(gauge("ledger.outbox.unpublished.oldest") >= Duration.ofMinutes(10).seconds, "the oldest unpublished event is over 5 min old")
        } finally {
            jdbc.update("UPDATE outbox_lease SET expires_at = '1970-01-02 00:00:00' WHERE name = 'outbox-relay'")
        }
        await atMost Duration.ofSeconds(30) untilAsserted { assertEquals(0.0, gauge("ledger.outbox.unpublished.oldest")) }
    }

    @Test
    fun `a dead-lettered message raises the dead-letter count`() {
        val before = gauge("ledger.dead.letters")

        send(envelope(TopUpRecorded(UUID.randomUUID(), UUID.randomUUID(), 0, UUID.randomUUID())))

        await atMost Duration.ofSeconds(30) untilAsserted { assertTrue(gauge("ledger.dead.letters") > before, "dead letters went up from $before") }
    }

    @Test
    fun `the daily check is scheduled for the next 2 am in Ireland`() {
        val next = DailyCheck.next(TestClock.instant())
        assertEquals("PENDING", timers.status(dailyCheck.type, next.toLocalDate().toString()))
        assertEquals(2, next.hour)
    }

    @Test
    fun `the daily check passes on balanced books, reports a drifted balance, and schedules the next day`() {
        val student = funded(1000)
        val firedFor = DailyCheck.next(TestClock.instant()).minusDays(1)
        tx.executeWithoutResult { dailyCheck.fire(DueTimer(dailyCheck.type, firedFor.toLocalDate().toString(), firedFor.toInstant(), null, 1)) }
        assertEquals(0.0, gauge("ledger.daily.check.problems"))
        assertEquals(TestClock.instant().epochSecond.toDouble(), gauge("ledger.daily.check.last.run.seconds"))

        // A kept balance changed behind the postings' back, then rolled back.
        val found = tx.execute { status ->
            jdbc.update("UPDATE ledger_account SET balance_cents = balance_cents + 1 WHERE owner_type = 'STUDENT' AND owner_id = ? AND purpose = 'AVAILABLE'", student.toString())
            dailyCheck.check().also { status.setRollbackOnly() }
        }!!
        assertEquals(listOf("1 student balances differ from their postings"), found)
    }

    @Test
    fun `the daily check names set-aside money with no open hold, a hold ended with no movement, and settlement totals with no charges`() {
        val order = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, funded(1000), 600, UUID.randomUUID()))

        // Both broken behind the postings' back, then rolled back.
        val found = tx.execute { status ->
            jdbc.update("UPDATE hold_decision SET state = 'RELEASED' WHERE order_id = ?", order.toString())
            jdbc.update("INSERT INTO deal_settlement (deal_id, restaurant_id, settled_at, charged_count, total_charged_cents) VALUES (?, ?, ?, 1, 475)",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), Timestamp.from(TestClock.instant()))
            dailyCheck.check().also { status.setRollbackOnly() }
        }!!
        assertEquals(listOf(
            "1 students' set-aside money differs from their open holds and reserved cash-outs",
            "1 holds whose movements don't match their state",
            "1 deal settlements whose totals differ from their charges",
        ), found)
        assertBooksBalance()
    }

    @Test
    fun `a failed day's check doesn't end the checks - the next day is scheduled every hour as well as at start-up`() {
        val hourly = scheduled.scheduledTasks.map { it.task }.filterIsInstance<FixedDelayTask>()
            .filter { it.runnable.toString().endsWith("DailyCheck.scheduleNext") }
        assertEquals(listOf(Duration.ofHours(1)), hourly.map { it.intervalDuration })
    }

    @Test
    fun `the next 2 am is today before 2 am, tomorrow after, and stays 2 am across the clock changes`() {
        assertEquals("2026-11-15T02:00Z[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-11-14T10:00:00Z")).toString())
        assertEquals("2026-11-14T02:00Z[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-11-14T01:59:00Z")).toString())
        assertEquals("2026-03-29T02:00+01:00[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-03-28T12:00:00Z")).toString())
        assertEquals("2026-10-25T02:00Z[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-10-24T12:00:00Z")).toString())
    }
}
