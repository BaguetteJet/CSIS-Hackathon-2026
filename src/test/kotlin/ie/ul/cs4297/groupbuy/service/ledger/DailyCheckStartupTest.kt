package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.service.platform.Timers
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.jdbc.CannotGetJdbcConnectionException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A ready listener that throws stops Spring Boot: MySQL slow at start-up must not take the instance down (the hourly run retries). */
class DailyCheckStartupTest {

    @Test
    fun `scheduling the next check at start-up survives MySQL being unreachable`() {
        val timers = mockk<Timers>()
        every { timers.schedule(any(), any(), any(), any()) } throws CannotGetJdbcConnectionException("Failed to obtain JDBC Connection")
        val check = DailyCheck(mockk(), mockk(), timers, Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC), SimpleMeterRegistry())

        assertDoesNotThrow { check.scheduleAtStartup() }
        verify(exactly = 1) { timers.schedule("ledger-daily-check", "2026-11-16", any(), any()) }
    }
}
