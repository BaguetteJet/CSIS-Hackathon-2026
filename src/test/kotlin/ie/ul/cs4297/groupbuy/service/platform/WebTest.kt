package ie.ul.cs4297.groupbuy.service.platform

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.support.ContainersTest
import ie.ul.cs4297.groupbuy.service.support.TestTokens
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Endpoints that exist only in this test, to exercise the helpers the way a team's controller would. */
@TestConfiguration(proxyBeanMethods = false)
@Import(WebTest.TestController::class)
class WebTestConfig

@Import(WebTestConfig::class)
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability // tests switch metrics export off unless asked
class WebTest : ContainersTest() {

    @LocalServerPort var port = 0
    @Autowired lateinit var mapper: ObjectMapper

    data class TopUp(@field:NotBlank val studentId: String, @field:Min(500) val amountCents: Long)

    @RestController
    class TestController(private val idempotent: IdempotentRequests) {
        val runs = AtomicInteger()

        @PostMapping("/api/test/top-ups")
        fun topUp(@RequestHeader("Idempotency-Key") key: String, @Valid @RequestBody body: TopUp, user: CurrentUser) =
            idempotent.handle(key, user.userId.toString(), body, HttpStatus.CREATED) {
                mapOf("topUpId" to UUID.randomUUID().toString(), "run" to runs.incrementAndGet(), "amountCents" to body.amountCents)
            }

        @PostMapping("/api/test/students/{studentId}/thing")
        fun own(@PathVariable studentId: UUID, user: CurrentUser): Map<String, String> {
            user.requireOwner(studentId, "Thing for $studentId")
            return mapOf("ok" to "yes")
        }

        @PostMapping("/api/test/admin-only")
        fun admin(user: CurrentUser): Map<String, String> {
            user.requireRole(Role.UNION_ADMIN)
            return mapOf("ok" to "yes")
        }
    }

    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun call(method: String, path: String, token: String? = null, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) b.header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(r: HttpResponse<String>) = mapper.readTree(r.body())

    @Test
    fun `hello answers with the user from the token`() {
        val student = UUID.randomUUID()
        val r = call("GET", "/api/$SERVICE/hello", TestTokens.student(student))
        assertEquals(200, r.statusCode(), r.body())
        assertEquals(student.toString(), json(r)["userId"].asText())
        assertEquals("STUDENT", json(r)["role"].asText())
        assertEquals(SERVICE, json(r)["service"].asText())
    }

