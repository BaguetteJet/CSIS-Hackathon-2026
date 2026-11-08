package ie.ul.cs4297.groupbuy.service.platform

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Errors as Problem Details (RFC 9457) with a machine-readable `code` (spec B, "Shared rules for
 * every API"). Throw an [ApiProblem] anywhere in a request and the page gets
 *
 * ```
 * { "type": "about:blank", "title": "Deal full", "status": 409, "detail": "...", "code": "DEAL_FULL" }
 * ```
 *
 * Use the codes your service's OpenAPI file lists (contracts, src/main/resources/contracts/openapi).
 */
open class ApiProblem(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val errors: List<FieldError> = emptyList(),
) : RuntimeException(message) {
    data class FieldError(val field: String, val message: String)
}

/** 404: no such thing, or it belongs to someone else (students never learn which). */
class NotFound(what: String) : ApiProblem(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what was not found")

/** 403: this role may not do this. */
class Forbidden(message: String = "Your role may not do this") : ApiProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", message)

/** 409 with your own code, for example Conflict("DEAL_FULL", "No places left"). */
class Conflict(code: String, message: String) : ApiProblem(HttpStatus.CONFLICT, code, message)

/** 422: the request is wrong; [errors] names each field. */
class Invalid(message: String, errors: List<FieldError> = emptyList()) : ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message, errors)

fun problem(status: HttpStatusCode, code: String, detail: String, errors: List<ApiProblem.FieldError> = emptyList()): ProblemDetail =
    ProblemDetail.forStatusAndDetail(status, detail).apply {
        title = HttpStatus.resolve(status.value())?.reasonPhrase ?: "Error"
        setProperty("code", code)
        if (errors.isNotEmpty()) setProperty("errors", errors)
    }

@RestControllerAdvice
class ProblemHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiProblem::class)
    fun api(e: ApiProblem) = respond(problem(e.status, e.code, e.message, e.errors))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException) = respond(problem(
        HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "The request has invalid fields",
        e.bindingResult.fieldErrors.map { ApiProblem.FieldError(it.field, it.defaultMessage ?: "is invalid") },
    ))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> {
        // A missing or mistyped field in a Kotlin request class lands here, not as a 500.
        val field = (e.cause as? com.fasterxml.jackson.databind.JsonMappingException)?.path
            ?.joinToString(".") { it.fieldName ?: "[${it.index}]" }?.ifEmpty { null }
        return respond(problem(
            HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "The request body could not be read",
            listOfNotNull(field?.let { ApiProblem.FieldError(it, "is missing or has the wrong type") }),
        ))
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException) = respond(problem(
        HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "The ${e.headerName} header is required",
        listOf(ApiProblem.FieldError(e.headerName, "is required")),
    ))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun badParameter(e: MethodArgumentTypeMismatchException): ResponseEntity<ProblemDetail> =
        // A path ID that is not a UUID is simply not found.
        respond(problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "${e.name} '${e.value}' was not found"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noRoute(e: NoResourceFoundException) = respond(problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such path: /${e.resourcePath}"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) =
        respond(problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "${e.method} is not allowed here"))

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(e: AccessDeniedException) = respond(problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role may not do this"))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unexpected error", e)
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong; the service log has the details"))
    }

    private fun respond(p: ProblemDetail): ResponseEntity<ProblemDetail> = ResponseEntity.status(p.status).body(p)
}

/**
 * No token, or an expired one: 401 with a Problem, never a 302 redirect to a login page (the Week 7
 * trap: Spring Security's default entry point).
 */
class ProblemEntryPoint(private val mapper: com.fasterxml.jackson.databind.ObjectMapper) : AuthenticationEntryPoint, AccessDeniedHandler {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, e: AuthenticationException) =
        write(response, 401, "UNAUTHENTICATED", "Sign in again on the dev sign-in page: the token is missing or has expired")

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, e: org.springframework.security.access.AccessDeniedException) =
        write(response, 403, "FORBIDDEN", "Your role may not do this")

    private fun write(response: HttpServletResponse, status: Int, code: String, detail: String) {
        response.status = status
        response.characterEncoding = "UTF-8"
        response.contentType = "application/problem+json"
        // Spring's own mapper writes ProblemDetail flat, with code beside status and title.
        response.writer.write(mapper.writeValueAsString(problem(HttpStatusCode.valueOf(status), code, detail)))
    }
}
