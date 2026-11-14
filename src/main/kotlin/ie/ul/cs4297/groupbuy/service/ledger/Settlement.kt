package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.Money
import ie.ul.cs4297.groupbuy.contracts.api.Balance
import ie.ul.cs4297.groupbuy.contracts.api.LedgerEntryKind
import ie.ul.cs4297.groupbuy.contracts.events.DealConfirmed
import ie.ul.cs4297.groupbuy.contracts.events.DealSettled
import ie.ul.cs4297.groupbuy.contracts.events.HoldReleasedReason
import ie.ul.cs4297.groupbuy.contracts.events.OrderCharged
import ie.ul.cs4297.groupbuy.contracts.events.OrderRef
import ie.ul.cs4297.groupbuy.contracts.events.SettlementIssue
import ie.ul.cs4297.groupbuy.service.platform.Outbox
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [LED-05] - Settle a confirmed deal
 * [LED-06] - Settle exactly once
 * [LED-10] - Settlement problems
 * Settles a confirmed deal in one transaction, exactly once (decision 0007).
 * 1. Claim deal_settlement: a repeat, on either instance or in a new envelope, stops here.
 * 2. Lock the hold rows (the deal's, then listed orders held elsewhere), close the deal, then lock every student involved.
 * 3. Charge each listed order that's fine; report the rest as SettlementIssue and leave their holds as they are.
 * 4. Release the deal's other open holds (NOT_ON_LIST), then publish DealSettled covering only the charged orders.
 * A crash anywhere rolls all of it back; redelivery settles the whole deal once.
 */
@Component
class Settlement(
    private val jdbc: JdbcTemplate,
    private val accounts: LedgerAccounts,
    private val postings: LedgerPostingService,
    private val releases: Releases,
    private val outbox: Outbox,
    private val clock: Clock,
    private val wakeups: RefundWakeups,
    private val meters: MeterRegistry,
) {

    fun settle(e: DealConfirmed, causationId: UUID) {
        val started = System.nanoTime()
        if (!claim(e)) return

        val dealHolds = releases.holdsForDeal(e.dealId)
        val listed = e.orders.distinctBy { it.orderId }.sortedBy { it.orderId.toString() }
        val listedIds = listed.map { it.orderId }.toSet()
        val holds = dealHolds.associateBy { it.orderId } +
            releases.holdsForOrders(listedIds - dealHolds.map { it.orderId }.toSet()).associateBy { it.orderId }
        releases.closeDeal(e.dealId, "CONFIRMED")

        val charges = mutableListOf<HoldRow>()
        val issues = mutableListOf<Pair<UUID, String>>()
        for (order in listed) {
            val hold = holds[order.orderId]
            when (val problem = problem(order, hold, e)) {
                null -> charges += hold!!
                else -> issues += order.orderId to problem
            }
        }
        val notListed = dealHolds.filter { it.isOpen && it.orderId !in listedIds }
        val balances = accounts.lockStudents((charges + notListed).map { it.studentId })

        // Every charged hold ends here, in one statement (decision 0008): the rows are already ours.
        if (charges.isNotEmpty()) {
            val ids = charges.map { it.orderId.toString() }
            check(jdbc.update("UPDATE hold_decision SET state = 'CHARGED' WHERE state = 'OPEN' AND order_id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray()) == charges.size) {
                "a hold in deal ${e.dealId} was not open"
            }
        }
        val rows = charges.map { charge(it, e, balances, causationId) }
        rows.chunked(500).forEach { chunk ->
            jdbc.update(
                "INSERT INTO order_charge (order_id, deal_id, student_id, restaurant_id, price_cents, commission_cents, returned_cents, charged_at) VALUES " +
                    chunk.joinToString(",") { "(?, ?, ?, ?, ?, ?, ?, ?)" },
                *chunk.flatten().toTypedArray(),
            )
        }
        val commissionTotal = charges.indices.sumOf { rows[it][5] as Long }
        releases.release(notListed, HoldReleasedReason.NOT_ON_LIST, causationId, balances)

        val chargedTotal = e.priceCents * charges.size
        jdbc.update(
            "UPDATE deal_settlement SET charged_count = ?, total_charged_cents = ?, total_commission_cents = ? WHERE deal_id = ?",
            charges.size, chargedTotal, commissionTotal, e.dealId.toString(),
        )
        issues.forEach { (orderId, problem) -> outbox.publish(SettlementIssue(e.dealId, orderId, problem), causationId) }
        outbox.publish(DealSettled(e.dealId, e.restaurantId, charges.size, chargedTotal, commissionTotal), causationId)
        wakeups.dealDecided(e.dealId)

        // [LED-23] - Observability: settlement count, duration and SettlementIssue count
        meters.counter("ledger.settlement.issues").increment(issues.size.toDouble())
        meters.timer("ledger.settlement").record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
    }

    private fun claim(e: DealConfirmed): Boolean = try {
        jdbc.update(
            "INSERT INTO deal_settlement (deal_id, restaurant_id, settled_at) VALUES (?, ?, ?)",
            e.dealId.toString(), e.restaurantId.toString(), Timestamp.from(clock.instant()),
        )
        true
    } catch (dup: DuplicateKeyException) {
        false
    }

    /** [LED-10] - Settlement problems: why a listed order can't be charged, in words for staff with no personal data, or null if it can. */
    private fun problem(order: OrderRef, hold: HoldRow?, e: DealConfirmed): String? = when {
        hold == null || hold.result != "PLACED" -> "No money was set aside for this order"
        hold.state == "RELEASED" -> "The money set aside for this order was already released"
        hold.state == "CHARGED" -> "This order was already charged"
        hold.dealId != e.dealId -> "The money set aside is for another deal"
        hold.studentId != order.studentId -> "The money set aside is for another student"
        hold.amountCents < e.priceCents -> "The money set aside is below the price"
        else -> null
    }

    /** Charge one order at the tier price; returns its order_charge row. */
    private fun charge(hold: HoldRow, e: DealConfirmed, balances: MutableMap<UUID, Balance>, causationId: UUID): List<Any> {
        val price = e.priceCents
        val commission = Money.commission(price, e.commissionBasisPoints) // half up (R7)
        val returned = hold.amountCents - price
        val lines = buildList {
            add(PostingLine(AccountRef.studentHeld(hold.studentId), -hold.amountCents))
            if (price - commission != 0L) add(PostingLine(AccountRef.restaurantPayable(e.restaurantId), price - commission))
            if (commission != 0L) add(PostingLine(AccountRef.UNION_COMMISSION, commission))
            // A settlement that returns nothing adds no statement entry.
            if (returned != 0L) add(PostingLine(AccountRef.studentAvailable(hold.studentId), returned,
                EntryDetails(LedgerEntryKind.RETURNED, "Paid ${euros(price)}, ${euros(returned)} back in your Account", e.dealId, hold.orderId)))
        }
        check(postings.post(MovementRef(MovementType.ORDER_CHARGE, hold.orderId), lines)) { "order ${hold.orderId} was charged twice" }
        val before = balances.getValue(hold.studentId)
        val after = Balance(hold.studentId, before.availableCents + returned, before.heldCents - hold.amountCents)
        balances[hold.studentId] = after
        outbox.publish(
            OrderCharged(hold.orderId, e.dealId, hold.studentId, e.restaurantId, price, commission, returned, after.availableCents, after.heldCents),
            causationId,
        )
        wakeups.orderCharged(hold.orderId)
        return listOf(hold.orderId.toString(), e.dealId.toString(), hold.studentId.toString(), e.restaurantId.toString(),
            price, commission, returned, Timestamp.from(clock.instant()))
    }

    private fun euros(cents: Long) = "€%d.%02d".format(cents / 100, cents % 100)
}
