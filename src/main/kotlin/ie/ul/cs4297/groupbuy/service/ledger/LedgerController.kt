package ie.ul.cs4297.groupbuy.service.ledger

import ie.ul.cs4297.groupbuy.contracts.api.Balance
import ie.ul.cs4297.groupbuy.contracts.events.Role
import ie.ul.cs4297.groupbuy.service.platform.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** [LED-07] - My balance: the signed-in student's own balance. Read live: a new hold shows at once (D-J2). */
@RestController
class LedgerController(private val accounts: LedgerAccounts) {

    @GetMapping("/api/ledger/me")
    fun myBalance(user: CurrentUser): Balance {
        user.requireRole(Role.STUDENT)
        return accounts.balance(user.userId)
    }
}
