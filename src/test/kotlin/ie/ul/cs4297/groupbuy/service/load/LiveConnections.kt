package ie.ul.cs4297.groupbuy.service.load

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * The 2,000-connection live-update test (Student experience): opens many server-sent-event
 * connections to one deal's live stream at once, keeps them open, and reports how many stayed
 * connected and received data.
 *
 *   ./gradlew liveConnections -Purl=http://localhost:8080/api/experience/deals/<dealId>/live -Pconnections=2000 -Pseconds=60
 *
 * Run it through the gateway (port 8080), which must not buffer this path. Start a rush-to-cutoff
 * scenario at the same time so the count moves. Passing means every connection stayed open and
 * every one received at least one event.
 */
fun main(args: Array<String>) {
    val url = URI.create(args.getOrElse(0) { "http://localhost:8080/api/experience/deals/00000000-0000-0000-0000-000000000000/live" })
    val connections = args.getOrElse(1) { "2000" }.toInt()
    val seconds = args.getOrElse(2) { "60" }.toLong()

    val http = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    val open = AtomicInteger()
    val gotData = AtomicInteger()
    val failed = AtomicInteger()
    val errors = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    println("Opening $connections connections to $url for $seconds seconds ...")
    val started = System.nanoTime()
    val futures = (1..connections).map {
        val request = HttpRequest.newBuilder(url).header("Accept", "text/event-stream").GET().build()
        http.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).thenAccept { response ->
            if (response.statusCode() != 200) {
                failed.incrementAndGet()
                errors.computeIfAbsent("HTTP ${response.statusCode()}") { AtomicInteger() }.incrementAndGet()
                return@thenAccept
            }
            open.incrementAndGet()
            var counted = false
            try {
                response.body().forEach { line ->
                    if (!counted && line.startsWith("data:")) { counted = true; gotData.incrementAndGet() }
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

    val end = started + Duration.ofSeconds(seconds).toNanos()
    while (System.nanoTime() < end) {
        Thread.sleep(5_000)
        println("  after ${(System.nanoTime() - started) / 1_000_000_000} s: ${open.get()} open, ${gotData.get()} received data, ${failed.get()} failed")
    }
    val stillOpen = open.get()
    println()
    println("Live connections: $stillOpen of $connections still open after $seconds s; ${gotData.get()} received data; ${failed.get()} failed")
    if (errors.isNotEmpty()) println("Failures: " + errors.entries.joinToString { "${it.key} x${it.value.get()}" })
    val pass = stillOpen == connections && gotData.get() == connections
    println(if (pass) "PASS" else "FAIL: every connection must stay open and receive at least one event")
    futures.forEach { it.cancel(true) }
    exitProcess(if (pass) 0 else 1)
}
