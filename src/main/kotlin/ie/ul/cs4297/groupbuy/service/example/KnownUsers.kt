package ie.ul.cs4297.groupbuy.service.example

import ie.ul.cs4297.groupbuy.contracts.ContractEvent
import ie.ul.cs4297.groupbuy.contracts.Envelope
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.contracts.events.RoleChanged
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.contracts.events.UserDeleted
import ie.ul.cs4297.groupbuy.contracts.events.UserRegistered
import ie.ul.cs4297.groupbuy.contracts.events.UserUpdated
import ie.ul.cs4297.groupbuy.service.ledger.LedgerAccounts
import ie.ul.cs4297.groupbuy.service.platform.EventInbox
import ie.ul.cs4297.groupbuy.service.platform.ParkedEvents
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

// EXAMPLE: a consumer of users.v1 that keeps the users it has heard of (table known_user, V2
// migration). It shows the three consumer patterns every service needs:
//
//   1. once:    each event's effect runs once, however often the event arrives;
//   2. parking: an event that arrives before the one it depends on waits, then is applied;
//   3. ignore:  tombstones and event types this service does not know are skipped.
//
// users.v1 is the platform's topic, so this is not any team's logic. Keep it if your service needs
// names or roles; delete it (and drop the table in your own migration) if not.

@Entity
@Table(name = "known_user")
class KnownUser(
    @Id @Column(name = "user_id") var userId: String = "",
    var name: String = "",
    var email: String = "",
    var role: String = "",
    @Column(name = "restaurant_id") var restaurantId: String? = null,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
)

interface KnownUserRepository : JpaRepository<KnownUser, String>

@Component
class KnownUsersConsumer(
    private val inbox: EventInbox,
    private val parking: ParkedEvents,
    private val users: KnownUserRepository,
    private val clock: Clock,
    private val accounts: LedgerAccounts,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** This consumer's name in the processed-events and parking tables. */
        const val CONSUMER = "known-users"
    }

    // The group is SERVICE_NAME (application.yml), shared by both instances: each event is
    // processed once per service. idIsGroup = false keeps it so: without it Spring Kafka would use
    // the id as the group (ConsumerGroupCheck stops the service if that happens).
    @KafkaListener(id = CONSUMER, idIsGroup = false, topics = [Topics.USERS_V1])
    fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
        val envelope = record.value() ?: return // a tombstone, or an event type we do not know
        inbox.once(CONSUMER, envelope) { apply(envelope) }
    }

    /** Applies one event. Runs inside the inbox's transaction. */
    private fun apply(envelope: Envelope<ContractEvent>) {
        when (val e = envelope.payload) {
            is UserRegistered -> {
                users.save(KnownUser(e.userId.toString(), e.name, e.email, e.role.name, e.restaurantId?.toString(), clock.instant()))
                // Ledger (LED-01): open the student's account here, since this is the one users.v1 listener.
                if (e.role == Role.STUDENT) accounts.openStudent(e.userId)
                // Anything that arrived early for this user can be applied now.
                parking.release(CONSUMER, "user:${e.userId}").forEach { apply(it) }
            }
            is UserUpdated -> update(envelope, e.userId.toString()) { it.name = e.name; it.email = e.email }
            is RoleChanged -> update(envelope, e.userId.toString()) { it.role = e.role.name; it.restaurantId = e.restaurantId?.toString() }
            is UserDeleted -> users.deleteById(e.userId.toString())
            else -> {} // other users.v1 events: nothing to do
        }
    }

    /** Changes a known user, or parks the event until UserRegistered for that user arrives. */
    private fun update(envelope: Envelope<ContractEvent>, userId: String, change: (KnownUser) -> Unit) {
        val user = users.findById(userId).orElse(null)
        if (user == null) {
            log.info("{} for unknown user {}: parked until UserRegistered", envelope.type, userId)
            parking.park(CONSUMER, "user:$userId", envelope)
            return
        }
        change(user)
        user.updatedAt = clock.instant()
        users.save(user)
    }
}
