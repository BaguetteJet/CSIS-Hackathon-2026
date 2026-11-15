---
name: ledger-story
description: Build or change a Ledger LED story (holds, settlement, releases, refunds, cash-outs, payouts, statement, desk balance). Use when implementing a step from work-items/.
---

# Build an LED story

1. **Pin the rule.** Read your item in `work-items/`, the LED text in the team spec (Ledger §5), and the decision records it touches.
   *Done when* you can state the rule and the item's "done when" check in one line each.
2. **Check the contract.** Event and API shapes come from the contracts library (samples and schemas in the `contracts-1.3.0` jar), never from memory.
3. **Test first** (the `test-driven-development` skill). Write a container test extending `LedgerFlowTest`; its helpers are `funded`, `hold`, `sendAndWait`, `events` and `assertBooksBalance`.
   *Done when* it fails for the reason the story describes.
4. **Build it** inside the existing listener or service:
   - money through `post`;
   - balances from `lockStudent(s)`;
   - lock order: business rows, then students, then publish;
   - a business key for the effect;
   - `outbox.publish` with the balance after;
   - statement text in student words.
5. **Contract test** in `ContractTest` for every new event, built the way the code builds it.
6. **Verify** with the `ledger-verify` skill.
7. **Record it.** Tick the step in your item.
