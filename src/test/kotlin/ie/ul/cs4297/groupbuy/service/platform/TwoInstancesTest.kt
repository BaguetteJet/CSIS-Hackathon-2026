package ie.ul.cs4297.groupbuy.service.platform

import ie.ul.cs4297.groupbuy.contracts.ContractJson
import ie.ul.cs4297.groupbuy.contracts.events.CountChanged
import ie.ul.cs4297.groupbuy.contracts.events.Topics
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestClock
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A timer handler for these tests: records each firing in a table, with the instance that fired it. */
@TestConfiguration(proxyBeanMethods = false)
class ProofTimerConfig {
    @Bean
    fun proofTimer(jdbc: JdbcTemplate, settings: PlatformSettings) = object : TimerHandler {
        override val type = "proof"
        override fun fire(timer: DueTimer) {
            jdbc.update("INSERT INTO timer_proof (timer_key, fired_by) VALUES (?, ?)", timer.key, settings.instanceId)
        }
    }
}

// Done when: a timer created on one instance fires exactly once when that instance is killed;
// with both instances publishing, one key's events arrive in order.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ProofTimerConfig::class)
class TwoInstancesTest : ContainersTest() {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var timers: Timers
    @Autowired lateinit var outbox: Outbox
    @Autowired lateinit var tx: TransactionTemplate

    private lateinit var two: ConfigurableApplicationContext

    @BeforeAll
    fun start() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS timer_proof (timer_key VARCHAR(200) NOT NULL, fired_by VARCHAR(100) NOT NULL)")
        two = SecondInstance.start()
    }

    @AfterAll
    fun stop() {
        if (two.isActive) two.close()
    }

    private fun firings(key: String) = jdbc.queryForList("SELECT fired_by FROM timer_proof WHERE timer_key = ?", String::class.java, key)

    @Test
    fun `with both instances running, each of 40 due timers fires exactly once`() {
        val prefix = "race-${UUID.randomUUID()}"
        repeat(40) { timers.schedule("proof", "$prefix-$it", TestClock.instant()) }

        await atMost Duration.ofSeconds(30) untilAsserted {
            assertEquals(40, jdbc.queryForObject("SELECT COUNT(DISTINCT timer_key) FROM timer_proof WHERE timer_key LIKE ?", Int::class.java, "$prefix-%"))
        }
        Thread.sleep(2_000) // give a second firing every chance to happen
        val counts = jdbc.queryForList("SELECT COUNT(*) FROM timer_proof WHERE timer_key LIKE ? GROUP BY timer_key", Int::class.java, "$prefix-%")
        assertEquals(List(40) { 1 }, counts, "every timer fired exactly once")
    }

    @Test
    fun `a timer created on one instance fires exactly once after that instance is gone`() {
        val key = "kill-${UUID.randomUUID()}"
        val other = SecondInstance.start("three")
        other.getBean(Timers::class.java).schedule("proof", key, TestClock.instant().plusSeconds(60))
        other.close() // the instance that created the timer is gone before it is due

        TestClock.advance(Duration.ofSeconds(61))
        await atMost Duration.ofSeconds(30) untilAsserted { assertEquals(1, firings(key).size) }
        Thread.sleep(2_000)
        assertEquals(1, firings(key).size, "fired exactly once")
        assertEquals("FIRED", timers.status("proof", key))
    }

    @Test
    fun `with both instances publishing, one key's events arrive in order`() {
        val dealId = UUID.randomUUID()
        val outboxTwo = two.getBean(Outbox::class.java)
        val txTwo = two.getBean(TransactionTemplate::class.java)
        val pool = Executors.newFixedThreadPool(8)
        repeat(100) { i ->
            val (o, t) = if (i % 2 == 0) outbox to tx else outboxTwo to txTwo
            pool.submit { t.executeWithoutResult { o.publish(CountChanged(dealId, i, 0, 50 - (i % 50))) } }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)

        val received = readTopic(Topics.ORDERS_V1, until = { seen -> seen.count { it.key() == dealId.toString() } >= 100 })
            .filter { it.key() == dealId.toString() }
            .map { ContractJson.read(it.value()!!)!! }
        assertEquals((1L..100L).toList(), received.map { it.sequence }, "one key: sequences 1 to 100, in order, none missing or repeated")
        assertEquals(setOf(1), received.map { it.version }.toSet())
    }
}

/** Starts extra instances with the proof timer handler. */
object SecondInstance {
    private val started = Collections.synchronizedList(mutableListOf<ConfigurableApplicationContext>())

    fun start(id: String = "two"): ConfigurableApplicationContext =
        org.springframework.boot.builder.SpringApplicationBuilder(
            ie.ul.cs4297.groupbuy.service.ServiceApplication::class.java,
            ie.ul.cs4297.groupbuy.service.support.TestSupportConfig::class.java,
            ProofTimerConfig::class.java,
        ).profiles("test").properties(*(ContainersTest.settings().map { (k, v) -> "$k=$v" } + listOf("INSTANCE_ID=$id", "SERVER_PORT=0")).toTypedArray())
            .run().also { started += it }
}
