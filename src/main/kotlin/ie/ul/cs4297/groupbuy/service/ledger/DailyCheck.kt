package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.service.platform.DueTimer
import ie.ul.cs4297.groupbuy.service.platform.TimerHandler
import ie.ul.cs4297.groupbuy.service.platform.Timers
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * [LED-24] - Operability and data (stretch): at 02:00 Irish time, check the books: every posting sums to zero, no student
 * is below zero, each kept balance equals its postings. Reports by log and a metric, publishes no event (DEC Ledger).
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

    @EventListener(ApplicationReadyEvent::class)
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

    /** Each problem in words; empty when the books balance. */
    fun check(): List<String> {
        val found = mutableListOf<String>()
        val sum = accounts.sumOfAllAccounts()
        if (sum != 0L) found += "postings sum to $sum cents, not 0"
        val negative = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'STUDENT' AND balance_cents < 0", Long::class.java)!!
        if (negative > 0) found += "$negative student accounts below zero"
        val drifted = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_account a WHERE a.owner_type = 'STUDENT' " +
                "AND a.balance_cents <> (SELECT COALESCE(SUM(p.amount_cents), 0) FROM ledger_posting p WHERE p.account_id = a.id)",
            Long::class.java)!!
        if (drifted > 0) found += "$drifted student balances differ from their postings"
        return found
    }

    companion object {
        private val IRELAND: ZoneId = ZoneId.of("Europe/Dublin")

        /** The next 02:00 in Ireland after [now]; 02:00 exists on both clock-change days. */
        fun next(now: Instant): ZonedDateTime {
            val today = now.atZone(IRELAND).toLocalDate().atTime(LocalTime.of(2, 0)).atZone(IRELAND)
            return if (today.toInstant().isAfter(now)) today else today.plusDays(1)
        }
    }
}
