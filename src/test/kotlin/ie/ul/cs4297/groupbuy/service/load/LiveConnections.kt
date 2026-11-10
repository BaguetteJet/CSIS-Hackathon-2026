package ie.ul.cs4297.groupbuy.service.load

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.system.exitProcess

/**
 * The 2,000-connection live-update test (Student experience): opens many server-sent-event
 * connections to one deal's live stream at once, keeps them open, and checks that every one
 * ends on the final count (spec C3, Student experience: "every one must end on the final count").
 *
 *   ./gradlew liveConnections -Purl=http://localhost:8080/api/experience/deals/<dealId>/live -Pconnections=2000 -Pseconds=60
 *   ./gradlew liveConnections -Purl=... -Pfinal=50      the final count is 50 confirmed
 *
 * Run it through the gateway (port 8080), which must not buffer this path. Start a rush-to-cutoff
 * scenario at the same time so the count moves, and let it finish before the run ends.
 *
 * The final count: with `-Pfinal=<n>`, `confirmed` must be n. Without it, the script opens one more
 * connection at the end and takes the first count it is sent (the deal's current count) as the final
 * one; then `confirmed`, `pending` and `placesLeft` must all match it. Each connection may take up to
 * [SETTLE] after the run to catch up.
 *
 * Passing means every connection stayed open, received at least one count, and its last count is the
 * final count. The connections that did not are listed.
 */
fun main(args: Array<String>) {
    val url = URI.create(args.getOrElse(0) { "http://localhost:8080/api/experience/deals/00000000-0000-0000-0000-000000000000/live" })
    val connections = args.getOrElse(1) { "2000" }.toInt()
    val seconds = args.getOrElse(2) { "60" }.toLong()
    val final = args.getOrElse(3) { "" }.trim().takeIf { it.isNotEmpty() }?.toInt()
    val outcome = LiveConnections.run(url, connections, Duration.ofSeconds(seconds), final, ::println)
    exitProcess(if (outcome.pass) 0 else 1)
}

/** The parts of a LiveCount the test compares. */
data class Count(val confirmed: Int?, val pending: Int?, val placesLeft: Int?) {
    override fun toString() = "confirmed $confirmed, pending $pending, places left $placesLeft"
}

object LiveConnections {
    /** How long a connection may take after the run to reach the final count (updates come at most once a second). */
    val SETTLE: Duration = Duration.ofSeconds(5)
    private const val LISTED = 20
    private val json = ObjectMapper()

    data class Outcome(
        val connections: Int,
        val stillOpen: Int,
        val receivedData: Int,
        val failed: Int,
        val finalCount: Count?,
        /** Connection number (1-based) to its last count, or null when it received none, for every one not on the final count. */
        val notOnFinal: Map<Int, Count?>,
        val pass: Boolean,
    )

    /** One count from an event's data (a LiveCount as JSON), or null when it is not one. */
    fun parse(data: String): Count? = runCatching {
        val n = json.readTree(data)
        if (!n.isObject) return null
        fun int(f: String) = n.get(f)?.takeIf { it.isNumber }?.asInt()
        Count(int("confirmed"), int("pending"), int("placesLeft")).takeIf { it.confirmed != null }
    }.getOrNull()

    /** Whether a connection's last count is the final one: `confirmed` alone against `-Pfinal`, every field against a count read at the end. */
    fun onFinal(last: Count?, finalConfirmed: Int?, finalCount: Count?): Boolean = when {
        last == null -> false
        finalConfirmed != null -> last.confirmed == finalConfirmed
        finalCount != null -> last == finalCount
        else -> false
    }

