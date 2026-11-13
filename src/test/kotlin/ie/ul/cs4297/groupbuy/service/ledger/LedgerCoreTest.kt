package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestTokens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/**
 * The Item 2 review: fresh migration, one movement per business key, rollback agreement, and
 * the /me role/balance rules. Needs Docker or a working Podman socket:
 *   ./gradlew containerTest --tests 'ie.ul.cs4297.groupbuy.service.ledger.LedgerCoreTest'
 */
class LedgerCoreTest : ContainersTest() {

    @LocalServerPort var port = 0
    @Autowired lateinit var topUpStore: TopUpStore
    @Autowired lateinit var holdStore: HoldStore
    @Autowired lateinit var accounts: LedgerAccounts
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var mapper: ObjectMapper

    private val http = HttpClient.newHttpClient()

    private fun call(method: String, path: String, token: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
        token?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun count(sql: String, vararg args: Any?): Int =
        jdbc.queryForObject(sql, Int::class.java, *args)!!

    @Test
    fun `a fresh database has the ledger schema and the Union accounts`() {
        val versions = jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
            String::class.java,
        )
        assertTrue(versions.containsAll(listOf("1", "2", "3", "4")), versions.toString())
        assertEquals(2, count("SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'UNION'"))
        assertEquals(0L, accounts.sumOfAllAccounts())
    }

    @Test
    fun `a duplicate topUpId credits once and the first amount wins`() {
        val student = UUID.randomUUID()
        val topUpId = UUID.randomUUID()

        val first = tx.execute { topUpStore.record(topUpId, student, 2_000) }!!
        assertTrue(first.posted)
        assertEquals(2_000L, first.availableCents)
        assertEquals(0L, first.heldCents)

        val repeat = tx.execute { topUpStore.record(topUpId, student, 9_000) }!!
        assertFalse(repeat.posted)
        assertEquals(2_000L, repeat.availableCents)
        assertEquals(0L, repeat.heldCents)

        assertEquals(1, count(
            "SELECT COUNT(*) FROM ledger_movement WHERE reference_type = 'TOP_UP' AND reference_id = ?",
            topUpId.toString(),
        ))
        assertEquals(2, count(
            "SELECT COUNT(*) FROM ledger_posting p JOIN ledger_movement m ON p.movement_id = m.id WHERE m.reference_id = ?",
            topUpId.toString(),
        ))
        assertEquals(0L, accounts.sumOfAllAccounts())
    }

    @Test
    fun `a hold is decided once and repeats return the first answer`() {
        val student = UUID.randomUUID()
        val dealId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        tx.execute { topUpStore.record(UUID.randomUUID(), student, 2_000) }!!

        val first = tx.execute { holdStore.reserve(orderId, dealId, student, 600) }!!
        assertEquals(HoldResult.PLACED, first.result)
        assertEquals(1_400L, first.availableCents)
        assertEquals(600L, first.heldCents)
        assertTrue(first.first)

        val repeat = tx.execute { holdStore.reserve(orderId, dealId, UUID.randomUUID(), 9_999) }!!
        assertEquals(HoldResult.PLACED, repeat.result)
        assertEquals(1_400L, repeat.availableCents)
        assertEquals(600L, repeat.heldCents)
        assertFalse(repeat.first)

        val insufficient = tx.execute { holdStore.reserve(UUID.randomUUID(), dealId, student, 5_000) }!!
        assertEquals(HoldResult.INSUFFICIENT_FUNDS, insufficient.result)
        assertEquals(1_400L, insufficient.availableCents)
        assertEquals(600L, insufficient.heldCents)
        assertTrue(insufficient.first)

        val refusedOrder = UUID.randomUUID()
        val refused = tx.execute { holdStore.refuse(refusedOrder, dealId, student, 600, HoldResult.DEAL_CLOSED) }!!
        assertEquals(HoldResult.DEAL_CLOSED, refused.result)
        assertTrue(refused.first)
        assertEquals(HoldResult.DEAL_CLOSED, holdStore.first(refusedOrder)!!.result)

        val refusedRepeat = tx.execute { holdStore.reserve(refusedOrder, dealId, student, 600) }!!
        assertEquals(HoldResult.DEAL_CLOSED, refusedRepeat.result)
        assertFalse(refusedRepeat.first)

        assertEquals(1, count(
            "SELECT COUNT(*) FROM ledger_movement WHERE reference_type = 'HOLD' AND reference_id = ?",
            orderId.toString(),
        ))
        assertEquals(3, count(
            "SELECT COUNT(*) FROM ledger_hold_decision WHERE student_id = ?",
            student.toString(),
        ))
        assertEquals(0L, accounts.sumOfAllAccounts())
    }

    @Test
    fun `a rolled back transaction leaves no movement and no balance change`() {
        val student = UUID.randomUUID()
        val topUpId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val dealId = UUID.randomUUID()

        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                topUpStore.record(topUpId, student, 2_000)
                holdStore.reserve(orderId, dealId, student, 600)
                throw IllegalStateException("rollback")
            }
        }

        assertEquals(0L, accounts.balance(student).availableCents)
        assertEquals(0L, accounts.balance(student).heldCents)
        assertEquals(0, count(
            "SELECT COUNT(*) FROM ledger_movement WHERE reference_id IN (?, ?)",
            topUpId.toString(), orderId.toString(),
        ))
        assertEquals(0, count(
            "SELECT COUNT(*) FROM ledger_hold_decision WHERE order_id = ?",
            orderId.toString(),
        ))
        assertEquals(0L, accounts.sumOfAllAccounts())
    }

    @Test
    fun `me needs a student token and reads the committed balance`() {
        val student = UUID.randomUUID()

        val noToken = call("GET", "/api/ledger/me")
        assertEquals(401, noToken.statusCode(), noToken.body())

        val wrongRole = call("GET", "/api/ledger/me", TestTokens.union(Role.UNION_FINANCE))
        assertEquals(403, wrongRole.statusCode(), wrongRole.body())

        val empty = call("GET", "/api/ledger/me", TestTokens.student(student))
        assertEquals(200, empty.statusCode(), empty.body())
        assertEquals(0L, mapper.readTree(empty.body())["availableCents"].asLong())
        assertEquals(0L, mapper.readTree(empty.body())["heldCents"].asLong())

        tx.execute { topUpStore.record(UUID.randomUUID(), student, 2_000) }!!
        val funded = call("GET", "/api/ledger/me", TestTokens.student(student))
        assertEquals(200, funded.statusCode(), funded.body())
        assertEquals(2_000L, mapper.readTree(funded.body())["availableCents"].asLong())
        assertEquals(0L, mapper.readTree(funded.body())["heldCents"].asLong())
    }
}
