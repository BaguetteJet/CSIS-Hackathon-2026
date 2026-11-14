package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.PayoutConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.TopUpRecorded
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestClock
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.UUID

/** [LED-19] - Bad input: zero-amount payments events land on the dead-letter topic at once, unrecorded, and the consumer moves on. */
class PaymentsDeadLetterTest : ContainersTest() {

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var accounts: LedgerAccounts
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun send(envelope: Envelope<*>) = kafka.send(Topics.PAYMENTS_V1, envelope.key, ContractJson.write(envelope)).get()

    @Test
    fun `a zero-amount cash-out, payout and top-up are dead-lettered and the next event is credited`() {
        val student = UUID.randomUUID()
        val restaurant = UUID.randomUUID()
        val bad = listOf(
            Envelope.of(WithdrawalRequested(UUID.randomUUID(), student, 0, UUID.randomUUID()), 1, TestClock.instant()),
            Envelope.of(PayoutConfirmed(UUID.randomUUID(), restaurant, 0), 1, TestClock.instant()),
            Envelope.of(TopUpRecorded(UUID.randomUUID(), student, 0, UUID.randomUUID()), 2, TestClock.instant()),
        )
        bad.forEach { send(it) }
        send(Envelope.of(TopUpRecorded(UUID.randomUUID(), student, 700, UUID.randomUUID()), 3, TestClock.instant()))

        // The good top-up is on the same key, behind the bad ones: crediting it means the consumer moved on.
        await atMost Duration.ofSeconds(30) untilAsserted { assertEquals(700L, accounts.balance(student).availableCents) }

        val ids = bad.map { it.eventId.toString() }.toSet()
        val dead = readTopic("$SERVICE.dlt", until = { seen -> seen.mapNotNull { it.value() }.count { v -> ids.any { v.contains(it) } } >= ids.size })
            .mapNotNull { it.value()?.let(ContractJson::read) }
            .filter { it.eventId.toString() in ids }
        assertEquals(ids, dead.map { it.eventId.toString() }.toSet(), "every zero-amount event is on $SERVICE.dlt")
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_event WHERE event_id IN (?, ?, ?)", Int::class.java, *ids.toTypedArray()),
            "none was recorded as processed")
    }
}
