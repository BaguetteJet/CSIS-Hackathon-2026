# Interface targets

Constraints drawn from per-team spec and a set decided upon by group. All verification link to `docs/evidence/`.

## Orders: the hold call

| | Target | Source | Measured | Evidence |
|---|---|---|---|---|
| ✅ | 100 holds/s for 20 s, every answer under 2 s | LED-22 | Max 337 ms, p95 45 ms, 100% checks | [led-22](evidence/led-22-holds-k6.md) |
| ✅ | The same while a 200-order deal settles on the same students, every answer a 200 | LED-22, marked-property table | 8,000 holds over 4 runs, none a 503; max 950 ms. Busy CPUs: p95 101 ms, max 330 ms | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ✅ | A held-up hold answers 503 within 2 s, saves nothing | ORD-13, LED-21 | `HoldsTest` | [flaky-tests](evidence/flaky-tests-2026-11-14.md) |
| ✅ | Database down: 503 within 10 s, never 500; normal within 60 s of its return | LED-21 | Slowest 1.18 s; normal 8 s after. Incomplete: a hand run before #59 had three of ten at 1.98–3.34 s, still under 10 s; to measure again on `main` | [led-21](evidence/led-21-outages.md), its follow-ups |
| ✅ | Kafka paused 5 min: holds still answered | LED-21 | 40 PLACED, slowest 0.18 s | [led-21](evidence/led-21-outages.md) |
| ✅ | PLT-14 shape: 10 deals × 200 students, each student once per deal, at 100/s; every answer under 2 s | PLT-14 | Dev kit (`scripts/k6/rush.js`): p95 70 ms, max 289 ms. Integrated line: **Not yet** | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ✅ | Then the deals withdrawn: every hold released. **Ours (proposed):** within 60 s | PLT-14 | 1,600 releases in 4.6–4.7 s | [load 15 Nov](evidence/interface-load-2026-11-15.md) |

## Deals: settlement

| | Target | Source | Measured | Evidence |
|---|---|---|---|---|
| ✅ | A 200-order deal settles in one transaction | LED-05, LED-22 | Live, during the rush: 0.21–0.87 s over 4 runs; alone 0.27–0.49 s; busy CPUs 0.36 s. `SettlementTest` allows 3 s (`7ee6b54`); 1.74 s was seen live on 14 Nov (EDGE-CASES Q2) | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ☐ | The same on the integrated line | PLT-14 | **Not yet** | |
| ✅ | Ledger down 10 min: settlements, top-ups, releases catch up once | LED-21 | Healthy 13 s after start; balances right within 2 s | [led-21](evidence/led-21-outages.md) |

## Student experience: the Account page

| | Target | Source | Measured | Evidence |
|---|---|---|---|---|
| ✅ | `GET /api/ledger/me` and `/me/entries` during 100 holds/s. **Ours (proposed):** p95 under 250 ms, leaving them 750 ms | Reference, Student experience | Through the gateway: p95 27–50 ms in clean runs, 209 ms in run 1; max 865 ms | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ✅ | Statement newest first; entries net to `availableCents` | LED-11 | `StatementTest` | PHASES |
| ✅ | Sign-in key down: 503, never a wrong balance | LED-18 | `SignInUnavailableTest` | PHASES |

## Payments: the desk and money in and out

| | Target | Source | Measured | Evidence |
|---|---|---|---|---|
| ✅ | `GET /api/ledger/students/{id}` at the desk. **Ours (proposed):** p95 under 250 ms during the rush | LED-12 | p95 35–85 ms, max 807 ms | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ✅ | TopUpCredited after TopUpRecorded. **Ours (proposed):** within 5 s when nothing is down | LED-02 | 20 top-ups mid-rush: 1.2–2.0 s, including starting the producer | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ✅ | No valid event dead-lettered by an outage up to 2 min | LED-21 | Dead letters 2 → 2 | [led-21](evidence/led-21-outages.md) |
| ✅ | A cash-out and a hold racing for the same money: one wins | LED-15 | `WithdrawalsTest` | PHASES |

## Support: refunds

| | Target | Source | Measured | Evidence |
|---|---|---|---|---|
| ✅ | A kitchen failure sends up to 300 RefundApproved within 60 s. **Ours (proposed):** every RefundPosted within 60 s of the last one | Reference, Support | 300 at once: 1.9–3.6 s; 45 s at about 3× the spec's rate | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ✅ | No refund over its charge; early refunds parked, then posted or refused | LED-13, LED-14 | `RefundsTest` | PHASES |

## Platform: the line

| | Target | Source | Measured | Evidence |
|---|---|---|---|---|
| ✅ | Each instance within 512 MB | LED-22 | Up to 457 MiB after four runs, then steady; heap capped at 60% (Dockerfile); no out-of-memory kills | [load 15 Nov](evidence/interface-load-2026-11-15.md) |
| ✅ | One instance killed: the other carries on; repeats change nothing | LED-21 | 116 PLACED on `ledger-2`, slowest 0.24 s | [led-21](evidence/led-21-outages.md) |
| ✅ | Every published event valid against contracts 1.3.0 | Money check, CI | `ContractTest` | PHASES |
| ✅ | An instance starts while MySQL is busy: start-up never fails on one slow connection | LED-21 | Was: the daily check's start-up listener ended the instance (seen once in `TwoInstancesTest`, 15 Nov). Fixed on `main` (`e5105ad`) | EDGE-CASES Q23 |


