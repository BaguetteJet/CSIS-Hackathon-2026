package ie.ul.cs4297.groupbuy.service.example

import ie.ul.cs4297.groupbuy.service.platform.CurrentUser
import ie.ul.cs4297.groupbuy.service.platform.PlatformSettings
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant

/**
 * EXAMPLE: one endpoint, GET /api/<service>/hello. It shows a path under your service, the
 * signed-in user from the token, and the platform clock. Replace it with your own API.
 *
 * Through the gateway: curl -H "Authorization: Bearer <token>" http://localhost:8080/api/<service>/hello
 */
@RestController
class HelloController(private val settings: PlatformSettings, private val clock: Clock, private val users: KnownUserRepository) {

    data class Hello(val service: String, val instance: String, val userId: String, val role: String, val name: String?, val now: Instant)

    @GetMapping("/api/\${groupbuy.service-name}/hello")
    fun hello(user: CurrentUser): Hello = Hello(
        service = settings.serviceName,
        instance = settings.instanceId,
        userId = user.userId.toString(),
        role = user.role.name,
        name = users.findById(user.userId.toString()).map { it.name }.orElse(null), // from users.v1, once it has arrived
        now = clock.instant(),
    )
}
