package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.Balance
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.platform.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The student's own balance (LED-07; ledger.yaml, GET /api/ledger/me).
 *
 * The student ID comes only from the token: there is no path or body to take someone else's ID
 * from. A student whose account has not been opened yet gets 200 with zeros, and the balance is
 * read from the accounts themselves, never from a copy.
 */
@RestController
class LedgerController(private val accounts: LedgerAccounts) {

    @GetMapping("/api/ledger/me")
    fun myBalance(user: CurrentUser): Balance {
        user.requireRole(Role.STUDENT)
        return accounts.balance(user.userId)
    }
}
