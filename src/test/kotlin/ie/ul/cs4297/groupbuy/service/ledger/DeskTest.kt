package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.support.TestTokens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

// [LED-12] - Desk balance; [LED-18] - Security: Union finance reads any student's balance at the desk (item 7).
class DeskTest : LedgerFlowTest() {

    @LocalServerPort var port = 0

    private val http = HttpClient.newHttpClient()
    private val finance = TestTokens.union(Role.UNION_FINANCE)

    private fun call(path: String, token: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        if (token != null) request.header("Authorization", "Bearer $token")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `finance sees the same balance as the student's own me`() {
        val student = funded(5000)
        assertEquals(HoldResult.PLACED, hold(UUID.randomUUID(), student, 600, UUID.randomUUID()))

        val desk = call("/api/ledger/students/$student", finance)
        val me = call("/api/ledger/me", TestTokens.student(student))

        assertEquals(200, desk.statusCode(), desk.body())
        val body = mapper.readTree(desk.body())
        assertEquals(student.toString(), body["studentId"].asText())
        assertEquals(4400L, body["availableCents"].asLong())
        assertEquals(600L, body["heldCents"].asLong())
        assertEquals(mapper.readTree(me.body()), body)
    }

    @Test
    fun `a well-formed id with no account answers zeros`() {
        val unknown = UUID.randomUUID()

        val response = call("/api/ledger/students/$unknown", finance)

        assertEquals(200, response.statusCode(), response.body())
        val body = mapper.readTree(response.body())
        assertEquals(unknown.toString(), body["studentId"].asText())
        assertEquals(0L, body["availableCents"].asLong())
        assertEquals(0L, body["heldCents"].asLong())
    }

    @Test
    fun `a malformed id answers 404 NOT_FOUND`() {
        // 1-2-3-4-5 is one UUID.fromString accepts, but the contract's pattern doesn't.
        for (id in listOf("not-a-uuid", "1-2-3-4-5", "12345678-1234-1234-1234-1234567890123")) {
            val response = call("/api/ledger/students/$id", finance)
            assertEquals(404, response.statusCode(), "$id: ${response.body()}")
            assertEquals("NOT_FOUND", mapper.readTree(response.body())["code"].asText(), id)
        }
    }

    @Test
    fun `no or expired token is 401, then any role but finance is 403 with no data`() {
        val student = funded(1000)
        val path = "/api/ledger/students/$student"

        assertEquals(401, call(path, null).statusCode())
        assertEquals(401, call(path, TestTokens.token(UUID.randomUUID(), Role.UNION_FINANCE, expiresIn = Duration.ofMinutes(-5))).statusCode())

        for (token in listOf(TestTokens.student(student), TestTokens.restaurantStaff(UUID.randomUUID()))) {
            val response = call(path, token)
            assertEquals(403, response.statusCode(), response.body())
            assertEquals("FORBIDDEN", mapper.readTree(response.body())["code"].asText())
        }
    }
}
