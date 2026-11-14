package ie.ul.cs4297.groupbuy.service.ledger

import java.util.UUID

/** The top-up credit PaymentsConsumer calls. Joins its transaction: the event commits with it. */
interface TopUpPosting {
    /** Credit once per [topUpId] at the first amount. Unknown student: starts at 0. */
    fun creditTopUp(topUpId: UUID, studentId: UUID, amountCents: Long): TopUpResult
}

data class TopUpResult(val newlyApplied: Boolean, val availableCents: Long, val heldCents: Long)
