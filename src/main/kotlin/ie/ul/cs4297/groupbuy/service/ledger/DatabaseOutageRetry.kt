package ie.ul.cs4297.groupbuy.service.ledger

import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.FixedBackOff
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

/**
 * [LED-21] - Outages: while MySQL can't be reached, an event waits for it (every 10 s, up to 15 min) instead of
 * going to ledger.dlt after the platform's 5 retries (2.5 min). Any other failure keeps the platform's retries.
 * Set on the platform's error handler, which stays as it is (EDGE-CASES Q12).
 */
@Configuration
class DatabaseOutageRetry(errorHandler: DefaultErrorHandler) {

    init {
        errorHandler.setBackOffFunction { _, e -> backOffFor(e) }
    }

    companion object {
        val WHILE_DATABASE_DOWN = FixedBackOff(10_000, 90)

        /** The long back-off when [e] says MySQL is unreachable; null keeps the platform's. */
        fun backOffFor(e: Throwable): BackOff? = if (databaseUnreachable(e)) WHILE_DATABASE_DOWN else null

        // The cause chain, because the listener wraps the exception and a failed commit wraps the driver's.
        fun databaseUnreachable(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                when (cause) {
                    is CannotCreateTransactionException, is DataAccessResourceFailureException, is RecoverableDataAccessException,
                    is SQLTransientConnectionException, is SQLNonTransientConnectionException, is SQLRecoverableException -> return true
                }
                if (cause.cause === cause) return false
                cause = cause.cause
            }
            return false
        }
    }
}
