package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.api.HoldRequest
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.testkit.assertMatchesContract
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestClock
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Helpers for tests that drive money through holds, closures and settlement on real MySQL and Kafka. */
abstract class LedgerFlowTest : ContainersTest() {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var tx: TransactionTemplate
    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var holds: Holds
    @Autowired lateinit var topUps: TopUpPosting
    @Autowired lateinit var accounts: LedgerAccounts

    fun funded(cents: Long): UUID = UUID.randomUUID().also { student ->
        if (cents > 0) tx.execute { topUps.creditTopUp(UUID.randomUUID(), student, cents) }
    }

    fun hold(order: UUID, student: UUID, cents: Long, deal: UUID): HoldResult =
        holds.place(HoldRequest(order, student, deal, Instant.parse("2026-11-14T12:00:00Z"), cents)).result

    fun envelope(event: ContractEvent): Envelope<ContractEvent> = Envelope.of(event, sequence = 1, occurredAt = TestClock.instant())

    fun send(envelope: Envelope<ContractEvent>) {
        kafka.send(envelope.payload.topic(), envelope.key, ContractJson.write(envelope)).get()
    }

    /** Sends each envelope and waits until the closures listener has processed all of them. */
    fun sendAndWait(vararg envelopes: Envelope<ContractEvent>) {
        envelopes.forEach { send(it) }
        await atMost Duration.ofSeconds(30) untilAsserted {
            envelopes.map { it.eventId }.distinct().forEach { id ->
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                    Int::class.java, ClosuresConsumer.CONSUMER, id.toString()), "processed $id")
            }
        }
    }

    fun balanceOf(student: UUID): Pair<Long, Long> = accounts.balance(student).let { it.availableCents to it.heldCents }

    fun stateOf(order: UUID): String? =
        jdbc.queryForList("SELECT state FROM hold_decision WHERE order_id = ?", String::class.java, order.toString()).singleOrNull()

    fun movements(type: MovementType, reference: UUID): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM ledger_movement WHERE reference_type = ? AND reference_id = ?", Int::class.java, type.name, reference.toString())!!

    /** The payloads of [type] events about [deal] in the outbox, oldest first; each one checked against the contract. */
    fun events(type: String, deal: UUID): List<JsonNode> = jdbc.queryForList(
        "SELECT message_json FROM outbox_event WHERE event_type = ? AND JSON_UNQUOTE(JSON_EXTRACT(message_json, '$.payload.dealId')) = ? ORDER BY id",
        String::class.java, type, deal.toString(),
    ).map { json -> assertMatchesContract(json); mapper.readTree(json)["payload"] }

    fun statement(student: UUID): List<Pair<String, String>> = jdbc.query(
        "SELECT p.entry_kind, p.description FROM ledger_posting p JOIN ledger_account a ON a.id = p.account_id " +
            "WHERE a.owner_type = 'STUDENT' AND a.owner_id = ? AND a.purpose = 'AVAILABLE' AND p.entry_kind IS NOT NULL ORDER BY p.id",
        { rs, _ -> rs.getString(1) to rs.getString(2) }, student.toString(),
    )

    fun assertBooksBalance() = assertEquals(0L, accounts.sumOfAllAccounts(), "all postings sum to 0")
}
