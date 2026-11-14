package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.service.platform.problem
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * [LED-21] - Outages: MySQL unreachable answers 503 UNAVAILABLE on every API call, not the platform's 500 (DEC E10).
 * Only the "can't reach the database" exceptions: a bug such as bad SQL stays a 500. Runs before the platform's catch-all.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class DatabaseUnavailableHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(
        DataAccessResourceFailureException::class,
        TransientDataAccessException::class,
        RecoverableDataAccessException::class,
        CannotCreateTransactionException::class,
    )
    fun unavailable(e: Exception): ResponseEntity<ProblemDetail> {
        log.warn("Database unavailable, answered 503: {}", e.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "The Ledger is briefly unavailable; try again"))
    }
}
