package ie.ul.cs4297.groupbuy.service.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import ie.ul.cs4297.groupbuy.contracts.events.Role
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Tokens for tests, signed like the dev sign-in's: `sub` is the user ID, `role` the role and
 * `restaurantId` set for restaurant staff.
 *
 * ```
 * mockMvc.get("/api/orders/orders/$id") { header("Authorization", "Bearer ${TestTokens.student(studentId)}") }
 * ```
 */
object TestTokens {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    val publicKey: RSAPublicKey = keys.public as RSAPublicKey

    fun token(userId: UUID, role: Role, restaurantId: UUID? = null, expiresIn: Duration = Duration.ofHours(24)): String {
        val claims = JWTClaimsSet.Builder()
            .subject(userId.toString())
            .claim("role", role.name)
            .apply { if (restaurantId != null) claim("restaurantId", restaurantId.toString()) }
            .issueTime(Date())
            .expirationTime(Date.from(Instant.now().plus(expiresIn)))
            .build()
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims).apply { sign(RSASSASigner(keys.private)) }.serialize()
    }

    fun student(userId: UUID = UUID.randomUUID()) = token(userId, Role.STUDENT)
    fun restaurantStaff(restaurantId: UUID, userId: UUID = UUID.randomUUID()) = token(userId, Role.RESTAURANT_STAFF, restaurantId)
    fun union(role: Role, userId: UUID = UUID.randomUUID()) = token(userId, role)
}

/**
 * A clock tests move by hand: never wait for a cut-off or a 15-minute timer, move time instead.
 *
 * ```
 * TestClock.advance(Duration.ofMinutes(15))   // and the timer is due
 * ```
 *
 * One clock for the whole test JVM, so two instances started in one test agree on the time.
 */
object TestClock : Clock() {
    private val now = AtomicReference(Instant.parse("2026-11-14T10:00:00Z"))
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now.get()
    fun set(instant: Instant) = now.set(instant)
    fun advance(by: Duration): Instant = now.updateAndGet { it.plus(by) }
}

/** Replaces the platform's public key with the test key, and the platform clock with [TestClock]. */
@TestConfiguration(proxyBeanMethods = false)
class TestSupportConfig {
    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(TestTokens.publicKey).build()

    @Bean
    @Primary
    fun testClock(): Clock = TestClock
}
