- **Owner: Igor · Backup: Sam**

### What this means

- The schema sets up where Ledger stores balances and the history of every money movement.
- The shared posting operation locks one student’s balance so two requests cannot spend the same money. It saves the movement and the new balance together.
- Hersh uses it to reserve money, and Sam uses it to apply each top-up once.
- The `/me` endpoint lets a signed-in student see their own available and held amounts.

### Checklist

- Add the Flyway migration and shared posting operation (LED-20). Lock the student’s balance row and record the movement and balance change in one transaction.
- Provide Hersh with the hold operation and Sam with a top-up operation that credits each `topUpId` once and reports whether it was newly applied (LED-02).
- Build `GET /api/ledger/me` (LED-07) after the write path works. Read the signed-in student’s committed balance and return zeros if no account exists.

### Implementation

- Add the next Flyway migration under `src/main/resources/db/migration/`. Store available and held balances in integer cents, a movement history, and durable keys for the first hold decision and each applied top-up. Agree on how you create or locate a lockable balance row for a student with no existing account.
- Keep the balance read, funds check, movement record, and balance update under the same student row lock and transaction. A hold moves cents from available to held. A top-up adds cents to available with the matching Union cash posting. Every movement balances and the student’s available and held amounts stay nonnegative.
- Agree on the two handoffs before coding. Hersh needs a reservation result with the balance at decision time. Sam needs a top-up result that distinguishes a newly applied credit from a duplicate and includes the resulting balance. Make both operations participate in the caller’s transaction so the event can be recorded before commit.
- After the write path works, implement `GET /api/ledger/me` using the starter’s `CurrentUser` and the `STUDENT` role. Return `studentId`, `availableCents`, and `heldCents` for that signed-in user, with zero amounts if they have no account. Read committed Ledger data so a completed hold shows up immediately.

### Ready to review

- Verify a fresh database migrates, a duplicate business ID moves no money, and a rollback leaves the movement history and balance in agreement. For `/me`, check no token returns 401, a valid token with the wrong role returns 403, and a student sees their own committed balance.
