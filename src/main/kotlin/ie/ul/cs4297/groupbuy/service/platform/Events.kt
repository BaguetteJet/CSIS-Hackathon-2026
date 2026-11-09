package ie.ul.cs4297.groupbuy.service.platform

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

/**
 * Reads a Kafka value as an envelope with its event class (contracts library). It runs inside
 * Spring's ErrorHandlingDeserializer (application.yml), so a message that cannot be read at all
 * goes straight to the dead-letter topic instead of wedging the consumer (the Week 6 trap).
 *
 * The value is null for a tombstone (users.v1 is compacted) and for an event type this version of
 * the contracts does not know: ignore both (rule 9).
 */
class EnvelopeDeserializer : Deserializer<Envelope<ContractEvent>?> {
    override fun deserialize(topic: String, data: ByteArray?): Envelope<ContractEvent>? =
        data?.let { ContractJson.read(it.decodeToString()) }
}

/** Writes dead letters: raw bytes when the message could not be read, JSON otherwise. */
class DeadLetterSerializer : Serializer<Any?> {
    override fun serialize(topic: String, data: Any?): ByteArray? = when (data) {
        null -> null
        is ByteArray -> data
        is String -> data.encodeToByteArray()
        is Envelope<*> -> ContractJson.write(data).encodeToByteArray()
        else -> ContractJson.mapper.writeValueAsBytes(data)
    }
}

/**
 * Retries and dead letters (spec B, rule 8): a failed event is retried five times with growing
 * gaps (1, 2, 4, 8, 16 seconds), then moved to this service's dead-letter topic, `<service>.dlt`,
 * with the reason in its headers. A message that cannot be read at all goes there at once.
 * The platform team's tool replays dead letters after the fix.
 */
@Configuration
class EventConsumerConfig(private val settings: PlatformSettings) {
    private val log = LoggerFactory.getLogger(javaClass)

    val deadLetterTopic: String get() = "${settings.serviceName}.dlt"

    @Bean
    fun deadLetterTopicDefinition(): NewTopic = TopicBuilder.name(deadLetterTopic).partitions(6).build()

