package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.CancelReason
import ie.ul.cs4297.groupbuy.contracts.events.DealCancelled
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.DealFailed
import ie.ul.cs4297.groupbuy.contracts.events.OrderAbandoned
import ie.ul.cs4297.groupbuy.contracts.events.OrderWithdrawn
import ie.ul.cs4297.groupbuy.contracts.testkit.assertMatchesContract
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestClock
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
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

// Item 1 review on real MySQL and Kafka with two instances. Tests are independent.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoldsTest : ContainersTest() {

    @LocalServerPort var port = 0
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var topUps: TopUpPosting
    @Autowired lateinit var accounts: LedgerAccounts
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var kafka: KafkaTemplate<String, String>

    private lateinit var two: ConfigurableApplicationContext
    private val portTwo get() = two.environment.getProperty("local.server.port")!!.toInt()
    private val http = HttpClient.newHttpClient()

    @BeforeAll
    fun start() {
        two = startSecondInstance()
    }

    @AfterAll
    fun stop() = two.close()

    @Test
    fun `exact funds are placed, and HoldPlaced carries the balance after`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val answer = hold(port, body(order, student, 1000))
        assertEquals(200, answer.statusCode())
        assertEquals(mapOf("orderId" to order.toString(), "result" to "PLACED"), fields(answer))
        assertEquals(0L to 1000L, balanceOf(student))
        assertEquals("OPEN", stateOf(order))
        val event = holdEvents(student).single()
        assertMatchesContract(event)
        assertEquals("HoldPlaced", tree(event)["type"].asText())
        assertEquals(0, tree(event)["payload"]["availableCents"].asInt())
        assertEquals(1000, tree(event)["payload"]["heldCents"].asInt())
    }

    @Test
    fun `one cent short is refused with the available cents, and nothing moves`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val answer = hold(port, body(order, student, 1001))
        assertEquals(200, answer.statusCode())
        assertEquals(mapOf("orderId" to order.toString(), "result" to "INSUFFICIENT_FUNDS", "availableCents" to "1000"), fields(answer))
        assertEquals(1000L to 0L, balanceOf(student))
        assertEquals(0, movementsFor(order))
        assertEquals(null, stateOf(order))
        val event = holdEvents(student).single()
        assertMatchesContract(event)
        assertEquals("HoldRejected", tree(event)["type"].asText())
        assertEquals("INSUFFICIENT_FUNDS", tree(event)["payload"]["reason"].asText())
    }

    @Test
    fun `a student with no account is refused with 0 available`() {
        val student = UUID.randomUUID()
        val answer = hold(port, body(UUID.randomUUID(), student, 1))
        assertEquals("INSUFFICIENT_FUNDS", fields(answer)["result"])
        assertEquals("0", fields(answer)["availableCents"])
        assertMatchesContract(holdEvents(student).single())
    }

    @Test
    fun `a repeat on the other instance gets the first answer whatever its body, and moves and publishes nothing`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        val first = fields(hold(port, body(order, student, 600)))
        val other = funded(5000)
        // The same order four times on the other instance: same body, less money, another student, another deal.
        for (repeat in listOf(body(order, student, 600), body(order, student, 50), body(order, other, 600), body(order, student, 600, UUID.randomUUID()))) {
            assertEquals(first, fields(hold(portTwo, repeat)))
        }
        // A refusal is a first answer too: a repeat after a top-up is still refused.
        val refused = UUID.randomUUID()
        val refusal = fields(hold(port, body(refused, student, 5000)))
        tx.execute { topUps.creditTopUp(UUID.randomUUID(), student, 10_000) }
        assertEquals(refusal, fields(hold(portTwo, body(refused, student, 5000))))

        assertEquals(400L + 10_000L to 600L, balanceOf(student))
        assertEquals(1, movementsFor(order))
        assertEquals(listOf("HoldPlaced", "HoldRejected"), holdEvents(student).map { tree(it)["type"].asText() })
        assertEquals(5000L to 0L, balanceOf(other))
        assertTrue(holdEvents(other).isEmpty())
    }

    @Test
    fun `the same order racing on both instances is decided once`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        // Like Orders retrying while the first call runs: 8 calls, alternating instances.
        val answers = race(List(8) { i -> (if (i % 2 == 0) port else portTwo) to body(order, student, 600) })
        assertTrue(answers.all { it.statusCode() == 200 }, answers.map { it.statusCode() }.toString())
        assertEquals(1, answers.map { fields(it) }.toSet().size)
        assertEquals("PLACED", fields(answers.first())["result"])
        assertEquals(400L to 600L, balanceOf(student))
        assertEquals(1, movementsFor(order))
        assertEquals(1, holdEvents(student).size)
    }

    @Test
    fun `two 6 euro orders racing for 10 euro on both instances, one placed and one refused`() {
        repeat(5) {
            val student = funded(1000)
            val answers = race(listOf(port to body(UUID.randomUUID(), student, 600), portTwo to body(UUID.randomUUID(), student, 600)))
                .map { fields(it) }
            assertEquals(listOf("INSUFFICIENT_FUNDS", "PLACED"), answers.map { it["result"]!! }.sorted())
            assertEquals("400", answers.single { it["result"] == "INSUFFICIENT_FUNDS" }["availableCents"])
            assertEquals(400L to 600L, balanceOf(student))
            assertEquals(listOf("HoldPlaced", "HoldRejected"), holdEvents(student).map { tree(it)["type"].asText() })
        }
    }

    @Test
    fun `a student locked for longer than Orders waits answers 503 within 2 seconds, and leaves no answer behind`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        // Hold the student's lock from outside for 3 s: longer than Orders waits.
        val locked = CountDownLatch(1)
        val blocker = Executors.newSingleThreadExecutor().submit {
            dataSource.connection.use { c ->
                c.autoCommit = false
                c.prepareStatement("SELECT * FROM ledger_account WHERE owner_type = 'STUDENT' AND owner_id = '$student' FOR UPDATE").executeQuery()
                locked.countDown()
                Thread.sleep(3000)
                c.rollback()
            }
        }
        locked.await(5, TimeUnit.SECONDS)
        val started = System.nanoTime()
        val answer = hold(port, body(order, student, 600))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(503, answer.statusCode(), answer.body())
        assertEquals("UNAVAILABLE", tree(answer.body())["code"].asText())
        assertTrue(millis < 2000, "answered after $millis ms")
        blocker.get(10, TimeUnit.SECONDS)
        // Orders retries: nothing was saved, so this is the first decision.
        assertEquals("PLACED", fields(hold(portTwo, body(order, student, 600)))["result"])
    }

    @Test
    fun `bad input is 422 with errors naming each field, and over 64 KB is 413`() {
        val order = UUID.randomUUID()
        val student = UUID.randomUUID()
        fun fieldsWrong(body: String, contentType: String? = "application/json"): List<String> {
            val answer = hold(port, body, contentType)
            assertEquals(422, answer.statusCode(), body)
            assertEquals("VALIDATION_FAILED", tree(answer.body())["code"].asText())
            return tree(answer.body())["errors"]?.map { it["field"].asText() } ?: emptyList()
        }
        assertEquals(listOf("amountCents"), fieldsWrong(body(order, student, 0)))
        assertEquals(listOf("amountCents"), fieldsWrong(body(order, student, -5)))
        assertEquals(listOf("amountCents"), fieldsWrong(body(order, student, 6).replace("6}", "6.5}")))
        assertEquals(listOf("amountCents"), fieldsWrong(body(order, student, 6).replace("6}", "\"6\"}")))
        assertEquals(listOf("orderId"), fieldsWrong(body(order, student, 6).replace(order.toString(), "not-a-uuid")))
        assertEquals(listOf("studentId"), fieldsWrong(body(order, student, 6).replace("\"$student\"", "null")))
        assertEquals(listOf("cutoffAt"), fieldsWrong(body(order, student, 6).replace("12:00:00Z", "12:00:00+01:00")))
        assertEquals(listOf("orderId", "studentId", "dealId", "cutoffAt", "amountCents"), fieldsWrong("{}"))
        assertEquals(emptyList<String>(), fieldsWrong("{"))
        // Not one JSON object: trailing text, a second object, or a field given twice (which value is meant?).
        assertEquals(emptyList<String>(), fieldsWrong(body(order, student, 6) + " garbage"))
        assertEquals(emptyList<String>(), fieldsWrong(body(order, student, 6) + body(order, student, 6)))
        assertEquals(emptyList<String>(), fieldsWrong(body(order, student, 6).replace("{", "{\"amountCents\":1,")))
        assertEquals(listOf("Content-Type"), fieldsWrong(body(order, student, 6), contentType = null))
        assertEquals(listOf("Content-Type"), fieldsWrong(body(order, student, 6), contentType = "text/plain"))

        val big = hold(port, body(order, student, 6).replace("{", "{\"padding\":\"${"x".repeat(70_000)}\","))
        assertEquals(413, big.statusCode())
        assertEquals("PAYLOAD_TOO_LARGE", tree(big.body())["code"].asText())

        // Nothing above was decided. Unknown fields are ignored.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM hold_decision WHERE order_id = ?", Int::class.java, order.toString()))
        val ok = hold(port, body(order, student, 6).replace("{", "{\"note\":\"ignored\","), "application/json; charset=utf-8")
        assertEquals(200, ok.statusCode())
    }

    @Test
    fun `the same topUpId twice credits once, at the first amount`() {
        val student = UUID.randomUUID()
        val topUp = UUID.randomUUID()
        val first = tx.execute { topUps.creditTopUp(topUp, student, 5000) }!!
        val again = tx.execute { topUps.creditTopUp(topUp, student, 9999) }!!
        assertEquals(TopUpResult(true, 5000, 0), first)
        assertEquals(TopUpResult(false, 5000, 0), again)
        assertEquals(1, movementsFor(topUp))
    }

    // The marked property: "A hold that arrives after its deal or order has closed is refused".

    @Test
    fun `a first hold for a withdrawn or abandoned order is ORDER_CLOSED, and nothing moves`() {
        val student = funded(1000)
        for (close in listOf<(UUID) -> ContractEvent>({ OrderWithdrawn(it, DEAL, student) }, { OrderAbandoned(it, DEAL, student) })) {
            val order = UUID.randomUUID()
            publish(close(order))
            awaitClosed("order_closure", "order_id", order)
            assertEquals(mapOf("orderId" to "$order", "result" to "ORDER_CLOSED"), fields(hold(port, body(order, student, 100))))
        }
        assertEquals(1000L to 0L, balanceOf(student))
        val events = holdEvents(student)
        events.forEach { assertMatchesContract(it) }
        assertEquals(listOf("ORDER_CLOSED", "ORDER_CLOSED"), events.map { tree(it)["payload"]["reason"].asText() })
        assertEquals(1000, tree(events[0])["payload"]["availableCents"].asInt()) // the balance, even though nothing moved
    }

    @Test
    fun `a first hold for a confirmed, failed or cancelled deal is DEAL_CLOSED`() {
        val student = funded(1000)
        for (close in listOf<(UUID) -> ContractEvent>(
            { DealConfirmed(it, UUID.randomUUID(), 475, 1000, emptyList()) },
            { DealFailed(it, 3) },
            { DealCancelled(it, CancelReason.DECLINED) },
        )) {
            val deal = UUID.randomUUID()
            publish(close(deal))
            awaitClosed("deal_closure", "deal_id", deal)
            assertEquals("DEAL_CLOSED", fields(hold(portTwo, body(UUID.randomUUID(), student, 100, deal)))["result"])
        }
        assertEquals(1000L to 0L, balanceOf(student))
        assertEquals(List(3) { "DEAL_CLOSED" }, holdEvents(student).map { tree(it)["payload"]["reason"].asText() })
    }

    @Test
    fun `several reasons at once - ORDER_CLOSED, then DEAL_CLOSED, then INSUFFICIENT_FUNDS`() {
        val broke = UUID.randomUUID() // no money at all: INSUFFICIENT_FUNDS would also apply
        val deal = UUID.randomUUID()
        val order = UUID.randomUUID()
        publish(DealFailed(deal, 1))
        publish(OrderAbandoned(order, deal, broke))
        awaitClosed("deal_closure", "deal_id", deal)
        awaitClosed("order_closure", "order_id", order)
        assertEquals("ORDER_CLOSED", fields(hold(port, body(order, broke, 100, deal)))["result"])
        assertEquals("DEAL_CLOSED", fields(hold(port, body(UUID.randomUUID(), broke, 100, deal)))["result"])
    }

    @Test
    fun `a repeat keeps its first answer even after the order closes`() {
        val student = funded(1000)
        val order = UUID.randomUUID()
        assertEquals("PLACED", fields(hold(port, body(order, student, 600)))["result"])
        publish(OrderWithdrawn(order, DEAL, student))
        awaitClosed("order_closure", "order_id", order)
        assertEquals("PLACED", fields(hold(portTwo, body(order, student, 600)))["result"])
        assertEquals(1, holdEvents(student).size)
    }

    /** Send [event] on its real topic, like Orders or Deals would. */
    private fun publish(event: ContractEvent) {
        kafka.send(event.topic(), event.key(), ContractJson.write(Envelope.of(event, sequence = 1, occurredAt = TestClock.instant()))).get()
    }

    /** Wait until the closures listener writes the row for [id]. */
    private fun awaitClosed(table: String, column: String, id: UUID) {
        await atMost Duration.ofSeconds(30) untilAsserted {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $column = ?", Int::class.java, id.toString()))
        }
    }

    /** A new student with [cents] available: funded through the real top-up path. */
    private fun funded(cents: Long): UUID = UUID.randomUUID().also { student ->
        tx.execute { topUps.creditTopUp(UUID.randomUUID(), student, cents) }
    }

    private fun body(order: UUID, student: UUID, amountCents: Long, deal: UUID = DEAL) =
        """{"orderId":"$order","studentId":"$student","dealId":"$deal","cutoffAt":"2026-11-14T12:00:00Z","amountCents":$amountCents}"""

    private fun hold(port: Int, body: String, contentType: String? = "application/json"): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/internal/ledger/holds"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .apply { if (contentType != null) header("Content-Type", contentType) }
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** Send every call at once and return the answers in order. */
    private fun race(calls: List<Pair<Int, String>>): List<HttpResponse<String>> {
        val pool = Executors.newFixedThreadPool(calls.size)
        val go = CountDownLatch(1)
        try {
            val futures = calls.map { (port, body) -> pool.submit<HttpResponse<String>> { go.await(); hold(port, body) } }
            go.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** A 200 answer's fields as strings: two answers compare with plain equality. */
    private fun fields(answer: HttpResponse<String>): Map<String, String> {
        assertEquals(200, answer.statusCode(), answer.body())
        return tree(answer.body()).properties().associate { it.key to it.value.asText() }
    }

    private fun tree(json: String): JsonNode = mapper.readTree(json)

    private fun balanceOf(student: UUID): Pair<Long, Long> = accounts.balance(student).let { it.availableCents to it.heldCents }

    private fun stateOf(order: UUID): String? =
        jdbc.queryForObject("SELECT state FROM hold_decision WHERE order_id = ?", String::class.java, order.toString())

    private fun movementsFor(reference: UUID): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM ledger_movement WHERE reference_id = ?", Int::class.java, reference.toString())!!

    private fun holdEvents(student: UUID): List<String> = jdbc.queryForList(
        "SELECT message_json FROM outbox_event WHERE event_key = ? AND event_type IN ('HoldPlaced', 'HoldRejected') ORDER BY id",
        String::class.java, student.toString(),
    )

    companion object {
        private val DEAL: UUID = UUID.randomUUID()
    }
}
