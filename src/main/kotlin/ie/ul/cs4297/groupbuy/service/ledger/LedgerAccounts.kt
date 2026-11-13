package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.Balance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/** Who owns an account. */
enum class AccountOwner { STUDENT, RESTAURANT, UNION }

/** What an account holds. */
enum class AccountPurpose { AVAILABLE, HELD, PAYABLE, CASH, COMMISSION }

/**
 * The name of one account: who owns it and what it holds.
 *
 * A student has two accounts, AVAILABLE and HELD, so setting money aside is a movement between
 * them (double entry) rather than a flag on a single balance.
 */
data class AccountRef(
    val owner: AccountOwner,
    val ownerId: String,
    val purpose: AccountPurpose,
) {
    override fun toString(): String = "$owner/$ownerId/$purpose"

    companion object {
        /**
         * The Union owns the CASH and COMMISSION accounts. Its owner id is the zero UUID, so it
         * fits the CHAR(36) owner column exactly like a student's or restaurant's id does.
         */
        const val UNION = "00000000-0000-0000-0000-000000000000"

        fun studentAvailable(studentId: UUID) = AccountRef(AccountOwner.STUDENT, studentId.toString(), AccountPurpose.AVAILABLE)
        fun studentHeld(studentId: UUID) = AccountRef(AccountOwner.STUDENT, studentId.toString(), AccountPurpose.HELD)
        fun restaurantPayable(restaurantId: UUID) = AccountRef(AccountOwner.RESTAURANT, restaurantId.toString(), AccountPurpose.PAYABLE)

        /** The other side of money paid in or out at the desk. */
        val UNION_CASH = AccountRef(AccountOwner.UNION, UNION, AccountPurpose.CASH)

        /** What the Union keeps of each order. */
        val UNION_COMMISSION = AccountRef(AccountOwner.UNION, UNION, AccountPurpose.COMMISSION)
    }
}

/**
 * Reads accounts and opens them on first use.
 *
 * Balances are only ever changed through [LedgerPostingService]. A student has no row until their
 * first movement (or UserRegistered arrives): [balance] then answers 200 with zeros, as LED-07
 * requires, and [openStudent] creates both rows at zero.
 */
@Component
class LedgerAccounts(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {

    /** Opens a student's AVAILABLE and HELD accounts at zero; a second call changes nothing. */
    fun openStudent(studentId: UUID) {
        transactions.executeWithoutResult {
            ensure(AccountRef.studentAvailable(studentId))
            ensure(AccountRef.studentHeld(studentId))
        }
    }

    /** The student's balances, or zeros when no account has been opened yet. */
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
     * The account's id, creating the row at zero when it does not exist. Call this in the
     * transaction that is about to post to the account.
     *
     * The SELECT first keeps the common path free of locks: a posting locks its accounts only
     * once, in id order, in [LedgerPostingService.post]. The upsert handles the rare race where
     * another instance opens the same account at the same moment, and LAST_INSERT_ID() returns
     * the existing row's id without a second snapshot read.
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

    /**
     * The sum of every account's balance. Double entry means it is always zero; the zero-sum
     * property test and the operator's daily check use this.
     */
    fun sumOfAllAccounts(): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(balance_cents), 0) FROM ledger_account", Long::class.java,
    )!!
}