    @Test
    fun `no token is 401 with a Problem, never a redirect to a login page`() {
        val r = call("GET", "/api/$SERVICE/hello")
        assertEquals(401, r.statusCode())
        assertTrue(r.headers().firstValue("Location").isEmpty)
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("application/problem+json"))
        assertEquals("UNAUTHENTICATED", json(r)["code"].asText())
        assertEquals(401, json(r)["status"].asInt())
    }

    @Test
    fun `a forged or expired token is 401`() {
        val forged = TestTokens.student().dropLast(4) + "AAAA"
        assertEquals(401, call("GET", "/api/$SERVICE/hello", forged).statusCode())
        val expired = TestTokens.token(UUID.randomUUID(), Role.STUDENT, expiresIn = java.time.Duration.ofMinutes(-5))
        assertEquals(401, call("GET", "/api/$SERVICE/hello", expired).statusCode())
    }

    @Test
    fun `an expired token is ignored on paths that need none, and still 401 elsewhere`() {
        val expired = TestTokens.token(UUID.randomUUID(), Role.STUDENT, expiresIn = java.time.Duration.ofMinutes(-5))
        assertEquals(200, call("GET", "/actuator/health", expired).statusCode())
        assertEquals(200, call("GET", "/app/$SERVICE/", expired).statusCode())
        val r = call("GET", "/api/$SERVICE/hello", expired)
        assertEquals(401, r.statusCode())
        assertTrue(r.headers().firstValue("Location").isEmpty, "401, never a redirect")
    }

    @Test
    fun `health and metrics need no token`() {
        assertEquals(200, call("GET", "/actuator/health").statusCode())
        val metrics = call("GET", "/actuator/prometheus")
        assertEquals(200, metrics.statusCode())
        assertTrue(metrics.body().contains("outbox_unpublished"), "the outbox gauge is exported")
        assertTrue(metrics.body().contains("service=\"$SERVICE\""), "every metric is tagged with the service")
    }

    @Test
    fun `an unknown path is 404 NOT_FOUND`() {
        val r = call("GET", "/api/$SERVICE/no-such-thing", TestTokens.student())
        assertEquals(404, r.statusCode())
        assertEquals("NOT_FOUND", json(r)["code"].asText())
    }

    @Test
    fun `the same Idempotency-Key and body give the first answer, and the work runs once`() {
        val token = TestTokens.union(Role.UNION_FINANCE)
        val key = UUID.randomUUID().toString()
        val body = """{"studentId":"s-1","amountCents":2000}"""
        val first = call("POST", "/api/test/top-ups", token, body, mapOf("Idempotency-Key" to key))
        val again = call("POST", "/api/test/top-ups", token, """{"amountCents":2000,"studentId":"s-1"}""", mapOf("Idempotency-Key" to key))
        assertEquals(201, first.statusCode(), first.body())
        assertEquals(201, again.statusCode())
        assertEquals(json(first), json(again), "the repeat returns the first answer, same topUpId")
    }

    @Test
    fun `the same Idempotency-Key with a different body is 409 IDEMPOTENCY_KEY_REUSED`() {
        val token = TestTokens.union(Role.UNION_FINANCE)
        val key = UUID.randomUUID().toString()
        call("POST", "/api/test/top-ups", token, """{"studentId":"s-1","amountCents":2000}""", mapOf("Idempotency-Key" to key))
        val r = call("POST", "/api/test/top-ups", token, """{"studentId":"s-1","amountCents":3000}""", mapOf("Idempotency-Key" to key))
        assertEquals(409, r.statusCode())
        assertEquals("IDEMPOTENCY_KEY_REUSED", json(r)["code"].asText())
    }

    @Test
    fun `a missing Idempotency-Key or an invalid body is 422 VALIDATION_FAILED naming the field`() {
        val token = TestTokens.union(Role.UNION_FINANCE)
        val noKey = call("POST", "/api/test/top-ups", token, """{"studentId":"s-1","amountCents":2000}""")
        assertEquals(422, noKey.statusCode())
        assertEquals("Idempotency-Key", json(noKey)["errors"][0]["field"].asText())

        val tooSmall = call("POST", "/api/test/top-ups", token, """{"studentId":"s-1","amountCents":5}""", mapOf("Idempotency-Key" to "k-${UUID.randomUUID()}"))
        assertEquals(422, tooSmall.statusCode())
        assertEquals("amountCents", json(tooSmall)["errors"][0]["field"].asText())

        val missing = call("POST", "/api/test/top-ups", token, """{"studentId":"s-1"}""", mapOf("Idempotency-Key" to "k-${UUID.randomUUID()}"))
        assertEquals(422, missing.statusCode(), "a missing Kotlin non-null field is 422, not 500")
        assertEquals("VALIDATION_FAILED", json(missing)["code"].asText())
    }

    @Test
    fun `another student's resource is 404, never 403`() {
        val me = UUID.randomUUID()
        assertEquals(200, call("POST", "/api/test/students/$me/thing", TestTokens.student(me)).statusCode())
        val r = call("POST", "/api/test/students/${UUID.randomUUID()}/thing", TestTokens.student(me))
        assertEquals(404, r.statusCode())
        assertEquals("NOT_FOUND", json(r)["code"].asText())
    }

    @Test
    fun `the wrong role is 403 FORBIDDEN`() {
        assertEquals(200, call("POST", "/api/test/admin-only", TestTokens.union(Role.UNION_ADMIN)).statusCode())
        val r = call("POST", "/api/test/admin-only", TestTokens.student())
        assertEquals(403, r.statusCode())
        assertEquals("FORBIDDEN", json(r)["code"].asText())
    }

    @Test
    fun `the pages are served at app slash service, with the UI kit and api js`() {
        val page = call("GET", "/app/$SERVICE/")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("ui-kit.css"))
        assertEquals(200, call("GET", "/app/$SERVICE/api.js").statusCode())
        val css = call("GET", "/app/$SERVICE/ui-kit.css")
        assertEquals(200, css.statusCode())
        assertEquals("no-cache", css.headers().firstValue("Cache-Control").orElse(""), "browsers re-check pages after a deploy")
        assertEquals(302, call("GET", "/app/$SERVICE").statusCode())
    }

    @Test
    fun `a single-page app's deep link answers index html, but a missing file is still 404`() {
        val deep = call("GET", "/app/$SERVICE/deals/${UUID.randomUUID()}")
        assertEquals(200, deep.statusCode())
        assertTrue(deep.body().contains("ui-kit.css"), "the deep link is answered with index.html")
        assertEquals(404, call("GET", "/app/$SERVICE/assets/missing-file.js").statusCode())
    }

    @Test
    fun `a path without html answers the page of that name, query string and all`() {
        val page = call("GET", "/app/$SERVICE/complain?order=${UUID.randomUUID()}")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("test page: complain"), "answered with complain.html, not index.html")
    }
}
