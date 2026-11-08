package ie.ul.cs4297.groupbuy.service.platform

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.contracts.events.Role
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

/**
 * Who the user is (spec C1, "Who the user is"). Every request through the gateway carries the dev
 * sign-in's signed token; this service checks it with the platform's public key (JWT_PUBLIC_KEY_URL).
 *
 * Claims: `sub` is the user ID; `role` is STUDENT, RESTAURANT_STAFF, UNION_SUPPORT, UNION_FINANCE
 * or UNION_ADMIN; `restaurantId` is there for restaurant staff. Never take a user ID from a body.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    companion object {
        /**
         * Paths anyone may call without a token. Student experience adds its deal list, deal page
         * and live stream here, for example "/api/experience/deals/ **" (without the space).
         */
        val PUBLIC_PATHS: List<String> = listOf()
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity, mapper: ObjectMapper): SecurityFilterChain {
        val problems = ProblemEntryPoint(mapper)
        http
            .csrf { it.disable() }                     // tokens, not cookies: no CSRF
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                it.requestMatchers("/app/**").permitAll()      // the pages themselves; their API calls carry the token
                it.requestMatchers("/internal/**").permitAll() // inside the Docker network only; the gateway never routes it
                it.requestMatchers("/error").permitAll()
                if (PUBLIC_PATHS.isNotEmpty()) it.requestMatchers(*PUBLIC_PATHS.toTypedArray()).permitAll()
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer {
                it.jwt { jwt -> jwt.jwtAuthenticationConverter(::toAuthentication) }
                it.authenticationEntryPoint(problems)
                it.accessDeniedHandler(problems)
            }
            .exceptionHandling { it.authenticationEntryPoint(problems).accessDeniedHandler(problems) }
        return http.build()
    }

    /** The `role` claim becomes the authority ROLE_<role>, so @PreAuthorize("hasRole('UNION_ADMIN')") works. */
    private fun toAuthentication(jwt: Jwt): AbstractAuthenticationToken {
        val role = jwt.getClaimAsString("role")
        val authorities = listOfNotNull(role?.let { SimpleGrantedAuthority("ROLE_$it") })
        return JwtAuthenticationToken(jwt, authorities, jwt.subject)
    }
}

/**
 * The signed-in user. Add a `user: CurrentUser` parameter to a controller method and it is filled
 * from the token. Use `user: CurrentUser?` on a path anyone may call.
 *
 * ```
 * @GetMapping("/api/orders/orders/{orderId}")
 * fun get(@PathVariable orderId: UUID, user: CurrentUser): Order {
 *     val order = orders.find(orderId) ?: throw NotFound("Order $orderId")
 *     user.requireOwner(order.studentId, "Order $orderId")
 *     ...
 * }
 * ```
 */
data class CurrentUser(val userId: UUID, val role: Role, val restaurantId: UUID?) {

    /** Students see only their own things: another student's ID answers 404, so IDs cannot be probed. */
    fun requireOwner(ownerId: UUID, what: String) {
        if (ownerId != userId) throw NotFound(what)
    }

    /** Restaurant staff see only their own restaurant (the restaurantId in their token); others get 404. */
    fun requireRestaurant(restaurantId: UUID, what: String) {
        if (role != Role.RESTAURANT_STAFF || this.restaurantId != restaurantId) throw NotFound(what)
    }

    /** Only these roles may do this; anyone else gets 403 FORBIDDEN. */
    fun requireRole(vararg roles: Role) {
        if (role !in roles) throw Forbidden("This needs the role ${roles.joinToString(" or ")}")
    }

    companion object {
        /** The user of the current request, or null when it carries no valid token. */
        fun current(): CurrentUser? {
            val jwt = (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)?.token ?: return null
            val id = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
            val role = runCatching { Role.valueOf(jwt.getClaimAsString("role")) }.getOrNull() ?: return null
            val restaurant = jwt.getClaimAsString("restaurantId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            return CurrentUser(id, role, restaurant)
        }
    }
}

@Configuration
class CurrentUserConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += object : HandlerMethodArgumentResolver {
            override fun supportsParameter(p: MethodParameter) = p.parameterType == CurrentUser::class.java

            override fun resolveArgument(p: MethodParameter, mav: ModelAndViewContainer?, request: NativeWebRequest, binder: WebDataBinderFactory?): Any? {
                val user = CurrentUser.current()
                // A Kotlin non-null parameter with no token would be a 500 (the Week 7 trap): answer 401 instead.
                if (user == null && !p.isOptional) {
                    throw ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in again on the dev sign-in page: the token is missing, expired or has no valid sub and role")
                }
                return user
            }
        }
    }
}
