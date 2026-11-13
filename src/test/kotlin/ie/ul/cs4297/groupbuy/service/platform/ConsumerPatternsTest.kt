package ie.ul.cs4297.groupbuy.service.platform

import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.contracts.events.RoleChanged
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.contracts.events.UserRegistered
import ie.ul.cs4297.groupbuy.contracts.events.UserUpdated
import ie.ul.cs4297.groupbuy.service.example.KnownUserRepository
import ie.ul.cs4297.groupbuy.service.example.KnownUsersConsumer
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestClock
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.UUID

// Done when: an event published twice is processed once; an event arriving before its
// prerequisite is parked, then applied; a malformed message lands on the dead-letter topic and
// the consumer keeps going. Each test goes through real Kafka and MySQL, using the example
// users.v1 consumer.
class ConsumerPatternsTest : ContainersTest() {

    @Autowired lateinit var kafka: KafkaTemplate<String, String>
    @Autowired lateinit var users: KnownUserRepository
    @Autowired lateinit var parking: ParkedEvents
    @Autowired lateinit var inbox: EventInbox

    private fun send(envelope: Envelope<*>) = kafka.send(Topics.USERS_V1, envelope.key, ContractJson.write(envelope)).get()

    private fun registered(id: UUID, name: String, role: Role = Role.STUDENT) =
        Envelope.of(UserRegistered(id, name, "$name@studentmail.ul.ie".lowercase().replace(" ", "."), role), 1, TestClock.instant())

    @Test
    fun `an event published twice is processed once`() {
        val id = UUID.randomUUID()
        val first = registered(id, "Aoife Murphy")
        val rename = Envelope.of(UserUpdated(id, "Aoife Ní Mhurchú", "aoife@studentmail.ul.ie"), 2, TestClock.instant())
        send(first)
        send(rename)
        send(first) // the repeat: if it were processed again, the name would go back to "Aoife Murphy"
        val marker = UUID.randomUUID()
        send(registered(marker, "Marker"))

        await atMost Duration.ofSeconds(30) untilAsserted { assertTrue(users.existsById(marker.toString())) }
        assertEquals("Aoife Ní Mhurchú", users.findById(id.toString()).get().name)
    }

    @Test
    fun `the inbox runs an effect once per eventId and consumer`() {
        val envelope = registered(UUID.randomUUID(), "Once Only")
        var runs = 0
        assertTrue(inbox.once("inbox-test", envelope) { runs++ })
        assertFalse(inbox.once("inbox-test", envelope) { runs++ })
        assertTrue(inbox.once("another-consumer", envelope) { runs++ }) // each consumer keeps its own record
        assertEquals(2, runs)
    }

    @Test
    fun `an event arriving before its prerequisite is parked, then applied`() {
        val id = UUID.randomUUID()
        val restaurant = UUID.randomUUID()
        send(Envelope.of(RoleChanged(id, Role.RESTAURANT_STAFF, restaurant), 2, TestClock.instant())) // early

        await atMost Duration.ofSeconds(30) untilAsserted { assertEquals(1L, parking.count(KnownUsersConsumer.CONSUMER)) }
        assertFalse(users.existsById(id.toString()))

        send(registered(id, "Ciarán Walsh", Role.STUDENT)) // the prerequisite arrives

        await atMost Duration.ofSeconds(30) untilAsserted {
            val user = users.findById(id.toString()).orElseThrow()
            assertEquals("RESTAURANT_STAFF", user.role) // the parked RoleChanged was applied after UserRegistered
            assertEquals(restaurant.toString(), user.restaurantId)
        }
        assertEquals(0L, parking.count(KnownUsersConsumer.CONSUMER))
    }

    @Test
    fun `a malformed message lands on the dead-letter topic and the consumer keeps going`() {
        val badKey = "bad-${UUID.randomUUID()}"
        kafka.send(Topics.USERS_V1, badKey, "{ this is not an event").get()
        kafka.send(Topics.USERS_V1, "bad2-$badKey", """{"type":"UserRegistered","payload":{}}""").get() // valid JSON, missing fields
        val after = UUID.randomUUID()
        send(registered(after, "After The Bad One"))

        await atMost Duration.ofSeconds(30) untilAsserted { assertTrue(users.existsById(after.toString())) }

        val dead = readTopic("$SERVICE.dlt", until = { seen -> seen.count { it.key().endsWith(badKey) } >= 2 })
            .filter { it.key().endsWith(badKey) }
        // Both bad messages are there. Delivery is at least once, so a rebalance may add a copy.
        assertEquals(setOf(badKey, "bad2-$badKey"), dead.map { it.key() }.toSet(), "both bad messages are on $SERVICE.dlt")
        assertEquals("{ this is not an event", dead.first { it.key() == badKey }.value())
        val reason = dead.first().headers().lastHeader("kafka_dlt-exception-message")?.value()?.decodeToString()
        assertTrue(reason != null && reason.isNotBlank(), "the reason is in the kafka_dlt-exception-message header")
    }
}
