package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.Balance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

enum class AccountOwner { STUDENT, RESTAURANT, UNION }

enum class AccountPurpose { AVAILABLE, HELD, PAYABLE, CASH, COMMISSION }

/** An account: owner plus purpose. A hold moves money from AVAILABLE to HELD: no flag. */
data class AccountRef(
    val owner: AccountOwner,
    val ownerId: String,
    val purpose: AccountPurpose,
) {
    override fun toString(): String = "$owner/$ownerId/$purpose"

    companion object {
        /** The Union's owner id: zero UUID, so it fits the CHAR(36) column. */
        const val UNION = "00000000-0000-0000-0000-000000000000"

        fun studentAvailable(studentId: UUID) = AccountRef(AccountOwner.STUDENT, studentId.toString(), AccountPurpose.AVAILABLE)
        fun studentHeld(studentId: UUID) = AccountRef(AccountOwner.STUDENT, studentId.toString(), AccountPurpose.HELD)
        fun restaurantPayable(restaurantId: UUID) = AccountRef(AccountOwner.RESTAURANT, restaurantId.toString(), AccountPurpose.PAYABLE)

        /** Desk money in and out. */
        val UNION_CASH = AccountRef(AccountOwner.UNION, UNION, AccountPurpose.CASH)

        /** What the Union keeps from each order. */
        val UNION_COMMISSION = AccountRef(AccountOwner.UNION, UNION, AccountPurpose.COMMISSION)
    }
}

/** Read accounts and open them on first use. Balances only change through [LedgerPostingService]. */
@Component
class LedgerAccounts(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {

    /** Open both student accounts at zero. Safe to call twice. */
    fun openStudent(studentId: UUID) {
        transactions.executeWithoutResult {
            ensure(AccountRef.studentAvailable(studentId))
            ensure(AccountRef.studentHeld(studentId))
        }
    }

    /** No accounts yet: zeros (LED-07). */
    fun balance(studentId: UUID): Balance {
        val balances = jdbc.query(
            "SELECT purpose, balance_cents FROM ledger_account " +
                "WHERE owner_type = ? AND owner_id = ? AND purpose IN (?, ?)",
            { rs, _ -> rs.getString(1) to rs.getLong(2) },
            AccountOwner.STUDENT.name,
            studentId.toString(),
            AccountPurpose.AVAILABLE.name,
            AccountPurpose.HELD.name,
        ).toMap()
        return Balance(
            studentId = studentId,
            availableCents = balances[AccountPurpose.AVAILABLE.name] ?: 0L,
            heldCents = balances[AccountPurpose.HELD.name] ?: 0L,
        )
    }

    /**
     * Open and lock the student's accounts, then return the latest balance (LED-20).
     * Use it for money decisions and for any balance put on an event.
     * AVAILABLE is opened first: locks go in ascending id order, like [LedgerPostingService.post].
     * FOR UPDATE, not a plain read: a REPEATABLE READ snapshot can be out of date.
     */
    fun lockStudent(studentId: UUID): Balance {
        val available = ensure(AccountRef.studentAvailable(studentId))
        val held = ensure(AccountRef.studentHeld(studentId))
        val cents = jdbc.query(
            "SELECT id, balance_cents FROM ledger_account WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            { rs, _ -> rs.getLong(1) to rs.getLong(2) }, available, held,
        ).toMap()
        return Balance(studentId, cents.getValue(available), cents.getValue(held))
    }

    /**
     * The account's id. Opens it at zero if needed.
     * Plain SELECT first: [LedgerPostingService.post] does the locking.
     * The upsert handles two instances opening it at once.
     */
    fun ensure(ref: AccountRef): Long {
        findId(ref)?.let { return it }
        jdbc.update(
            "INSERT INTO ledger_account (owner_type, owner_id, purpose, balance_cents, created_at) " +
                "VALUES (?, ?, ?, 0, ?) ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)",
            ref.owner.name, ref.ownerId, ref.purpose.name, Timestamp.from(clock.instant()),
        )
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)
            ?: error("the account $ref could not be opened")
    }

    private fun findId(ref: AccountRef): Long? = jdbc.query(
        "SELECT id FROM ledger_account WHERE owner_type = ? AND owner_id = ? AND purpose = ?",
        { rs, _ -> rs.getLong(1) },
        ref.owner.name, ref.ownerId, ref.purpose.name,
    ).firstOrNull()

    /** Always zero under double entry (LED-20). */
    fun sumOfAllAccounts(): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(balance_cents), 0) FROM ledger_account", Long::class.java,
    )!!
}
