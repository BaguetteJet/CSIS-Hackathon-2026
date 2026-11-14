package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** With the reference id: the business key that makes a repeat do nothing. */
enum class MovementType {
    TOP_UP,
    HOLD,
    RELEASE,
    ORDER_CHARGE,
    REFUND,
    WITHDRAWAL,
    WITHDRAWAL_PAYOUT,
    WITHDRAWAL_RETURN,
    PAYOUT,
}

data class MovementRef(val type: MovementType, val id: UUID)

/** [LED-11] - My statement: statement line. `credit` comes from the posting's sign. */
data class EntryDetails(
    val kind: LedgerEntryKind,
    val description: String,
    val dealId: UUID? = null,
    val orderId: UUID? = null,
)

/** One side of a movement: signed cents. A movement's sides add up to zero. */
data class PostingLine(
    val account: AccountRef,
    val amountCents: Long,
    val entry: EntryDetails? = null,
)

/**
 * The only way money moves.
 * Same [MovementRef] twice: returns false and moves nothing.
 * Only student accounts keep a running balance: they are locked in ascending id order, so no deadlocks.
 * Union and restaurant accounts are just their postings: no row to lock, so no hot row for top-ups or settlement.
 * Students never go negative: callers check first, this only catches bugs. Payables and Union can ([LED-13] - Post refunds; [LED-17] - Restaurant payouts).
 * Joins the caller's transaction: the event commits with the money.
 */
@Component
class LedgerPostingService(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {

    fun post(reference: MovementRef, lines: List<PostingLine>): Boolean {
        require(lines.size >= 2) { "a movement needs at least two sides" }
        require(lines.all { it.amountCents != 0L }) { "a posting line must not be zero" }
        val total = lines.fold(0L) { sum, line -> Math.addExact(sum, line.amountCents) }
        require(total == 0L) { "the movement's sides add up to $total, not zero" }
        require(lines.map { it.account }.distinct().size >= 2) { "a movement needs at least two different accounts" }

        return transactions.execute { postInTransaction(reference, lines) }!!
    }

    private fun postInTransaction(reference: MovementRef, lines: List<PostingLine>): Boolean {
        val postedAt = clock.instant()
        val movementId = UUID.randomUUID().toString()
        try {
            jdbc.update(
                "INSERT INTO ledger_movement (id, reference_type, reference_id, posted_at) VALUES (?, ?, ?, ?)",
                movementId, reference.type.name, reference.id.toString(), Timestamp.from(postedAt),
            )
        } catch (e: DuplicateKeyException) {
            // Already posted: the first one wins.
            return false
        }

        // Open accounts in a fixed order, then lock the student ones by ascending id.
        val distinctAccounts = lines.map { it.account }.distinct()
        val idByAccount = distinctAccounts
            .sortedBy { "${it.owner.name}|${it.ownerId}|${it.purpose.name}" }
            .associateWith { accounts.ensure(it) }
        val studentDeltaById = lines
            .filter { it.account.owner == AccountOwner.STUDENT }
            .groupingBy { idByAccount.getValue(it.account) }
            .fold(0L) { sum, line -> Math.addExact(sum, line.amountCents) }
        val studentIds = studentDeltaById.keys.sorted()
        val accountById = idByAccount.entries.associate { (account, id) -> id to account }

        // One relative update per student account: it takes the row lock, and refuses to go below zero.
        for (id in studentIds) {
            val delta = studentDeltaById.getValue(id)
            if (jdbc.update("UPDATE ledger_account SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents + ? >= 0", delta, id, delta) != 1) {
                error("posting ${reference.type} ${reference.id} would make ${accountById.getValue(id)} negative")
            }
        }

        // Every side of the movement in one statement.
        val args = lines.flatMap { line ->
            listOf(movementId, idByAccount.getValue(line.account), line.amountCents, Timestamp.from(postedAt),
                line.entry?.kind?.name, line.entry?.description, line.entry?.dealId?.toString(), line.entry?.orderId?.toString())
        }
        jdbc.update(
            "INSERT INTO ledger_posting (movement_id, account_id, amount_cents, posted_at, entry_kind, description, deal_id, order_id) VALUES " +
                lines.joinToString(",") { "(?, ?, ?, ?, ?, ?, ?, ?)" },
            *args.toTypedArray(),
        )
        return true
    }
}
