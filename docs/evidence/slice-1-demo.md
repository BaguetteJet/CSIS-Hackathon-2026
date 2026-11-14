# Slice 1 demo evidence (item 4)

Owner: Shakeeba · Backup: Hersh



## Bench setup (done Fri 13 Nov)

- Dev kit running with the lite profile

- Ledger built with bootJar, two replicas started

- Health: 8081 UP, 8082 UP

- dev-kit .env: LEDGER_URL=http://ledger:8080, FAKE_EXCLUDE=ledger

- Gateway check: /api/ledger/hello answered from ledger-2 as Aoife Murphy



## Commands used

Captured Sat 14 Nov 00:21–00:25 UTC on Hersh's bench (`slice-1/hold-api`, after `./check`).

```bash
./sim money-in --repeat
curl -s -X POST localhost:8081/internal/ledger/holds -H 'Content-Type: application/json' \
  -d '{"orderId":"<order>","studentId":"<student>","dealId":"<deal>","cutoffAt":"2026-11-14T12:00:00Z","amountCents":600}'
curl -s -H "Authorization: Bearer $(./token aoife)" localhost:8080/api/ledger/me
docker exec devkit-kafka-1 kafka-console-consumer --bootstrap-server localhost:9092 --topic ledger.v1 --from-beginning --timeout-ms 10000
```



## Windows notes

- ./sim must be run through Git Bash

- ./sim money-in prints nothing and exits 1, but publishes (HTTP 200)



## Evidence

Aoife is `432c5870-9e89-30aa-9249-59a1d6d35748`, funded 2000 by `money-in`.

- [x] One TopUpCredited per top-up after `money-in --repeat`: 4 top-ups, 4 credits, each from our outbox (e.g. topUp `2b192a69…`, event `0432ff88…`).
- [x] Funded hold: Aoife, 600, replica 8081, order `0d20bf22…` gives PLACED; HoldPlaced `df28bb46…`, 1400 / 600.
- [x] Refusal: 1401 on 8081, order `86f093c0…` gives INSUFFICIENT_FUNDS, availableCents 1400; HoldRejected `e9b00845…`.
- [x] Replay on 8082: same answers, even with another student and amount; still one HOLD movement, no new event.
- [x] Two-replica race: 2000 available, two 1200 orders, one per replica: one PLACED (`12f8c521…`), one INSUFFICIENT_FUNDS with 800.
- [x] Balances chain on ledger.v1 for every student, sequences 1..n. `/me` via the gateway: 0 / 2000; no token 401; union-admin 403.
