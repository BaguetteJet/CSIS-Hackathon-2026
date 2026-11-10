package ie.ul.cs4297.groupbuy.service.load

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live-connections script against a small stream served here: it passes only when every
 * connection's last count is the final count, and it names the ones that are not.
 */
class LiveConnectionsTest {
    private val dealId = "6f1c2a7e-0000-4000-8000-000000000001"
    private var server: HttpServer? = null
    @Volatile private var stopped = false

    @AfterEach
    fun stop() { stopped = true; server?.stop(0) }

    private fun count(confirmed: Int) = """{"dealId":"$dealId","confirmed":$confirmed,"pending":0,"placesLeft":${50 - confirmed}}"""

    /** A live stream that sends 1 confirmed, then 3; the connections [stale] picks never get the 3 (only fresh ones do, at the end). */
    private fun serve(stale: (Int) -> Boolean = { false }): URI {
        val n = AtomicInteger()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.executor = Executors.newVirtualThreadPerTaskExecutor()
        val moved = java.util.concurrent.atomic.AtomicBoolean(false)
        s.createContext("/live") { ex ->
            val i = n.incrementAndGet()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                fun send(data: String) { out.write("event: count\ndata: $data\n\n".toByteArray()); out.flush() }
                send(count(if (moved.get()) 3 else 1))
                Thread.sleep(300)
                moved.set(true)
                if (!stale(i)) send(count(3))
                while (!stopped) Thread.sleep(50)
            }
        }
        s.start()
        server = s
        return URI.create("http://127.0.0.1:${s.address.port}/live")
    }

    @Test
    fun `every connection on the final count, read at the end, passes`() {
        val o = LiveConnections.run(serve(), 20, Duration.ofSeconds(1), null) {}
        assertEquals(Count(3, 0, 47), o.finalCount)
        assertTrue(o.notOnFinal.isEmpty(), "${o.notOnFinal}")
        assertTrue(o.pass)
    }

    @Test
    fun `a connection left on an old count fails and is named, though it received an event`() {
        val o = LiveConnections.run(serve(stale = { it == 4 || it == 7 }), 10, Duration.ofSeconds(1), null) {}
        assertEquals(10, o.receivedData)                     // the old rule (at least one event) would pass
        assertEquals(2, o.notOnFinal.size, "${o.notOnFinal}")
        assertTrue(o.notOnFinal.values.all { it == Count(1, 0, 49) })
        assertFalse(o.pass)
    }

    @Test
    fun `the final count given with -Pfinal is checked against confirmed`() {
        val uri = serve()
        assertTrue(LiveConnections.run(uri, 5, Duration.ofSeconds(1), 3) {}.pass)
        val wrong = LiveConnections.run(uri, 5, Duration.ofSeconds(1), 4) {}
        assertFalse(wrong.pass)
        assertEquals(5, wrong.notOnFinal.size)
        assertNull(wrong.finalCount)
    }

    @Test
    fun `server-sent events are read as whole events, and only LiveCounts count`() {
        val got = mutableListOf<String>()
        LiveConnections.readEvents(listOf(": comment", "event: count", "data: {\"confirmed\":", "data: 2}", "", "data: hello", "").iterator()) { got += it }
        assertEquals(listOf("{\"confirmed\":\n2}", "hello"), got)
        assertEquals(Count(2, null, null), LiveConnections.parse(got[0]))
        assertNull(LiveConnections.parse("hello"))
        assertNull(LiveConnections.parse("{\"pending\":1}"))
    }
}
