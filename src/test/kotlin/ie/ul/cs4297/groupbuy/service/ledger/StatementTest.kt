package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.support.TestTokens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

// Item 7, LED-11 and LED-18: the student's own statement, newest first, netting to availableCents.
class StatementTest : LedgerFlowTest() {

    @LocalServerPort var port = 0
    @Autowired lateinit var postings: LedgerPostingService

    private val http = HttpClient.newHttpClient()

    private fun entries(token: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/api/ledger/me/entries")).GET()
        if (token != null) request.header("Authorization", "Bearer $token")
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `top-up, hold and settlement show newest first and net to available`() {
        val student = funded(5000)
        val deal = UUID.randomUUID()
        val order = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 600, deal))
        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 475, 1000, listOf(OrderRef(order, student)))))

        val response = entries(TestTokens.student(student))

        assertEquals(200, response.statusCode(), response.body())
        val body = mapper.readTree(response.body()).toList()
        assertEquals(listOf("RETURNED", "HOLD", "TOP_UP"), body.map { it["kind"].asText() })
        assertEquals(listOf(125L, 600L, 5000L), body.map { it["amountCents"].asLong() })
        assertEquals(listOf(true, false, true), body.map { it["credit"].asBoolean() })
        assertEquals("Paid €4.75, €1.25 back in your Account", body[0]["description"].asText())
        assertEquals(order.toString(), body[1]["orderId"].asText())

        var net = 0L
        for (entry in body) {
            if (entry["credit"].asBoolean()) net += entry["amountCents"].asLong() else net -= entry["amountCents"].asLong()
        }
        assertEquals(accounts.balance(student).availableCents, net)
    }

    @Test
    fun `a student with no account gets an empty list`() {
        val response = entries(TestTokens.student(UUID.randomUUID()))

        assertEquals(200, response.statusCode(), response.body())
        assertEquals("[]", response.body())
    }

    @Test
    fun `only the token's own student's entries are returned`() {
        val mine = funded(1000)
        funded(2000)

        val body = mapper.readTree(entries(TestTokens.student(mine)).body()).toList()

        assertEquals(listOf(1000L), body.map { it["amountCents"].asLong() })
    }

    @Test
    fun `entries at the same time show the later posting first`() {
        val student = funded(100)
        tx.execute { topUps.creditTopUp(UUID.randomUUID(), student, 200) }

        val body = mapper.readTree(entries(TestTokens.student(student)).body()).toList()

        assertEquals(body[0]["at"].asText(), body[1]["at"].asText(), "the test clock stands still, so both share a time")
        assertEquals(listOf(200L, 100L), body.map { it["amountCents"].asLong() })
    }

    @Test
    fun `at most the 100 newest entries`() {
        val student = funded(1)
        for (cents in 2L..105L) {
            tx.execute { topUps.creditTopUp(UUID.randomUUID(), student, cents) }
        }

        val body = mapper.readTree(entries(TestTokens.student(student)).body()).toList()

        assertEquals(100, body.size)
        assertEquals(105L, body.first()["amountCents"].asLong())
        assertEquals(6L, body.last()["amountCents"].asLong())
    }

    @Test
    fun `descriptions use student wording, never hold or wallet`() {
        val student = funded(5000)
        val deal = UUID.randomUUID()
        val left = UUID.randomUUID()
        val charged = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(left, student, 600, deal))
        sendAndWait(envelope(OrderWithdrawn(left, deal, student)))
        assertEquals(HoldResult.PLACED, hold(charged, student, 600, deal))
        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 475, 1000, listOf(OrderRef(charged, student)))))

        val body = mapper.readTree(entries(TestTokens.student(student)).body()).toList()
        val descriptions = body.map { it["description"].asText() } + HoldReleasedReason.entries.map { Releases.wording(it) }

        assertEquals(setOf("TOP_UP", "HOLD", "RELEASE", "RETURNED"), body.map { it["kind"].asText() }.toSet())
        for (description in descriptions) {
            val words = description.lowercase()
            assertFalse("hold" in words || "wallet" in words, "not student wording: $description")
        }
    }

    @Test
    fun `a cash-out reserve is a debit`() {
        val student = funded(1000)
        // Cash-outs are item 8; this posts the same lines its reserve will, to pin the statement's side.
        tx.execute {
            postings.post(MovementRef(MovementType.WITHDRAWAL, UUID.randomUUID()), listOf(
                PostingLine(AccountRef.studentAvailable(student), -300, EntryDetails(LedgerEntryKind.WITHDRAWAL, "Cash-out at the desk")),
                PostingLine(AccountRef.studentHeld(student), 300),
            ))
        }

        val newest = mapper.readTree(entries(TestTokens.student(student)).body()).first()

        assertEquals("WITHDRAWAL", newest["kind"].asText())
        assertEquals(300L, newest["amountCents"].asLong())
        assertEquals(false, newest["credit"].asBoolean())
    }

    @Test
    fun `a settlement that returns nothing adds no entry`() {
        val student = funded(5000)
        val deal = UUID.randomUUID()
        val order = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, hold(order, student, 475, deal))
        sendAndWait(envelope(DealConfirmed(deal, UUID.randomUUID(), 475, 1000, listOf(OrderRef(order, student)))))

        val body = mapper.readTree(entries(TestTokens.student(student)).body()).toList()

        assertEquals(listOf("HOLD", "TOP_UP"), body.map { it["kind"].asText() })
        assertEquals(4525L, accounts.balance(student).availableCents)
    }

    @Test
    fun `no token is 401, then a role other than student is 403`() {
        val noToken = entries(null)
        assertEquals(401, noToken.statusCode(), noToken.body())

        val finance = entries(TestTokens.union(Role.UNION_FINANCE))
        assertEquals(403, finance.statusCode(), finance.body())
    }
}
