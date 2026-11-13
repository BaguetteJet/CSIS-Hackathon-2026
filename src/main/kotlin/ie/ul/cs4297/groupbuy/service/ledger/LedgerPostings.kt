package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * What a movement is for. Together with its reference id it is the business key that stops a
 * repeated event from moving money twice (a top-up by topUpId, a charge by orderId, ...).
 */
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

/** One business movement: what it is and the id of the thing that caused it. */
data class MovementRef(val type: MovementType, val id: UUID)

/**
 * The student statement line a posting represents, when the posting is one the student sees.
 * [LedgerEntryKind] maps to the statement's kind (LED-11); a hold is a debit, a release a credit,
 * and so on, so the statement can take `credit` from the sign of [PostingLine.amountCents].
 */
data class EntryDetails(
    val kind: LedgerEntryKind,
    val description: String,
    val dealId: UUID? = null,
    val orderId: UUID? = null,
)

/**
 * One side of a movement. [amountCents] is signed: positive increases that account's balance,
 * negative decreases it. The lines of one movement must add up to zero.
 */
data class PostingLine(
    val account: AccountRef,
    val amountCents: Long,
    val entry: EntryDetails? = null,
)

/**
 * The posting primitive: the only way money moves in the Ledger.
 *
 * [post] writes one balanced movement and updates every account it names, all in one transaction.
 * The movement's business reference makes it idempotent: calling it again with the same
 * [MovementRef] returns false and changes nothing, even if the amounts are different. So a
 * repeated TopUpRecorded with the same topUpId credits the first amount once, and a repeated
 * DealConfirmed cannot charge an order twice.
 *
 * Accounts are locked in id order before any balance changes, so two movements that touch the
 * same accounts cannot deadlock each other by taking them in opposite orders. A posting may never
 * take a student's AVAILABLE or HELD balance below zero: a caller that has to refuse a request
 * (a hold with too little money) checks first and publishes its refusal itself, while this guard
 * catches a bug. Restaurant payables and Union accounts may go negative, as LED-13 and LED-17
 * require.
 *
 * Call this inside the transaction that publishes the movement's event, so the money and the
 * event are saved together (the outbox does the publishing). A call already inside a transaction
 * joins it; a call on its own gets one of its own.
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

    /** Runs in [post]'s transaction: the movement, every posting and every balance change. */
    private fun postInTransaction(reference: MovementRef, lines: List<PostingLine>): Boolean {
        val postedAt = clock.instant()
        val movementId = UUID.randomUUID().toString()
        try {
            jdbc.update(
                "INSERT INTO ledger_movement (id, reference_type, reference_id, posted_at) VALUES (?, ?, ?, ?)",
                movementId, reference.type.name, reference.id.toString(), Timestamp.from(postedAt),
            )
        } catch (e: DuplicateKeyException) {
            // The business movement is already posted: keep the first one, whatever this repeat says.
            return false
        }

        // Find or open every account in a fixed order, so two instances opening the same accounts
        // race safely; then lock the ids in ascending order, which every movement does.
        val distinctAccounts = lines.map { it.account }.distinct()
        val idByAccount = distinctAccounts
            .sortedBy { "${it.owner.name}|${it.ownerId}|${it.purpose.name}" }
            .associateWith { accounts.ensure(it) }
        val deltaById = lines
            .groupingBy { idByAccount.getValue(it.account) }
            .fold(0L) { sum, line -> Math.addExact(sum, line.amountCents) }
        val ids = deltaById.keys.sorted()
        val accountById = idByAccount.entries.associate { (account, id) -> id to account }

        val afterById = LinkedHashMap<Long, Long>(ids.size)
        for (id in ids) {
            val before = jdbc.queryForObject(
                "SELECT balance_cents FROM ledger_account WHERE id = ? FOR UPDATE",
                Long::class.java, id,
            )!!
            afterById[id] = Math.addExact(before, deltaById.getValue(id))
        }
        for ((id, after) in afterById) {
            val account = accountById.getValue(id)
            if (account.owner == AccountOwner.STUDENT && after < 0) {
                error("posting ${reference.type} ${reference.id} would make $account negative: $after cents")
            }
        }

        val postingSql =
            "INSERT INTO ledger_posting " +
                "(movement_id, account_id, amount_cents, posted_at, entry_kind, description, deal_id, order_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        for (line in lines) {
            jdbc.update(
                postingSql,
                movementId,
                idByAccount.getValue(line.account),
                line.amountCents,
                Timestamp.from(postedAt),
                line.entry?.kind?.name,
                line.entry?.description,
                line.entry?.dealId?.toString(),
                line.entry?.orderId?.toString(),
            )
        }
        for (id in ids) {
            jdbc.update(
                "UPDATE ledger_account SET balance_cents = ? WHERE id = ?",
                afterById.getValue(id), id,
            )
        }
        return true
    }
}
