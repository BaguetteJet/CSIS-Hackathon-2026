package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.service.platform.DueTimer
import ie.ul.cs4297.groupbuy.service.platform.TimerHandler
import ie.ul.cs4297.groupbuy.service.platform.Timers
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * [LED-24] - Operability and data (stretch): at 02:00 Irish time, check the books, item 9's invariants I1 to I5: postings and
 * each movement sum to zero, no student is below zero, each kept balance equals its postings, HELD equals the open holds and
 * reserved cash-outs, each hold's movements match its state, each settlement's totals match its charges.
 * Reports by log and a metric, publishes no event (DEC Ledger). The container tests run the same check after each flow.
 * A platform timer, so it fires once across both instances and follows the platform clock; each run schedules the next.
 */
@Component
class DailyCheck(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val timers: Timers,
    private val clock: Clock,
    meters: MeterRegistry,
) : TimerHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val type = "ledger-daily-check"

    // Only the instance that ran the check knows: the dashboard takes the max of both.
    private val problems = meters.gauge("ledger.daily.check.problems", AtomicLong(0))!!
    private val lastRun = meters.gauge("ledger.daily.check.last.run.seconds", AtomicLong(0))!!

    // Hourly too: a run that fails 10 times (MySQL down over 8 min at 02:00) never schedules the next, and the checks would stop.
    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    fun scheduleNext() {
        val at = next(clock.instant())
        timers.schedule(type, at.toLocalDate().toString(), at.toInstant())
    }

    override fun fire(timer: DueTimer) {
        val found = check()
        problems.set(found.size.toLong())
        lastRun.set(clock.instant().epochSecond)
        if (found.isEmpty()) log.info("Daily check {}: the books balance", timer.key)
        else log.error("Daily check {} found problems: {}", timer.key, found.joinToString("; "))
        scheduleNext()
    }

    /** Each problem in words; empty when the books balance. Each count is one statement, so it reads one consistent snapshot. */
    fun check(): List<String> {
        val found = mutableListOf<String>()
        val sum = accounts.sumOfAllAccounts()
        if (sum != 0L) found += "postings sum to $sum cents, not 0"
        for ((sql, problem) in CHECKS) {
            val count = jdbc.queryForObject(sql, Long::class.java)!!
            if (count > 0) found += "$count $problem"
        }
        return found
    }

    companion object {
        private val IRELAND: ZoneId = ZoneId.of("Europe/Dublin")

        /** Each check counts what breaks one invariant (item 9: I1 to I5). */
        private val CHECKS = listOf(
            "SELECT COUNT(*) FROM (SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents) <> 0) x"
                to "movements don't sum to 0",
            "SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'STUDENT' AND balance_cents < 0"
                to "student accounts below zero",
            "SELECT COUNT(*) FROM ledger_account a WHERE a.owner_type = 'STUDENT' " +
                "AND a.balance_cents <> (SELECT COALESCE(SUM(p.amount_cents), 0) FROM ledger_posting p WHERE p.account_id = a.id)"
                to "student balances differ from their postings",
            // Summed per student once, then joined: neither table has a student_id index, so a subquery per student grew as students x holds.
            "SELECT COUNT(*) FROM ledger_account a " +
                "LEFT JOIN (SELECT student_id, SUM(amount_cents) AS cents FROM hold_decision WHERE state = 'OPEN' GROUP BY student_id) h ON h.student_id = a.owner_id " +
                "LEFT JOIN (SELECT student_id, SUM(amount_cents) AS cents FROM withdrawal WHERE state = 'RESERVED' GROUP BY student_id) w ON w.student_id = a.owner_id " +
                "WHERE a.owner_type = 'STUDENT' AND a.purpose = 'HELD' AND a.balance_cents <> COALESCE(h.cents, 0) + COALESCE(w.cents, 0)"
                to "students' set-aside money differs from their open holds and reserved cash-outs",
            "SELECT COUNT(*) FROM hold_decision h WHERE " +
                "(SELECT COUNT(*) FROM ledger_movement m WHERE m.reference_type = 'HOLD' AND m.reference_id = h.order_id) <> IF(h.result = 'PLACED', 1, 0) " +
                "OR (SELECT COUNT(*) FROM ledger_movement m WHERE m.reference_type = 'RELEASE' AND m.reference_id = h.order_id) <> IF(h.state = 'RELEASED', 1, 0) " +
                "OR (SELECT COUNT(*) FROM ledger_movement m WHERE m.reference_type = 'ORDER_CHARGE' AND m.reference_id = h.order_id) <> IF(h.state = 'CHARGED', 1, 0)"
                to "holds whose movements don't match their state",
            "SELECT COUNT(*) FROM deal_settlement s WHERE " +
                "s.charged_count <> (SELECT COUNT(*) FROM order_charge c WHERE c.deal_id = s.deal_id) " +
                "OR s.total_charged_cents <> (SELECT COALESCE(SUM(c.price_cents), 0) FROM order_charge c WHERE c.deal_id = s.deal_id) " +
                "OR s.total_commission_cents <> (SELECT COALESCE(SUM(c.commission_cents), 0) FROM order_charge c WHERE c.deal_id = s.deal_id)"
                to "deal settlements whose totals differ from their charges",
        )

        /** The next 02:00 in Ireland after [now]; 02:00 exists on both clock-change days. */
        fun next(now: Instant): ZonedDateTime {
            val today = now.atZone(IRELAND).toLocalDate().atTime(LocalTime.of(2, 0)).atZone(IRELAND)
            return if (today.toInstant().isAfter(now)) today else today.plusDays(1)
        }
    }
}
