package ie.ul.cs4297.groupbuy.service.example

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.platform.PlatformSettings
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * EXAMPLE: a broadcast consumer (spec B, "Events with two instances").
 *
 * A normal consumer shares the group SERVICE_NAME, so each event reaches ONE of your instances.
 * A broadcast consumer has a group of its own per instance, so EVERY instance gets EVERY event.
 * Use it only for state each instance keeps in memory; Student experience's live counts are the
 * case in the spec (each instance pushes counts to the browsers connected to it).
 *
 * The group is named as in spec B, `<service>-live-<instance>` (`experience-live-1`,
 * `experience-live-2`): the instance is INSTANCE_ID, else the hostname, with a leading
 * `<service>-` dropped, so the container `experience-1` reads as `1`. The name stays the same across
 * restarts, so a restarted instance carries on from where it stopped. A group that has never run
 * starts at the latest event, which suits live updates. Everything else uses the shared group and
 * the database.
 *
 * This example only counts the users.v1 events this instance has seen (metric
 * broadcast.example.events). Delete it, or turn it into your live-update consumer.
 */
@Component
class BroadcastExample(settings: PlatformSettings, meters: MeterRegistry) {
    private val seen = AtomicLong()

    /** Read by the @KafkaListener below: one group per instance, the same at every start. */
    val groupId = broadcastGroup(settings.serviceName, "live", settings.instanceId)

    companion object {
        /** `<service>-<purpose>-<instance>`, for example experience-live-1 for the container experience-1. */
        fun broadcastGroup(serviceName: String, purpose: String, instanceId: String): String =
            "$serviceName-$purpose-${instanceId.removePrefix("$serviceName-")}"
    }

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
