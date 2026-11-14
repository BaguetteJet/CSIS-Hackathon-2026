package ie.ul.cs4297.groupbuy.service.ledger

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AuthenticationServiceException

// LED-18: what Spring Security throws when it can't fetch the sign-in key becomes a 503, and nothing else changes.
class SignInUnavailableTest {

    private val mapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>() // the app's mapper writes `code` flat
    private val filter = SignInUnavailableFilter(mapper)

    private fun run(chain: FilterChain): MockHttpServletResponse =
        MockHttpServletResponse().also { filter.doFilter(MockHttpServletRequest("GET", "/api/ledger/me"), it, chain) }

    @Test
    fun `sign-in key unreachable is 503 UNAVAILABLE`() {
        val response = run { _, _ -> throw AuthenticationServiceException("Couldn't retrieve remote JWK set") }
        assertEquals(503, response.status)
        assertEquals("application/problem+json", response.contentType?.substringBefore(';'))
        assertEquals("UNAVAILABLE", mapper.readTree(response.contentAsString)["code"].asText())
    }

    @Test
    fun `other errors pass through, and a normal request is untouched`() {
        assertThrows<IllegalStateException> { run { _, _ -> throw IllegalStateException("boom") } }
        assertEquals(204, run { _, res -> (res as MockHttpServletResponse).status = 204 }.status)
    }
}
