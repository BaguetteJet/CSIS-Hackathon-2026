package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.api.HoldRequest
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestTokens
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Item 2 review: migration, business-key dedupe, rollback, /me rules. Holds are in HoldsTest. */
class LedgerCoreTest : ContainersTest() {

    @LocalServerPort var port = 0
    @Autowired lateinit var topUpStore: TopUpStore
    @Autowired lateinit var holds: Holds
    @Autowired lateinit var accounts: LedgerAccounts
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var flyway: Flyway

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
        assertTrue(versions.containsAll(listOf("1", "2", "3", "4", "5", "6")), versions.toString())
        assertEquals(2, count("SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'UNION'"))
        assertEquals(0L, accounts.sumOfAllAccounts())
    }

    @Test
    fun `migrations get their own connection with no socket timeout, the pool's give up after 5 s`() {
        // EDGE-CASES Q11 and Q18: a frozen MySQL fails a call in 5 s, but a long ALTER at start-up is left to finish.
        dataSource.connection.use { assertEquals(5_000, it.networkTimeout) }
        flyway.configuration.dataSource.connection.use { assertEquals(0, it.networkTimeout) }
    }

    @Test
    fun `a duplicate topUpId credits once and the first amount wins`() {
        val student = UUID.randomUUID()
        val topUpId = UUID.randomUUID()

        val first = tx.execute { topUpStore.creditTopUp(topUpId, student, 2_000) }!!
        assertTrue(first.newlyApplied)
        assertEquals(2_000L, first.availableCents)
        assertEquals(0L, first.heldCents)

        val repeat = tx.execute { topUpStore.creditTopUp(topUpId, student, 9_000) }!!
        assertFalse(repeat.newlyApplied)
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
    fun `a rolled back transaction leaves no movement and no balance change`() {
        val student = UUID.randomUUID()
        val topUpId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val dealId = UUID.randomUUID()

        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                topUpStore.creditTopUp(topUpId, student, 2_000)
                assertEquals(HoldResult.PLACED, holds.place(HoldRequest(orderId, student, dealId, Instant.parse("2026-11-14T12:00:00Z"), 600)).result)
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
            "SELECT COUNT(*) FROM hold_decision WHERE order_id = ?",
            orderId.toString(),
        ))
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_event WHERE event_key = ?", student.toString()))
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

        tx.execute { topUpStore.creditTopUp(UUID.randomUUID(), student, 2_000) }!!
        val funded = call("GET", "/api/ledger/me", TestTokens.student(student))
        assertEquals(200, funded.statusCode(), funded.body())
        assertEquals(2_000L, mapper.readTree(funded.body())["availableCents"].asLong())
        assertEquals(0L, mapper.readTree(funded.body())["heldCents"].asLong())
    }

    @Test
    fun `top-ups for different students never wait on the Union cash account`() {
        // One top-up keeps its transaction open; another student's top-up must not queue behind it.
        val credited = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val slow = Executors.newSingleThreadExecutor().submit {
            tx.executeWithoutResult {
                topUpStore.creditTopUp(UUID.randomUUID(), UUID.randomUUID(), 1_000)
                credited.countDown()
                finish.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(credited.await(10, TimeUnit.SECONDS))
            val quick = TransactionTemplate(transactions).apply { timeout = 2 }
            val started = System.nanoTime()
            assertTrue(quick.execute { topUpStore.creditTopUp(UUID.randomUUID(), UUID.randomUUID(), 2_000) }!!.newlyApplied)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(millis < 1_000, "the second top-up waited $millis ms")
        } finally {
            finish.countDown()
            slow.get(10, TimeUnit.SECONDS)
        }
        assertEquals(0L, accounts.sumOfAllAccounts())
    }
}
