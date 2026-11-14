package ie.ul.cs4297.groupbuy.service.ledger

import com.fasterxml.jackson.databind.ObjectMapper
import ie.ul.cs4297.groupbuy.service.platform.ProblemHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.sql.SQLException

// [LED-21] - Outages: with MySQL down, every API call answers 503 UNAVAILABLE, never a 500; a real bug still answers 500.
class DatabaseUnavailableTest {

    @RestController
    class Failing {
        @GetMapping("/fail/{how}")
        fun fail(@PathVariable how: String): String = throw when (how) {
            "no-connection" -> CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", SQLException("Connection is not available, request timed out after 1000ms"))
            "no-transaction" -> CannotCreateTransactionException("Could not open JDBC Connection for transaction")
            "timeout" -> QueryTimeoutException("Statement cancelled")
            else -> BadSqlGrammarException("query", "SELECT nonsense", SQLException("syntax"))
        }
    }

    // Platform's catch-all first: the Ledger's handler must still win by its order.
    private val mvc = MockMvcBuilders.standaloneSetup(Failing()).setControllerAdvice(ProblemHandler(), DatabaseUnavailableHandler()).build()

    private fun call(how: String): Pair<Int, String> {
        val response = mvc.perform(get("/fail/$how")).andReturn().response
        return response.status to ObjectMapper().readTree(response.contentAsString)["code"].asText()
    }

    @Test
    fun `no connection, no transaction or a timeout is 503 UNAVAILABLE`() {
        for (how in listOf("no-connection", "no-transaction", "timeout")) {
            assertEquals(503 to "UNAVAILABLE", call(how), how)
        }
    }

    @Test
    fun `a bug in the SQL is still a 500`() {
        assertEquals(500, call("bad-sql").first)
    }
}