    @Bean
    fun kafkaErrorHandler(kafka: KafkaProperties): DefaultErrorHandler {
        val producerProps = kafka.buildProducerProperties(null).toMutableMap()
        producerProps[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        producerProps[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = DeadLetterSerializer::class.java
        val template = KafkaTemplate(DefaultKafkaProducerFactory<Any, Any>(producerProps))
        val recoverer = DeadLetterPublishingRecoverer(template) { record, e ->
            log.error("Dead letter: {} partition {} offset {} key {}: {}", record.topic(), record.partition(), record.offset(), record.key(), e.cause?.message ?: e.message)
            TopicPartition(deadLetterTopic, -1)
        }
        val backOff = ExponentialBackOffWithMaxRetries(5).apply {
            initialInterval = 1_000
            multiplier = 2.0
            maxInterval = 16_000
        }
        return DefaultErrorHandler(recoverer, backOff)
    }

    /**
     * For broadcast consumers (a group per instance): a message one cannot read is logged and
     * skipped, not dead-lettered, because the service's main consumer dead-letters it already.
     * Use it with @KafkaListener(containerFactory = "broadcastListenerFactory", ...). Its listeners
     * are recorded in [BroadcastListeners], so [ConsumerGroupCheck] lets them have a group of their own.
     */
    @Bean
    fun broadcastListenerFactory(
        configurer: org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer,
        consumers: org.springframework.kafka.core.ConsumerFactory<Any, Any>,
        broadcast: BroadcastListeners,
    ): org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Any, Any> =
        org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<Any, Any>().also {
            configurer.configure(it, consumers)
            it.setContainerCustomizer { container -> container.listenerId?.let(broadcast::add) }
            it.setCommonErrorHandler(DefaultErrorHandler({ record, e ->
                log.warn("Broadcast consumer skipped {} partition {} offset {}: {}", record.topic(), record.partition(), record.offset(), e.cause?.message ?: e.message)
            }, org.springframework.util.backoff.FixedBackOff(0, 0)))
        }
}

/**
 * Idempotent consumers (spec B, rule 6). Wrap each event's effect in [once]:
 *
 * ```
 * @KafkaListener(topics = [Topics.DEALS_V1])
 * fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
 *     val envelope = record.value() ?: return
 *     inbox.once("deals-listener", envelope) {
 *         when (val e = envelope.payload) { is DealTipped -> ...; else -> {} }
 *     }
 * }
 * ```
 *
 * The eventId is recorded in the same transaction as your effect, so a repeated event changes
 * nothing. Still give every effect a business key too (a unique constraint, for example one charge
 * per order): a repeat can also come with a new eventId.
 */
@Component
class EventInbox(private val jdbc: JdbcTemplate, private val tx: TransactionTemplate, private val clock: Clock) {

    /** Runs [effect] in one transaction, unless [consumer] has processed this eventId before. Returns false for a repeat. */
    fun once(consumer: String, envelope: Envelope<*>, effect: () -> Unit): Boolean = tx.execute {
        try {
            jdbc.update(
                "INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (?, ?, ?)",
                consumer, envelope.eventId.toString(), Timestamp.from(clock.instant()),
            )
        } catch (e: DuplicateKeyException) {
            return@execute false
        }
        effect()
        true
    }!!
}

/**
 * The parking table (spec B, rule 7). Events across topics can arrive in any order. When one
 * arrives before the event it depends on, park it, and apply it when that event arrives. Never
 * retry it until it is dead-lettered, and never block the partition waiting.
 *
 * ```
 * inbox.once("orders-listener", envelope) {
 *     val deal = deals.find(e.dealId)
 *     if (deal == null) { parking.park("orders-listener", "deal:${e.dealId}", envelope); return@once }
 *     ...
 * }
 * // and where the deal arrives, in the same transaction:
 * parking.release("orders-listener", "deal:${e.dealId}").forEach { apply(it) }
 * ```
 *
 * Both calls must run inside a transaction (inside [EventInbox.once] they do).
 */
@Component
class ParkedEvents(private val jdbc: JdbcTemplate, private val clock: Clock) {

    /** Keeps [envelope] until [release] is called with the same [waitsFor], a name you choose such as "deal:<id>". */
    fun park(consumer: String, waitsFor: String, envelope: Envelope<*>) {
        jdbc.update(
            "INSERT IGNORE INTO parked_event (consumer, waits_for, event_id, message_json, parked_at) VALUES (?, ?, ?, ?, ?)",
            consumer, waitsFor, envelope.eventId.toString(), ContractJson.write(envelope), Timestamp.from(clock.instant()),
        )
    }

    /** Removes and returns, oldest first, every event parked for [waitsFor]. Apply each one. */
    fun release(consumer: String, waitsFor: String): List<Envelope<ContractEvent>> {
        val rows = jdbc.query(
            "SELECT id, message_json FROM parked_event WHERE consumer = ? AND waits_for = ? ORDER BY id FOR UPDATE",
            { rs, _ -> rs.getLong(1) to rs.getString(2) }, consumer, waitsFor,
        )
        rows.forEach { (id, _) -> jdbc.update("DELETE FROM parked_event WHERE id = ?", id) }
        return rows.mapNotNull { (_, json) -> ContractJson.read(json) }
    }

    /** How many events are parked, for a dashboard or a test. */
    fun count(consumer: String? = null): Long =
        if (consumer == null) jdbc.queryForObject("SELECT COUNT(*) FROM parked_event", Long::class.java)!!
        else jdbc.queryForObject("SELECT COUNT(*) FROM parked_event WHERE consumer = ?", Long::class.java, consumer)!!
}
