package ie.ul.cs4297.groupbuy.service.platform

import org.springframework.context.SmartLifecycle
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The listener ids of broadcast consumers: the containers made by `broadcastListenerFactory`
 * (EventConsumerConfig) are recorded here as they are created. Only these may have a group other
 * than SERVICE_NAME.
 */
@Component
class BroadcastListeners {
    private val ids: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun add(listenerId: String) { ids += listenerId }
    fun ids(): Set<String> = ids.toSet()
}

/**
 * Every consumer shares the group SERVICE_NAME (spec B, Technical conventions), so each event is
 * processed once per service. A listener on any other group gets every event a second time, on
 * both instances, and starts from the beginning of the topic.
 *
 * The usual cause is `@KafkaListener(id = "...")` without `idIsGroup = false`: Spring Kafka then
 * uses the id as the group. This check runs after the listeners are created and before any of
 * them starts, and stops the service with the listener's name and the fix.
 */
@Component
class ConsumerGroupCheck(
    private val registry: KafkaListenerEndpointRegistry,
    private val settings: PlatformSettings,
    private val broadcast: BroadcastListeners,
) : SmartLifecycle {
    private var running = false

    // Listener containers start in phase Integer.MAX_VALUE - 100; this runs well before them.
    override fun getPhase(): Int = 0
    override fun isRunning(): Boolean = running
    override fun stop() { running = false }

    override fun start() {
        val wrong = problems(registry.allListenerContainers, settings.serviceName, broadcast.ids())
        check(wrong.isEmpty()) { wrong.joinToString("\n") }
        running = true
    }

    companion object {
        /** One message per listener whose group breaks the rule; empty when all is well. */
        fun problems(containers: Collection<MessageListenerContainer>, serviceName: String, broadcastIds: Set<String>): List<String> =
            containers.mapNotNull { c ->
                val id = c.listenerId ?: "(no id)"
                val group = c.groupId
                when {
                    id in broadcastIds && (group == null || group == serviceName || !group.startsWith("$serviceName-")) ->
                        "Broadcast listener '$id' has consumer group '$group'. A broadcast consumer needs a group per instance, " +
                            "starting with SERVICE_NAME: '$serviceName-<purpose>-<instance>' (see example/BroadcastExample.kt)."
                    id !in broadcastIds && group != serviceName ->
                        "Kafka listener '$id' has consumer group '$group', not SERVICE_NAME '$serviceName'. " +
                            "@KafkaListener(id = \"$id\") makes the id the group unless you add idIsGroup = false: " +
                            "write @KafkaListener(id = \"$id\", idIsGroup = false, ...), and never set groupId. " +
                            "A consumer every instance must run in full uses containerFactory = \"broadcastListenerFactory\" (example/BroadcastExample.kt)."
                    else -> null
                }
            }
    }
}
