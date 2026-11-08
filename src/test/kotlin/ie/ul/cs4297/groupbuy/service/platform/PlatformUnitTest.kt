package ie.ul.cs4297.groupbuy.service.platform

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import ie.ul.cs4297.groupbuy.contracts.api.HoldRequest
import ie.ul.cs4297.groupbuy.contracts.api.HoldResult
import ie.ul.cs4297.groupbuy.contracts.events.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

// Fast tests, no Docker: the hold client, the platform clock and the access helpers.
class PlatformUnitTest {

    private var server: HttpServer? = null

    @AfterEach
    fun stop() = server?.stop(0) ?: Unit

    /** A tiny HTTP server on a free port answering [path] with [answer] after [delayMillis]. */
    private fun serve(path: String, delayMillis: Long = 0, status: Int = 200, answer: () -> String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(path) { exchange ->
            Thread.sleep(delayMillis)
            val body = answer().toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.executor = java.util.concurrent.Executors.newCachedThreadPool()
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun hold(orderId: UUID = UUID.randomUUID()) =
        HoldRequest(orderId, UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-11-14T10:30:00Z"), 600)

    @Test
    fun `the HTTP hold client reads the Ledger's answer`() {
        val url = serve("/internal/ledger/holds") { """{"orderId":"${UUID.randomUUID()}","result":"INSUFFICIENT_FUNDS","availableCents":250}""" }
        val answer = HttpHoldClient(url).placeHold(hold())
        assertEquals(HoldResult.INSUFFICIENT_FUNDS, answer.result)
        assertEquals(250L, answer.availableCents)
    }

    @Test
    fun `the HTTP hold client gives up after 2 seconds`() {
        val url = serve("/internal/ledger/holds", delayMillis = 3_000) { "{}" }
        val started = System.nanoTime()
        assertThrows<HoldUnavailable> { HttpHoldClient(url).placeHold(hold()) }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMillis in 1_900..2_900, "took $tookMillis ms")
    }

    @Test
    fun `the HTTP hold client reports a Ledger that is down`() {
        assertThrows<HoldUnavailable> { HttpHoldClient("http://127.0.0.1:1").placeHold(hold()) }
        val url = serve("/internal/ledger/holds", status = 500) { "boom" }
        assertThrows<HoldUnavailable> { HttpHoldClient(url).placeHold(hold()) }
    }

    @Test
    fun `the stub gives the same answer for the same order, and has each mode`() {
        val stub = StubHoldClient("placed")
        val order = UUID.randomUUID()
        assertEquals(HoldResult.PLACED, stub.placeHold(hold(order)).result)
        stub.mode = "insufficient"
        assertEquals(HoldResult.PLACED, stub.placeHold(hold(order)).result, "same orderId, same answer")
        assertEquals(HoldResult.INSUFFICIENT_FUNDS, stub.placeHold(hold()).result)
        stub.mode = "down"
        assertThrows<HoldUnavailable> { stub.placeHold(hold()) }
    }

    @Test
    fun `the platform clock follows the clock service, in JSON or plain text`() {
        val ahead = Instant.now().plusSeconds(3_600)
        for (body in listOf("""{"now":"$ahead"}""", ahead.toString())) {
            val url = serve("/internal/clock") { body }
            val clock = PlatformClock()
            PlatformClockSync(clock, settings(clockUrl = "$url/internal/clock"), ObjectMapper()).refresh()
            assertTrue(abs(clock.instant().toEpochMilli() - ahead.toEpochMilli()) < 1_000, "clock reads the platform time from '$body'")
            server!!.stop(0)
        }
    }

    @Test
    fun `an unreachable clock keeps the last offset`() {
        val clock = PlatformClock()
        assertDoesNotThrow { PlatformClockSync(clock, settings(clockUrl = "http://127.0.0.1:1/internal/clock"), ObjectMapper()).refresh() }
        assertEquals(0, clock.offset().toMillis())
    }

    @Test
    fun `access helpers answer 404 for someone else's things and 403 for the wrong role`() {
        val me = UUID.randomUUID()
        val restaurant = UUID.randomUUID()
        val student = CurrentUser(me, Role.STUDENT, null)
        assertDoesNotThrow { student.requireOwner(me, "Order") }
        assertEquals("NOT_FOUND", assertThrows<NotFound> { student.requireOwner(UUID.randomUUID(), "Order") }.code)
        assertThrows<NotFound> { student.requireRestaurant(restaurant, "Deal") }

        val staff = CurrentUser(UUID.randomUUID(), Role.RESTAURANT_STAFF, restaurant)
        assertDoesNotThrow { staff.requireRestaurant(restaurant, "Deal") }
        assertThrows<NotFound> { staff.requireRestaurant(UUID.randomUUID(), "Deal") }

        assertEquals("FORBIDDEN", assertThrows<Forbidden> { student.requireRole(Role.UNION_ADMIN) }.code)
        assertDoesNotThrow { CurrentUser(me, Role.UNION_ADMIN, null).requireRole(Role.UNION_ADMIN, Role.UNION_SUPPORT) }
    }

    @Test
    fun `SERVICE_NAME must be one of the eight`() {
        assertThrows<IllegalArgumentException> { settings(serviceName = "orderz") }
        assertDoesNotThrow { settings(serviceName = "restaurant-ops") }
    }

    private fun settings(clockUrl: String = "", serviceName: String = "platform") = PlatformSettings(serviceName = serviceName, instanceId = "test", clockUrl = clockUrl)
}
