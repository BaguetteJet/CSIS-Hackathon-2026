# 0001 Split the work by money flow

**Protects.** Every LED item has one owner

**Problem.** Seven people, one service. Each area needs an owner and a backup.

**Decision.** One area per way money moves: holds, top-ups, settlement, refunds, cash-outs. Each area has its own listener or API and its own tables. The lead owns holds, releases and settlement: they all change the same hold, and together they are the golden path.

| Area | Starts from |
|---|---|
| 1 Holds, integration | Hold call; orders.v1, deals.v1 and users.v1 listeners |
| 2 Core | Posting service and accounts, used by every area |
| 3 Payments events, contracts | payments.v1 listener, contract tests |
| 4 Operations | CI, Docker, Grafana, handover document |
| 5 Settlement, releases | Called from the deals.v1 listener |
| 6 Refunds | support.v1 listener |
| 7 Statement, desk, testing | Page API, property test |
| 8 Cash-outs, payouts | Called from the payments.v1 listener |

**Rejected.**
- Split every story by layer (one owner for the controllers, one for the SQL, one for the Kafka events): a hold's answer, money and event commit in one transaction (0003), and so do a settlement's (0007). Each LED item would need three owners editing one transaction, and nobody would own the rule it has to prove, such as "a retried hold gets its first answer". Areas 2 and 4 are layers on purpose: money must post one way for every flow (0002), and there is one CI and one dashboard. Each gives the others a small, fixed interface instead of a slice of every story.
- One owner per LED item: LED-03, 04, 05, 08 and 09 all change the same hold, so its state machine (0008) would have five owners.

**Cost.** Every area depends on area 2, so its functions were agreed first. Areas that share a listener (1 and 5, 3 and 8) or a function one calls in the other (5 and 6) agree it up front.

**Proof.** [OWNERS.md](../../OWNERS.md): an owner and a backup per area. One Forgejo issue per LED item (#7–#30), assigned to its owner.
