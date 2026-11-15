package ie.ul.cs4297.groupbuy.service.ledger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLTransientConnectionException

/** EDGE-CASES Q12: MySQL unreachable waits up to 15 min; anything else keeps the platform's 5 retries, then ledger.dlt. */
class DatabaseOutageRetryTest {

    private fun failed(cause: Throwable) = ListenerExecutionFailedException("Listener failed", cause)

    @Test
    fun `MySQL unreachable, however it is wrapped, waits every 10 s for 15 min`() {
        val unreachable = listOf(
            CannotCreateTransactionException("no connection", SQLTransientConnectionException("HikariPool-1 - Connection is not available")),
            RecoverableDataAccessException("Communications link failure"),
            TransactionSystemException("Could not commit JDBC transaction").apply { initCause(SQLNonTransientConnectionException("No operations allowed after connection closed")) },
        )
        for (e in unreachable) {
            assertEquals(DatabaseOutageRetry.WHILE_DATABASE_DOWN, DatabaseOutageRetry.backOffFor(failed(e)), e.toString())
        }
        assertEquals(10_000L, DatabaseOutageRetry.WHILE_DATABASE_DOWN.interval)
        assertEquals(90L, DatabaseOutageRetry.WHILE_DATABASE_DOWN.maxAttempts)
    }

    @Test
    fun `a bug keeps the platform's retries`() {
        for (e in listOf(IllegalStateException("a hold in deal x was not open"), DataIntegrityViolationException("Duplicate entry"), RuntimeException())) {
            assertNull(DatabaseOutageRetry.backOffFor(failed(e)), e.toString())
        }
    }
}
