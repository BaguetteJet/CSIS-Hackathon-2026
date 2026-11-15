package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.api.HoldRequest
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.testkit.assertMatchesContract
import ie.ul.cs4297.groupbuy.service.platform.ApiProblem
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestClock
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
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
    @Autowired lateinit var dailyCheck: DailyCheck

    fun funded(cents: Long): UUID = UUID.randomUUID().also { student ->
        if (cents > 0) tx.execute { topUps.creditTopUp(UUID.randomUUID(), student, cents) }
    }

    /** A hold waiting over 1 s on a busy machine answers 503 and saves nothing (EDGE-CASES H10): like Orders, retry the same orderId. */
    fun hold(order: UUID, student: UUID, cents: Long, deal: UUID): HoldResult {
        val request = HoldRequest(order, student, deal, Instant.parse("2026-11-14T12:00:00Z"), cents)
        repeat(4) {
            try {
                return holds.place(request).result
            } catch (e: ApiProblem) {
                if (e.status != HttpStatus.SERVICE_UNAVAILABLE) throw e
            }
        }
        return holds.place(request).result
    }

    fun envelope(event: ContractEvent): Envelope<ContractEvent> = Envelope.of(event, sequence = 1, occurredAt = TestClock.instant())

    fun send(envelope: Envelope<ContractEvent>) {
        kafka.send(envelope.payload.topic(), envelope.key, ContractJson.write(envelope)).get()
    }

    /** Sends each envelope and waits until [consumer] (the closures listener unless named) has processed all of them. */
    fun sendAndWait(vararg envelopes: Envelope<ContractEvent>, consumer: String = ClosuresConsumer.CONSUMER) {
        envelopes.forEach { send(it) }
        await atMost Duration.ofSeconds(30) untilAsserted {
            envelopes.map { it.eventId }.distinct().forEach { id ->
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE consumer = ? AND event_id = ?",
                    Int::class.java, consumer, id.toString()), "processed $id")
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

    /**
     * Item 9's invariants (I1 to I5) over the whole database; each is one statement, so it reads one consistent snapshot.
     * I1, I2: the daily check's own checks, so tests and production agree on what "balanced" means.
     */
    fun assertBooksBalance() {
        assertEquals(emptyList<String>(), dailyCheck.check(), "I1, I2: books sum to 0, no student below 0, balances equal postings")
        assertEquals(0, count("SELECT COUNT(*) FROM (SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents) <> 0) x"),
            "I1: every movement sums to 0")
        assertEquals(0, count(
            "SELECT COUNT(*) FROM ledger_account a WHERE a.owner_type = 'STUDENT' AND a.purpose = 'HELD' AND a.balance_cents <> " +
                "COALESCE((SELECT SUM(h.amount_cents) FROM hold_decision h WHERE h.student_id = a.owner_id AND h.state = 'OPEN'), 0) + " +
                "COALESCE((SELECT SUM(w.amount_cents) FROM withdrawal w WHERE w.student_id = a.owner_id AND w.state = 'RESERVED'), 0)",
        ), "I3: a student's HELD equals their open holds plus reserved cash-outs")
        assertEquals(0, count(
            "SELECT COUNT(*) FROM hold_decision h WHERE " +
                "(SELECT COUNT(*) FROM ledger_movement m WHERE m.reference_type = 'HOLD' AND m.reference_id = h.order_id) <> IF(h.result = 'PLACED', 1, 0) " +
                "OR (SELECT COUNT(*) FROM ledger_movement m WHERE m.reference_type = 'RELEASE' AND m.reference_id = h.order_id) <> IF(h.state = 'RELEASED', 1, 0) " +
                "OR (SELECT COUNT(*) FROM ledger_movement m WHERE m.reference_type = 'ORDER_CHARGE' AND m.reference_id = h.order_id) <> IF(h.state = 'CHARGED', 1, 0)",
        ), "I4: each placed hold has one HOLD movement, and a RELEASE or ORDER_CHARGE only as its state says")
        assertEquals(0, count(
            "SELECT COUNT(*) FROM deal_settlement s WHERE " +
                "s.charged_count <> (SELECT COUNT(*) FROM order_charge c WHERE c.deal_id = s.deal_id) " +
                "OR s.total_charged_cents <> (SELECT COALESCE(SUM(c.price_cents), 0) FROM order_charge c WHERE c.deal_id = s.deal_id) " +
                "OR s.total_commission_cents <> (SELECT COALESCE(SUM(c.commission_cents), 0) FROM order_charge c WHERE c.deal_id = s.deal_id)",
        ), "I5: each deal's settlement totals equal its charges")
    }

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!
}
