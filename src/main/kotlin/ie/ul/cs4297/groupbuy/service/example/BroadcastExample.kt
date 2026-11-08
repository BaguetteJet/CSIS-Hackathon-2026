package ie.ul.cs4297.groupbuy.service.example

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.platform.PlatformSettings
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * EXAMPLE: a broadcast consumer (spec B, "Events with two instances").
 *
 * A normal consumer shares the group SERVICE_NAME, so each event reaches ONE of your instances.
 * A broadcast consumer has a group of its own per instance, so EVERY instance gets EVERY event.
 * Use it only for state each instance keeps in memory; Student experience's live counts are the
 * case in the spec (each instance pushes counts to the browsers connected to it).
 *
 * The group is new each time the instance starts and reads from the latest event, which suits
 * live updates. Everything else uses the shared group and the database.
 *
 * This example only counts the users.v1 events this instance has seen (metric
 * broadcast.example.events). Delete it, or turn it into your live-update consumer.
 */
@Component
class BroadcastExample(settings: PlatformSettings, meters: MeterRegistry) {
    private val seen = AtomicLong()

    /** Read by the @KafkaListener below: a group per instance, per start. */
    val groupId = "${settings.serviceName}-broadcast-${settings.instanceId}-${UUID.randomUUID().toString().take(8)}"

    init {
        meters.gauge("broadcast.example.events", seen)
    }

    @KafkaListener(
        id = "broadcast-example",
        topics = [Topics.USERS_V1],
        groupId = "#{__listener.groupId}",
        containerFactory = "broadcastListenerFactory", // skips bad messages: the main consumer dead-letters them
        properties = ["auto.offset.reset=latest"],
    )
    fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
        if (record.value() != null) seen.incrementAndGet()
    }

    fun seen(): Long = seen.get()
}
