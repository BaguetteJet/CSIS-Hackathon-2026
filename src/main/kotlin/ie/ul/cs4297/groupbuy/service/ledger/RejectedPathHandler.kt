package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.service.platform.problem
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.tomcat.util.buf.EncodedSolidusHandling
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.security.web.firewall.RequestRejectedHandler
import org.springframework.stereotype.Component

/**
 * [LED-12] - Desk balance; [LED-19] - Bad input: a path Spring Security's firewall refuses (`;`, an encoded `/` or `..`)
 * answers like any unknown ID, 404 NOT_FOUND, or 401 first when it carries no token (DEC E1, E2). Its default is 400.
 * The firewall still refuses the request, so `..;/internal/` never reaches the hold call (EDGE-CASES Q14).
 */
@Component
class RejectedPathHandler(private val mapper: ObjectMapper) : RequestRejectedHandler {

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, e: RequestRejectedException) {
        val signedIn = request.getHeader(HttpHeaders.AUTHORIZATION) != null
        val body = if (signedIn) {
            problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such path")
        } else {
            problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in again on the dev sign-in page: the token is missing or has expired")
        }
        response.status = body.status
        response.characterEncoding = "UTF-8"
        response.contentType = "application/problem+json"
        response.writer.write(mapper.writeValueAsString(body))
    }
}

/** Tomcat refuses an encoded `/` itself with an HTML 400; passed through still encoded, the firewall refuses it and [RejectedPathHandler] answers. */
@Component
class EncodedSlashToFirewall : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    override fun customize(factory: TomcatServletWebServerFactory) {
        factory.addConnectorCustomizers({ it.encodedSolidusHandling = EncodedSolidusHandling.PASS_THROUGH.value })
    }
}
