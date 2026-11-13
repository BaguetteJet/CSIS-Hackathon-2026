package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.TopUpCredited
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestClock
import ie.ul.cs4297.groupbuy.service.support.TestTokens
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * The first vertical slice across items 2 and 3: TopUpRecorded (PaymentsConsumer) credits once
 * through TopUpStore, publishes TopUpCredited, and /me reads the balance from the accounts.
 * Needs Docker and Kafka: ./gradlew containerTest
 */
class LedgerTopUpTest : ContainersTest() {

    @LocalServerPort var port = 0
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper

    private val http = HttpClient.newHttpClient()

    private fun send(event: Envelope<*>) =
        kafka.send(Topics.PAYMENTS_V1, event.key, ContractJson.write(event)).get()

    private fun me(token: String?): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port/api/ledger/me"))
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** GET /api/ledger/me with the student's token: (availableCents, heldCents). */
    private fun balance(student: UUID): Pair<Long, Long> {
        val response = me(TestTokens.student(student))
        assertEquals(200, response.statusCode(), response.body())
        val body = mapper.readTree(response.body())
        return body["availableCents"].asLong() to body["heldCents"].asLong()
    }

    @Test
    fun `a top-up is credited once and visible at me`() {
        val student = UUID.randomUUID()
        val topUpId = UUID.randomUUID()
        assertEquals(0L to 0L, balance(student), "a student whose account has not been opened gets zeros")

        send(Envelope.of(TopUpRecorded(topUpId, student, 5_000, UUID.randomUUID()), 1, TestClock.instant()))
        await atMost Duration.ofSeconds(30) untilAsserted {
            assertEquals(5_000L to 0L, balance(student))
        }

        // The same topUpId with a different amount: the first amount wins and nothing is published.
        val repeat = Envelope.of(TopUpRecorded(topUpId, student, 9_000, UUID.randomUUID()), 2, TestClock.instant())
        send(repeat)
        await atMost Duration.ofSeconds(30) untilAsserted {
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                    Int::class.java, PaymentsConsumer.CONSUMER, repeat.eventId.toString(),
                ),
                "the repeat has been processed",
            )
        }
        assertEquals(5_000L to 0L, balance(student), "the repeat changes nothing")

        val credited = readTopic(Topics.LEDGER_V1, until = { seen -> seen.any { it.key() == student.toString() } })
            .mapNotNull { record -> record.value()?.let { json -> ContractJson.read(json) } }
            .map { envelope -> envelope.payload }
            .filterIsInstance<TopUpCredited>()
            .filter { event -> event.topUpId == topUpId }
        assertEquals(1, credited.size, "one TopUpCredited for the topUpId")
        assertEquals(5_000L, credited.single().amountCents)
        assertEquals(5_000L, credited.single().availableCents)
        assertEquals(0L, credited.single().heldCents)

        assertEquals(0L, jdbc.queryForObject("SELECT COALESCE(SUM(balance_cents), 0) FROM ledger_account", Long::class.java)!!)
    }

    @Test
    fun `me without a token is 401, and with another role is 403`() {
        assertEquals(401, me(null).statusCode())
        val wrongRole = me(TestTokens.union(Role.UNION_FINANCE))
        assertEquals(403, wrongRole.statusCode())
        assertEquals("FORBIDDEN", mapper.readTree(wrongRole.body())["code"].asText())
    }
}
