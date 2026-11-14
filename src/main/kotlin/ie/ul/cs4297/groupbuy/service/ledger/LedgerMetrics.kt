package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.service.platform.EventConsumerConfig
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * [LED-23] - Observability: parked refunds, the age of the oldest (the 6 h alert), and dead letters, read at each scrape.
 * They come from the database and the topic, not from memory, so both instances report the same number and a restart loses nothing:
 * the dashboard takes the max, not the sum. Unreachable: NaN; with Kafka down the dead-letter read waits up to 3 × 2 s, inside the 10 s scrape timeout.
 */
@Component
class LedgerMetrics(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    consumers: EventConsumerConfig,
    kafkaAdmin: KafkaAdmin,
    meters: MeterRegistry,
) {
    private val deadLetterTopic = consumers.deadLetterTopic
    private val admin = AdminClient.create(kafkaAdmin.configurationProperties)

    init {
        Gauge.builder("ledger.refunds.parked", this) { it.orNaN { parkedRefunds() } }
            .description("Refunds waiting for their order's charge or deal's outcome").register(meters)
        Gauge.builder("ledger.refunds.parked.oldest", this) { it.orNaN { oldestParkedSeconds() } }
            .baseUnit("seconds").description("How long the oldest parked refund has waited; 0 when none").register(meters)
        Gauge.builder("ledger.dead.letters", this) { it.orNaN { deadLetters() } }
            .description("Messages on the Ledger's dead-letter topic").register(meters)
    }

    fun parkedRefunds(): Double =
        jdbc.queryForObject("SELECT COUNT(*) FROM parked_event WHERE consumer = ?", Long::class.java, SupportConsumer.CONSUMER)!!.toDouble()

    fun oldestParkedSeconds(): Double {
        val oldest = jdbc.queryForObject("SELECT MIN(parked_at) FROM parked_event WHERE consumer = ?", Timestamp::class.java, SupportConsumer.CONSUMER)
            ?: return 0.0
        return (clock.millis() - oldest.time).coerceAtLeast(0) / 1000.0
    }

    /** Latest minus earliest offset on every partition: what the topic holds now. */
    fun deadLetters(): Double {
        val partitions = admin.describeTopics(listOf(deadLetterTopic)).allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .getValue(deadLetterTopic).partitions().map { TopicPartition(deadLetterTopic, it.partition()) }
        val latest = admin.listOffsets(partitions.associateWith { OffsetSpec.latest() }).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val earliest = admin.listOffsets(partitions.associateWith { OffsetSpec.earliest() }).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return partitions.sumOf { latest.getValue(it).offset() - earliest.getValue(it).offset() }.toDouble()
    }

    private fun orNaN(read: () -> Double): Double = runCatching(read).getOrDefault(Double.NaN)

    @PreDestroy
    fun close() = admin.close()

    companion object {
        private const val TIMEOUT_SECONDS = 2L
    }
}
