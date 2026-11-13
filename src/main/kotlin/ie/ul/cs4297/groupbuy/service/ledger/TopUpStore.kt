package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What storing a top-up did: whether it was the first time, and the balance after it.
 *
 * `posted` is false when this topUpId was already stored: the first amount wins (LED-02). The
 * caller that publishes the credit event uses [availableCents] and [heldCents].
 */
data class TopUpStored(
    val posted: Boolean,
    val availableCents: Long,
    val heldCents: Long,
)

/**
 * Stores a top-up in the Ledger (LED-02, the storage side).
 *
 * This is the operation a `TopUpRecorded` consumer calls once it has an event. Receiving the
 * event and publishing `TopUpCredited` belong to the top-up event slice; this class only moves
 * the money and returns the balance after, so the two pieces stay separate.
 *
 * The topUpId is the business key: a repeat with the same topUpId returns `posted = false` and
 * changes nothing, even if the amount differs.
 */
@Component
class TopUpStore(
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
) {

    /**
     * Credits [studentId] by [amountCents], debits Union cash, and returns the balance after.
     * Returns null for a non-positive amount; the caller decides whether that event is invalid.
     *
     * Call this inside the transaction that will publish the credit event, so the money and the
     * event are saved together.
     */
    fun record(topUpId: UUID, studentId: UUID, amountCents: Long): TopUpStored? {
        if (amountCents <= 0) return null

        // A top-up is a first use: open both accounts even before UserRegistered arrives.
        accounts.openStudent(studentId)
        val posted = postings.post(
            reference = MovementRef(MovementType.TOP_UP, topUpId),
            lines = listOf(
                PostingLine(
                    account = AccountRef.studentAvailable(studentId),
                    amountCents = amountCents,
                    entry = EntryDetails(LedgerEntryKind.TOP_UP, "Top-up at the desk"),
                ),
                PostingLine(AccountRef.UNION_CASH, -amountCents),
            ),
        )
        val after = accounts.balance(studentId)
        return TopUpStored(posted, after.availableCents, after.heldCents)
    }
}
