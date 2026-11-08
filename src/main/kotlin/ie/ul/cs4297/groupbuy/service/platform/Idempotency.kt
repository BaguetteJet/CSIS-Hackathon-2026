package ie.ul.cs4297.groupbuy.service.platform

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock

/**
 * The `Idempotency-Key` header (spec B, "Shared rules for every API"): repeating a request with
 * the same key and the same body returns the first result; the same key with a different body
 * answers 409 IDEMPOTENCY_KEY_REUSED.
 *
 * ```
 * @PostMapping("/api/payments/desk/top-ups")
 * fun topUp(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: TopUpRequest, user: CurrentUser) =
 *     idempotent.handle(key, user.userId.toString(), body, HttpStatus.CREATED) { desk.recordTopUp(body, user) }
 * ```
 *
 * The key is recorded in the same transaction as your change, so a double click either waits and
 * gets the first answer, or (if the first failed) does the work itself. Keys are per caller.
 */
@Component
class IdempotentRequests(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    fun handle(key: String?, caller: String, body: Any?, status: HttpStatus = HttpStatus.OK, action: () -> Any): ResponseEntity<JsonNode> {
        if (key.isNullOrBlank() || key.length > 100) {
            throw Invalid("The Idempotency-Key header is required (1 to 100 characters)", listOf(ApiProblem.FieldError("Idempotency-Key", "is required")))
        }
        val hash = sha256(canonical(mapper.valueToTree(body)))
        return tx.execute {
            try {
                jdbc.update(
                    "INSERT INTO idempotency_record (caller, idempotency_key, request_hash, status, created_at) VALUES (?, ?, ?, 0, ?)",
                    caller, key, hash, Timestamp.from(clock.instant()),
                )
            } catch (e: DuplicateKeyException) {
                val (firstHash, firstStatus, firstJson) = jdbc.query(
                    "SELECT request_hash, status, response_json FROM idempotency_record WHERE caller = ? AND idempotency_key = ?",
                    { rs, _ -> Triple(rs.getString(1), rs.getInt(2), rs.getString(3)) }, caller, key,
                ).single()
                if (firstHash != hash) throw Conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used with a different request")
                return@execute ResponseEntity.status(firstStatus).contentType(MediaType.APPLICATION_JSON).body(mapper.readTree(firstJson ?: "null"))
            }
            val result: JsonNode = mapper.valueToTree(action())
            jdbc.update(
                "UPDATE idempotency_record SET status = ?, response_json = ? WHERE caller = ? AND idempotency_key = ?",
                status.value(), mapper.writeValueAsString(result), caller, key,
            )
            ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(result)
        }!!
    }

    /** The same JSON with object keys sorted, so {"a":1,"b":2} and {"b":2,"a":1} are the same body. */
    private fun canonical(node: JsonNode): String = mapper.writeValueAsString(sortKeys(node))

    private fun sortKeys(node: JsonNode): JsonNode = when {
        node.isObject -> mapper.createObjectNode().also { out ->
            node.fieldNames().asSequence().sorted().forEach { out.set<JsonNode>(it, sortKeys(node.get(it))) }
        }
        node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(sortKeys(it)) } }
        else -> node
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
