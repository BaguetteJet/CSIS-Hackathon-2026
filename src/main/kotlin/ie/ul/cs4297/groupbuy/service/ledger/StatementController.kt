package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntry
import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.platform.CurrentUser
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The signed-in student's statement (LED-11): every labelled change to their AVAILABLE account.
 * Each area labels its own posting when it moves the money, so new kinds show here with no change.
 */
@RestController
class StatementController(private val jdbc: JdbcTemplate) {

    @GetMapping("/api/ledger/me/entries")
    fun myEntries(user: CurrentUser): List<LedgerEntry> {
        user.requireRole(Role.STUDENT)
        // The student only ever comes from the token, never the request (LED-18).
        return jdbc.query(
            """
            SELECT p.posted_at, p.entry_kind, p.amount_cents, p.deal_id, p.order_id, p.description
            FROM ledger_posting p
            JOIN ledger_account a ON a.id = p.account_id
            WHERE a.owner_type = 'STUDENT' AND a.owner_id = ? AND a.purpose = 'AVAILABLE'
            AND p.entry_kind IS NOT NULL
            ORDER BY p.posted_at DESC, p.id DESC
            LIMIT 100
            """.trimIndent(),
            { rs, _ ->
                val signed = rs.getLong("amount_cents")
                LedgerEntry(
                    at = rs.getTimestamp("posted_at").toInstant(),
                    kind = LedgerEntryKind.valueOf(rs.getString("entry_kind")),
                    // The contract wants a positive amount; the posting's sign says whether money came in.
                    amountCents = if (signed < 0) -signed else signed,
                    credit = signed > 0,
                    dealId = rs.getString("deal_id")?.let { UUID.fromString(it) },
                    orderId = rs.getString("order_id")?.let { UUID.fromString(it) },
                    description = rs.getString("description"),
                )
            },
            user.userId.toString(),
        )
    }
}
