# 0001 Split the work by money flow

**Context.** Seven people share one service. Each area needs an owner and a backup (handover §16).

**Decision.** One area per money flow, each with its own entry point and tables. The lead owns holds and the hold lifecycle (settlement, releases), since they're one state machine and the critical path.

| Area | Entry point |
|---|---|
| 1 Holds, integration | Hold call, closures listener, users.v1 |
| 2 Core | Posting service, accounts |
| 3 Payments events, contracts | payments.v1, contract tests |
| 4 Operations | CI, compose, Grafana, handover |
| 5 Settlement, releases | Hooks in the closures listener |
| 6 Refunds | support.v1 |
| 7 Statement, desk, testing | Page API, property tests |
| 8 Cash-outs, payouts | Branches in the payments listener |

**Why not by layer.** A story would then cross three people; by flow, it stays with one.

**Cost.** Area 2 is everyone's dependency, so its interface is fixed first. Pairs that share a listener (1+5, 3+8) or a hook (5+6) agree it up front.
