package ie.ul.cs4297.groupbuy.service.platform

import ie.ul.cs4297.groupbuy.service.example.BroadcastExample
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.DefaultKafkaConsumerFactory

// No Docker: the listener containers are created but never started, so no broker is needed.
class ConsumerGroupCheckTest {

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    class Kafka {
        private fun factory() = ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            consumerFactory = DefaultKafkaConsumerFactory(mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "127.0.0.1:1",
                ConsumerConfig.GROUP_ID_CONFIG to "platform", // as application.yml: group-id ${SERVICE_NAME}
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ))
            setAutoStartup(false)
        }

        @Bean fun kafkaListenerContainerFactory() = factory()
        @Bean fun broadcastListeners() = BroadcastListeners()
        // Marks its containers as the real broadcastListenerFactory does.
        @Bean fun broadcastListenerFactory(broadcast: BroadcastListeners) =
            factory().apply { setContainerCustomizer { c -> c.listenerId?.let(broadcast::add) } }
        @Bean fun platformSettings() = PlatformSettings(serviceName = "platform", instanceId = "test")
    }

    class Good {
        @KafkaListener(id = "good", idIsGroup = false, topics = ["users.v1"])
        fun on(value: String) {}
    }

    class NoId {
        @KafkaListener(topics = ["users.v1"])
        fun on(value: String) {}
    }

    class IdIsGroup {
        @KafkaListener(id = "my-deals-listener", topics = ["users.v1"])
        fun on(value: String) {}
    }

    class Broadcast {
        @KafkaListener(id = "live", topics = ["users.v1"], groupId = "platform-live-1", containerFactory = "broadcastListenerFactory")
        fun on(value: String) {}
    }

    class HardCodedGroup {
        @KafkaListener(id = "other", idIsGroup = false, topics = ["users.v1"], groupId = "orders")
        fun on(value: String) {}
    }

    private fun context(vararg listeners: Class<*>) =
        AnnotationConfigApplicationContext().apply { register(Kafka::class.java, *listeners) }

    private fun problems(vararg listeners: Class<*>): List<String> {
        val ctx = context(*listeners)
        ctx.refresh()
        ctx.use {
            val registry = it.getBean(KafkaListenerEndpointRegistry::class.java)
            return ConsumerGroupCheck.problems(registry.allListenerContainers, "platform", it.getBean(BroadcastListeners::class.java).ids())
        }
    }

    @Test
    fun `listeners with idIsGroup false, or no id, and broadcast listeners pass`() {
        assertEquals(emptyList<String>(), problems(Good::class.java, NoId::class.java, Broadcast::class.java))
    }

    @Test
    fun `a listener id without idIsGroup false becomes the group, and is reported with the fix`() {
        val found = problems(Good::class.java, IdIsGroup::class.java)
        assertEquals(1, found.size, found.toString())
        assertTrue(found[0].contains("'my-deals-listener' has consumer group 'my-deals-listener', not SERVICE_NAME 'platform'"), found[0])
        assertTrue(found[0].contains("idIsGroup = false"), found[0])
    }

    @Test
    fun `a hard-coded group on a normal listener is reported`() {
        assertEquals(1, problems(HardCodedGroup::class.java).size)
    }

    @Test
    fun `the check stops the service before any listener starts`() {
        val ctx = context(IdIsGroup::class.java, ConsumerGroupCheck::class.java)
        val e = assertThrows<Exception> { ctx.refresh() }
        val messages = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(messages.contains("idIsGroup = false"), messages)
    }

    @Test
    fun `the check lets a correct service start`() {
        val ctx = context(Good::class.java, Broadcast::class.java, ConsumerGroupCheck::class.java)
        ctx.refresh()
        ctx.close()
    }

    @Test
    fun `broadcast groups are named as in spec B, and the same at every start`() {
        assertEquals("experience-live-1", BroadcastExample.broadcastGroup("experience", "live", "experience-1"))
        assertEquals("experience-live-2", BroadcastExample.broadcastGroup("experience", "live", "experience-2"))
        assertEquals("platform-live-local-8081", BroadcastExample.broadcastGroup("platform", "live", "local-8081"))
    }
}
