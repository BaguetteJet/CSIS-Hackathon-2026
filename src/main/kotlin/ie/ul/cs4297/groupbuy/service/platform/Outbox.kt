package ie.ul.cs4297.groupbuy.service.platform

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Publishing events (spec B, rules 5 and 7, and "Events with two instances").
 *
 * Call [publish] inside the same @Transactional method that saves your change. The event is saved
 * in the outbox table with the change, or not at all; the relay then sends it to Kafka. Delivery is
 * therefore at least once, never lost and never "sent but not saved".
 *
 * ```
 * @Transactional
 * fun approve(dealId: UUID) {
 *     deals.save(...)
 *     outbox.publish(DealPublished(...))
 * }
 * ```
 */
@Component
class Outbox(private val jdbc: JdbcTemplate, private val clock: Clock) {

    /**
     * Saves [event] for publishing, with the next sequence number for its key.
     *
     * @param causationId the eventId of the event you are handling, if any
     * @return the envelope as it will be published
     */
    fun <T : ContractEvent> publish(event: T, causationId: UUID? = null): Envelope<T> {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Outbox.publish must run inside @Transactional, in the same transaction as the change it announces"
        }
        val envelope = Envelope.of(event, sequence = nextSequence(event.topic(), event.key()), occurredAt = clock.instant(), causationId = causationId)
        jdbc.update(
            "INSERT INTO outbox_event (event_id, topic, event_key, event_type, message_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            envelope.eventId.toString(), event.topic(), envelope.key, envelope.type, ContractJson.write(envelope),
            Timestamp.from(envelope.occurredAt),
        )
        return envelope
    }

    /**
     * The per-key counter row (spec B, "Sequence numbers"). The upsert locks the row until the
     * transaction ends, so two instances changing the same key get consecutive numbers.
     */
    private fun nextSequence(topic: String, key: String): Long {
        jdbc.update(
            "INSERT INTO key_sequence (topic, event_key, last_sequence) VALUES (?, ?, 1) " +
                "ON DUPLICATE KEY UPDATE last_sequence = last_sequence + 1",
            topic, key,
        )
        return jdbc.queryForObject(
            "SELECT last_sequence FROM key_sequence WHERE topic = ? AND event_key = ?", Long::class.java, topic, key,
        )!!
    }
}

/**
 * Sends the outbox to Kafka. Both instances run it, but only the one holding the lease row
 * publishes, and always in outbox order, so one key's events never overtake each other.
 *
 * The lease lasts 10 seconds and is renewed before every send: if this instance stops, the other
 * takes over within 10 seconds. Times for the lease come from the database, so both instances agree.
 */
@Component
class OutboxRelay(
    private val jdbc: JdbcTemplate,
    private val kafka: KafkaTemplate<String, String>,
    settings: PlatformSettings,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val me = "${settings.serviceName}/${settings.instanceId}/${UUID.randomUUID().toString().take(8)}"
    private val published = meters.counter("outbox.published")
    private var leader = false

    init {
        meters.gauge("outbox.unpublished", this) { relay ->
            runCatching { relay.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL", Long::class.java)!!.toDouble() }
                .getOrDefault(Double.NaN)
        }
    }

    @Scheduled(fixedDelayString = "\${groupbuy.outbox.poll-millis:200}")
    fun relay() {
        try {
            while (holdLease()) {
                val batch = jdbc.query(
                    "SELECT id, topic, event_key, message_json FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT 100",
                ) { rs, _ -> Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)) }
                if (batch.isEmpty()) return
                for (row in batch) {
                    if (!holdLease()) return
                    kafka.send(row.topic, row.key, row.json).get(10, TimeUnit.SECONDS)
                    jdbc.update("UPDATE outbox_event SET published_at = UTC_TIMESTAMP(6) WHERE id = ?", row.id)
                    published.increment()
                }
            }
        } catch (e: Exception) {
            // Kafka or MySQL is down: stop, keep order, try again on the next tick.
            log.warn("Outbox relay paused: {}", e.message)
        }
    }

    /** Takes or renews the lease. True when this instance is the publisher. */
    private fun holdLease(): Boolean {
        val got = jdbc.update(
            "UPDATE outbox_lease SET holder = ?, expires_at = UTC_TIMESTAMP(6) + INTERVAL 10 SECOND " +
                "WHERE name = 'outbox-relay' AND (holder = ? OR expires_at < UTC_TIMESTAMP(6))",
            me, me,
        ) == 1
        if (got != leader) log.info(if (got) "This instance ({}) is now the outbox publisher" else "This instance ({}) is no longer the outbox publisher", me)
        leader = got
        return got
    }

    private data class Row(val id: Long, val topic: String, val key: String, val json: String)
}
