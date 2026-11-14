package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalCompleted
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalFailed
import ie.ul.cs4297.groupbuy.contracts.events.WithdrawalRequested
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Cash-outs (LED-15, 16). PaymentsConsumer calls these inside the inbox transaction, amounts already
 * checked (LED-19); the implementation posts and publishes. Item 8 replaces [NoWithdrawalsYet].
 */
interface Withdrawals {
    /** Reserve: available to held, then WithdrawalDebited; not enough, WithdrawalRefused. */
    fun requested(e: WithdrawalRequested, causationId: UUID)

    /** Held to Union cash at the reserved amount, then WithdrawalPaidOut. */
    fun completed(e: WithdrawalCompleted, causationId: UUID)

    /** Held back to available at the reserved amount, then WithdrawalReturned. */
    fun failed(e: WithdrawalFailed, causationId: UUID)
}

/** Until cash-outs exist: nothing moves, so Payments gets no answer. Logged so it shows in integration. */
@Component
class NoWithdrawalsYet : Withdrawals {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun requested(e: WithdrawalRequested, causationId: UUID) = log.warn("Cash-outs not built yet: WithdrawalRequested {} ignored", e.withdrawalId)
    override fun completed(e: WithdrawalCompleted, causationId: UUID) = log.warn("Cash-outs not built yet: WithdrawalCompleted {} ignored", e.withdrawalId)
    override fun failed(e: WithdrawalFailed, causationId: UUID) = log.warn("Cash-outs not built yet: WithdrawalFailed {} ignored", e.withdrawalId)
}
