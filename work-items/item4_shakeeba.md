# Item 4: Operations

**Owner:** Shakeeba · **Backup:** Sam · **LED:** 21, 22 (k6), 23, 24 (handover)

**Slice 1:** yes: the demo and its evidence.

- Two instances anyone can deploy from the handover document alone.
- Health visible on Grafana; proof the service survives the faults testers inject.

**Builds on:** metric names (items 1, 3, 5, 6); SQL (item 1). **Used by:** the assessor at the deploy gate.

### Steps

1. [x] **Slice-1 bench and evidence.** `docs/evidence/slice-1-demo.md`.
2. [x] **k6 (LED-22).** `scripts/k6/holds.js`, alone and during settlement ([evidence](../docs/evidence/led-22-holds-k6.md)).
3. [ ] **Dev kit on every laptop.** `LEDGER_URL=http://ledger:8080`, `FAKE_EXCLUDE=ledger` in `dev-kit/.env`.
   *Done when:* `/api/ledger/hello` answers from `ledger-1` or `ledger-2` everywhere.
4. [ ] **Tags.** `checkpoint-1` (Sat 11:00), `checkpoint-2` (15:50), `checkpoint-3`, `release` (Sun 16:00).
   *Done when:* each has a green CI run.
5. [x] **Dashboard (LED-23).** `grafana/ledger.json`: hold rate, latency, result; settlement time; SettlementIssue count; parked refunds; dead letters. Alert: a refund parked over 6 h.
   *Done when:* it shows live traffic.
6. [x] **Outage drills (LED-21).** Pause Kafka 5 min, stop MySQL, kill one instance, stop both for 10 min ([procedure](../docs/DRILLS.md), [evidence](../docs/evidence/led-21-outage-drills.md)).
   *Done when:* nothing is lost and everything catches up once.
7. [ ] **Handover (LED-24).** All 11 template sections in `docs/HANDOVER.md`.
   *Done when:* a teammate deploys from it without asking anything.
