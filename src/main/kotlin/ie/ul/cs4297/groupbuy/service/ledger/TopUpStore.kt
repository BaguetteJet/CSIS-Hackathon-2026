package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Stores a top-up in the Ledger (LED-02, the storage side): the [TopUpPosting] that
 * PaymentsConsumer calls once it has an event. Receiving the event and publishing
 * `TopUpCredited` belong to the consumer; this class only moves the money and returns the
 * balance after, so the two pieces stay separate.
 *
 * The topUpId is the business key: a repeat with the same topUpId returns `newlyApplied = false`
 * and changes nothing, even if the amount differs.
 */
@Component
class TopUpStore(
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
) : TopUpPosting {

    /**
     * Credits [studentId] by [amountCents], debits Union cash, and returns the balance after.
     *
     * Call this inside the transaction that will publish the credit event, so the money and the
     * event are saved together.
     */
    override fun creditTopUp(topUpId: UUID, studentId: UUID, amountCents: Long): TopUpResult {
        require(amountCents > 0) { "a top-up must be at least 1 cent, not $amountCents" }

        // A top-up is a first use: the posting opens the student's account even before UserRegistered.
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
        // Read under the lock: TopUpCredited's balance must chain with the student's other events.
        val after = accounts.lockStudent(studentId)
        return TopUpResult(posted, after.availableCents, after.heldCents)
    }
}
