package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

// [LED-03] - Place a hold: every answer within Orders' 2 s. The deadline is 300 ms here, so a slow decision is easy to make.
@TestPropertySource(properties = ["groupbuy.ledger.hold-deadline-ms=300"])
class HoldDeadlineTest : ContainersTest() {

    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var topUps: TopUpPosting
    @Autowired lateinit var accounts: LedgerAccounts
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var dataSource: DataSource

    private val http = HttpClient.newHttpClient()

    @Test
    fun `a decision slower than the deadline answers 503 on time, and a retry gets the decision once it commits`() {
        val student = UUID.randomUUID().also { tx.execute { _ -> topUps.creditTopUp(UUID.randomUUID(), it, 1000) } }
        val order = UUID.randomUUID()
        // A first call warms this fresh instance up, so the timing below measures the deadline, not start-up.
        assertEquals(200, hold(UUID.randomUUID(), UUID.randomUUID(), 1).statusCode())
        // Hold the student's lock for 700 ms: past the deadline, inside the decision's own 1 s transaction.
        val locked = CountDownLatch(1)
        val blocker = Executors.newSingleThreadExecutor().submit {
            dataSource.connection.use { c ->
                c.autoCommit = false
                c.prepareStatement("SELECT * FROM ledger_account WHERE owner_type = 'STUDENT' AND owner_id = '$student' FOR UPDATE").executeQuery()
                locked.countDown()
                Thread.sleep(700)
                c.rollback()
            }
        }
        locked.await(5, TimeUnit.SECONDS)

        val started = System.nanoTime()
        val answer = hold(order, student, 600)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(503, answer.statusCode(), answer.body())
        assertEquals("UNAVAILABLE", mapper.readTree(answer.body())["code"].asText())
        assertTrue(millis < 650, "answered after $millis ms")

        // The decision carried on after the answer and committed; Orders' retry gets it, and the money moved once.
        blocker.get(5, TimeUnit.SECONDS)
        await atMost Duration.ofSeconds(5) untilAsserted {
            assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM hold_decision WHERE order_id = ?", String::class.java, order.toString()))
        }
        val retry = hold(order, student, 600)
        assertEquals(200, retry.statusCode(), retry.body())
        assertEquals("PLACED", mapper.readTree(retry.body())["result"].asText())
        assertEquals(400L to 600L, accounts.balance(student).let { it.availableCents to it.heldCents })
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement WHERE reference_id = ?", Int::class.java, order.toString()))
    }

    private fun hold(order: UUID, student: UUID, amountCents: Long): HttpResponse<String> {
        val body = """{"orderId":"$order","studentId":"$student","dealId":"${UUID.randomUUID()}","cutoffAt":"2026-11-14T12:00:00Z","amountCents":$amountCents}"""
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/internal/ledger/holds"))
            .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
