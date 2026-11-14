package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.Balance
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.platform.CurrentUser
import ie.ul.cs4297.groupbuy.service.platform.NotFound
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * [LED-12] - Desk balance: any student's balance for the Union desk, the same Balance as /me, so cash-outs stay within availableCents.
 * Whether the student is active or blocked is Payments' check, not ours.
 */
@RestController
class DeskController(private val accounts: LedgerAccounts) {

    @GetMapping("/api/ledger/students/{studentId}")
    fun studentBalance(user: CurrentUser, @PathVariable studentId: String): Balance {
        user.requireRole(Role.UNION_FINANCE)
        // The contract's pattern, not UUID.fromString: that one accepts junk like 1-2-3-4-5.
        if (!UUID_PATTERN.matches(studentId)) {
            throw NotFound("Student $studentId")
        }
        return accounts.balance(UUID.fromString(studentId))
    }

    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
