package ie.ul.cs4297.groupbuy.service.ledger

import java.util.UUID

/**
 * The top-up operation from the shared posting code (Igor, item 2), as PaymentsConsumer calls it.
 * It runs inside the caller's transaction, so the credit and the TopUpCredited outbox row commit together.
 */
interface TopUpPosting {
    /**
     * Credits the student's available balance and debits Union cash, once per [topUpId], at the
     * first amount. A student with no account yet starts at 0.
     */
    fun creditTopUp(topUpId: UUID, studentId: UUID, amountCents: Long): TopUpResult
}

data class TopUpResult(val newlyApplied: Boolean, val availableCents: Long, val heldCents: Long)
