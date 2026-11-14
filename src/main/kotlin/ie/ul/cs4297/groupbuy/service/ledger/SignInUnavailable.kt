package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.service.platform.problem
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Sign-in key unreachable with none cached: answer 503 UNAVAILABLE, not 500 (LED-18, DEC E10).
 * Runs before Spring Security, which throws AuthenticationServiceException out of the chain and the starter leaves it as a 500.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SignInUnavailableFilter(private val mapper: ObjectMapper) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (e: Exception) {
            if (response.isCommitted || generateSequence<Throwable>(e) { it.cause }.none { it is AuthenticationServiceException }) throw e
            log.warn("Sign-in key unavailable, answered 503: {}", e.message)
            response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
            response.characterEncoding = "UTF-8"
            response.contentType = "application/problem+json"
            response.writer.write(mapper.writeValueAsString(problem(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", "Sign-in is briefly unavailable; try again")))
        }
    }
}
