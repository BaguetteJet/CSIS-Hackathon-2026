package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import org.springframework.stereotype.Component
import java.util.UUID

/** [LED-02] - Credit top-ups: [TopUpPosting] on the ledger, Union cash to the student's available. */
@Component
class TopUpStore(
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
) : TopUpPosting {

    override fun creditTopUp(topUpId: UUID, studentId: UUID, amountCents: Long): TopUpResult {
        require(amountCents > 0) { "a top-up must be at least 1 cent, not $amountCents" }

        // [LED-01] - Open accounts: opens the account if needed, no UserRegistered required.
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
        // Locked read: TopUpCredited chains with the student's other events.
        val after = accounts.lockStudent(studentId)
        return TopUpResult(posted, after.availableCents, after.heldCents)
    }
}
