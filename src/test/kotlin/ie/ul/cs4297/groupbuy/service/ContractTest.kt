package ie.ul.cs4297.groupbuy.service

import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.contracts.events.UserRegistered
import ie.ul.cs4297.groupbuy.contracts.testkit.ContractSamples
import ie.ul.cs4297.groupbuy.contracts.testkit.assertMatchesContract
import ie.ul.cs4297.groupbuy.service.platform.EnvelopeDeserializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Contract tests: every event your service publishes matches the contracts library, and every
 * event it consumes can be read. CI runs these on every push, so a broken event never reaches the line.
 *
 * Add one test per event you publish: build it the way your code does, then assertMatchesContract.
 * The two below are examples (the starter publishes nothing itself).
 */
@Tag("contract")
class ContractTest {

    @Test
    fun `an event built in code matches the contract`() {
        val event = UserRegistered(UUID.randomUUID(), "Aoife Murphy", "aoife@studentmail.ul.ie", Role.STUDENT)
        assertMatchesContract(Envelope.of(event, sequence = 1, occurredAt = Instant.parse("2026-11-14T12:00:00Z")))
    }

    @Test
    fun `the service can read every event type it consumes`() {
        // Replace with the event types your service consumes.
        for (type in listOf("UserRegistered", "UserUpdated", "RoleChanged", "UserDeleted")) {
            val envelope = EnvelopeDeserializer().deserialize("users.v1", ContractSamples.json(type).toByteArray())
            assertEquals(type, envelope!!.type)
        }
    }
}