    fun run(url: URI, connections: Int, duration: Duration, finalConfirmed: Int?, log: (String) -> Unit): Outcome {
        val http = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        val open = AtomicInteger()
        val gotData = AtomicInteger()
        val failed = AtomicInteger()
        val errors = ConcurrentHashMap<String, AtomicInteger>()
        val last = AtomicReferenceArray<Count?>(connections)

        log("Opening $connections connections to $url for ${duration.seconds} seconds ...")
        val started = System.nanoTime()
        val futures = (0 until connections).map { i ->
            val request = HttpRequest.newBuilder(url).header("Accept", "text/event-stream").GET().build()
            http.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).thenAccept { response ->
                if (response.statusCode() != 200) {
                    failed.incrementAndGet()
                    errors.computeIfAbsent("HTTP ${response.statusCode()}") { AtomicInteger() }.incrementAndGet()
                    return@thenAccept
                }
                open.incrementAndGet()
                try {
                    readEvents(response.body().iterator()) { data ->
                        val c = parse(data) ?: return@readEvents
                        if (last.getAndSet(i, c) == null) gotData.incrementAndGet()
                    }
                } finally {
                    open.decrementAndGet()
                }
            }.exceptionally { e ->
                failed.incrementAndGet()
                errors.computeIfAbsent(e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName) { AtomicInteger() }.incrementAndGet()
                null
            }
        }

        val end = started + duration.toNanos()
        while (System.nanoTime() < end) {
            Thread.sleep(minOf(5_000L, maxOf(1L, (end - System.nanoTime()) / 1_000_000)))
            log("  after ${(System.nanoTime() - started) / 1_000_000_000} s: ${open.get()} open, ${gotData.get()} received data, ${failed.get()} failed")
        }
        val stillOpen = open.get()

        // The final count: given, or read from one fresh connection now.
        val finalCount = if (finalConfirmed != null) null else readCurrent(http, url)
        if (finalConfirmed == null && finalCount == null) log("Could not read the final count from a fresh connection to $url: give it with -Pfinal=<confirmed>")
        // Let the last update reach everyone.
        val settleEnd = System.nanoTime() + SETTLE.toNanos()
        fun behind() = (0 until connections).filter { !onFinal(last.get(it), finalConfirmed, finalCount) }
        while (behind().isNotEmpty() && System.nanoTime() < settleEnd) Thread.sleep(250)
        val notOnFinal = behind().associate { (it + 1) to last.get(it) }

        val target = finalConfirmed?.let { "confirmed $it" } ?: finalCount?.toString() ?: "unknown"
        log("")
        log("Live connections: $stillOpen of $connections still open after ${duration.seconds} s; ${gotData.get()} received data; ${failed.get()} failed")
        if (errors.isNotEmpty()) log("Failures: " + errors.entries.joinToString { "${it.key} x${it.value.get()}" })
        log("Final count: $target; ${connections - notOnFinal.size} of $connections connections ended on it")
        if (notOnFinal.isNotEmpty()) {
            log("Not on the final count (${notOnFinal.size}):")
            notOnFinal.entries.take(LISTED).forEach { (n, c) -> log("  connection $n: ${c?.let { "last count $it" } ?: "no count received"}") }
            if (notOnFinal.size > LISTED) log("  ... and ${notOnFinal.size - LISTED} more")
        }
        val pass = stillOpen == connections && gotData.get() == connections && notOnFinal.isEmpty()
        log(if (pass) "PASS" else "FAIL: every connection must stay open, receive a count, and end on the final count")
        futures.forEach { it.cancel(true) }
        http.shutdownNow()
        return Outcome(connections, stillOpen, gotData.get(), failed.get(), finalCount, notOnFinal, pass)
    }

    /** The first count a fresh connection is sent: the deal's current count. */
    private fun readCurrent(http: HttpClient, url: URI): Count? = runCatching {
        val request = HttpRequest.newBuilder(url).header("Accept", "text/event-stream").GET().build()
        val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).thenApply { response ->
            if (response.statusCode() != 200) return@thenApply null
            var found: Count? = null
            val lines = response.body().iterator()
            try {
                readEvents(lines) { data -> if (found == null) found = parse(data); if (found != null) throw Done }
            } catch (_: Done) {
            } finally {
                response.body().close()
            }
            found
        }
        future.get(10, TimeUnit.SECONDS)
    }.getOrNull()

    private object Done : RuntimeException() { private fun readResolve(): Any = Done }

    /** Server-sent events: `data:` lines up to a blank line make one event's data. */
    fun readEvents(lines: Iterator<String>, onData: (String) -> Unit) {
        val data = StringBuilder()
        var has = false
        for (line in lines) {
            when {
                line.isEmpty() -> { if (has) onData(data.toString()); data.setLength(0); has = false }
                line.startsWith("data:") -> {
                    if (has) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                    has = true
                }
            }
        }
        if (has) onData(data.toString())
    }
}
