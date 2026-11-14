# Item 1: Holds and integration

**Owner:** Hersh (lead) · **Backup:** Igor · **LED:** 01, 03, 04, 18 (sign-in down), 22 (holds), 24 (recompute SQL)

- Orders gets one answer per order, fast, on either instance; holds for closed orders or deals are refused.
- Accounts open on registration; the service is promoted into integration and kept in line with the contracts.

**Builds on:** item 2 (`lockStudent`, `post`). **Used by:** item 5 (hooks, hold state), item 4 (metrics).

### Steps

1. [x] **Hold call (LED-03, 04).** `Holds.kt`, V4–V6.
   *Done when:* `HoldsTest` passes: exact funds, one cent short, repeats, 8-way race, 503 in under 2 s.
2. [ ] **Hooks for item 5.** `ClosuresConsumer` routes to `Releases` and `Settlement`.
   *Done when:* a late hold gets ORDER_CLOSED or DEAL_CLOSED.
3. [ ] **Accounts on registration (LED-01).** UserRegistered opens a student; RestaurantRegistered opens a payable.
   *Done when:* `AccountsOpenTest` passes.
4. [ ] **Sign-in key down (LED-18).** Check `/me` with the sign-in key unreachable.
   *Done when:* it answers 503 UNAVAILABLE, not 401 or 500.
5. [ ] **Recompute SQL (LED-24).** In `docs/HANDOVER.md` §8.
   *Done when:* it matches `/me` and DealSettled live.
6. [ ] **Speed (LED-22).** k6 at 100 holds/s, alone and during a cap-sized settlement.
   *Done when:* every answer is under 2 s.
7. [ ] **Ship slice 1.** Push, PR into `main` (reviewed by Igor), tag `checkpoint-1` before Sat 11:00.
   *Done when:* CI is green on `main`.
8. [ ] **Promote** (by Sat 16:30). Post `promote ledger <tag>` in the line channel.
   *Done when:* the organisers report live and Orders' holds arrive.
9. [ ] **Watch the contracts.** Answers log and contract releases, each morning.
   *Done when:* each change is shared and, if needed, an issue is opened.

**Review:** a hold after its order or deal closed is refused (marked property).
