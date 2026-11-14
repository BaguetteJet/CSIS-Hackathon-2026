package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.RefundApproved
import ie.ul.cs4297.groupbuy.contracts.events.RefundApprovedReason
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.service.platform.DueTimer
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
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID

/**
 * [LED-23] - Observability: parked refunds, the oldest one's age and dead letters on /actuator/prometheus.
 * [LED-24] - Operability and data: the daily check is scheduled for 02:00 Irish time, and finds a balance that drifted.
 */
class LedgerMetricsTest : LedgerFlowTest() {

    @Autowired lateinit var meters: MeterRegistry
    @Autowired lateinit var dailyCheck: DailyCheck
    @Autowired lateinit var timers: Timers

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
    fun `the next 2 am is today before 2 am, tomorrow after, and stays 2 am across the clock changes`() {
        assertEquals("2026-11-15T02:00Z[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-11-14T10:00:00Z")).toString())
        assertEquals("2026-11-14T02:00Z[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-11-14T01:59:00Z")).toString())
        assertEquals("2026-03-29T02:00+01:00[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-03-28T12:00:00Z")).toString())
        assertEquals("2026-10-25T02:00Z[Europe/Dublin]", DailyCheck.next(java.time.Instant.parse("2026-10-24T12:00:00Z")).toString())
    }
}
