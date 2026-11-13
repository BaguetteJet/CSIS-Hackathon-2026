package ie.ul.cs4297.groupbuy.service.support

import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.ServiceApplication
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Tag
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import java.time.Duration
import java.util.Properties
import java.util.UUID

/**
 * The base for tests that need real MySQL and Kafka (Testcontainers; Docker must be running).
 * Extend it, and the service starts once against both, with the same images as the dev kit:
 *
 * ```
 * class MyConsumerTest : ContainersTest() {
 *     @Test fun `a repeated event changes nothing`() { ... }
 * }
 * ```
 *
 * The containers start once per test run and are shared by every test class. The seven topics
 * exist with 6 partitions each, and the broker does not create topics by itself, as in the dev kit.
 * Run these with ./gradlew containerTest (CI runs only ./gradlew test).
 */
@Tag("containers")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") // the only place a service may start without CLOCK_URL (it runs on TestClock)
@Import(TestSupportConfig::class)
abstract class ContainersTest {

    companion object {
        const val SERVICE = "platform"

        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0.46")
            .withDatabaseName(SERVICE).withUsername(SERVICE).withPassword(SERVICE)
            .withReuse(false)

        val kafka: ConfluentKafkaContainer = ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")

        init {
            mysql.start()
            kafka.start()
            AdminClient.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers)).use { admin ->
                admin.createTopics(Topics.ALL.map { NewTopic(it, Topics.PARTITIONS, 1.toShort()) }).all().get()
            }
        }

        /** The settings the dev kit would give a service, as environment variables. */
        fun settings(): Map<String, String> = mapOf(
            "SERVICE_NAME" to SERVICE,
            "DB_URL" to mysql.jdbcUrl,
            "DB_USER" to mysql.username,
            "DB_PASSWORD" to mysql.password,
            "KAFKA_BOOTSTRAP_SERVERS" to kafka.bootstrapServers,
            "JWT_PUBLIC_KEY_URL" to "http://not-used-in-tests/jwks.json",
            "CLOCK_URL" to "",
            "INSTANCE_ID" to "one",
        )

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            settings().forEach { (k, v) -> registry.add(k) { v } }
        }

        /**
         * Starts a second instance of the service against the same MySQL and Kafka, as the
         * integration environment runs two. Close it to stop it.
         */
        fun startSecondInstance(): ConfigurableApplicationContext =
            SpringApplicationBuilder(ServiceApplication::class.java, TestSupportConfig::class.java)
                .profiles("test")
                .properties(*(settings().map { (k, v) -> "$k=$v" } + listOf("INSTANCE_ID=two", "SERVER_PORT=0")).toTypedArray())
                .run()

        /** A plain consumer that reads [topic] from the start, for checking what was published. */
        fun readTopic(topic: String, until: (List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String?>>) -> Boolean, timeout: Duration = Duration.ofSeconds(30)): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String?>> {
            val props = Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-${UUID.randomUUID()}")
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            }
            KafkaConsumer(props, StringDeserializer(), StringDeserializer()).use { consumer ->
                consumer.subscribe(listOf(topic))
                val seen = mutableListOf<org.apache.kafka.clients.consumer.ConsumerRecord<String, String?>>()
                val end = System.nanoTime() + timeout.toNanos()
                while (System.nanoTime() < end && !until(seen)) {
                    consumer.poll(Duration.ofMillis(500)).forEach { seen += it }
                }
                return seen
            }
        }
    }
}
