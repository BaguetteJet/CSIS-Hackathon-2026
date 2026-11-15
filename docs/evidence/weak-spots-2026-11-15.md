# Weak spots, 15 Nov 2026

Sun 15 Nov, 04:20–04:35 UTC, the dev kit with both Ledger instances (`ledger:local` built from `66d1461`, the same `src/main` as `322a5b1`). A read of every file in `src/main/.../ledger/` and the platform's consumer, inbox and outbox code, then live probes. The issues found and their fixes: [EDGE-CASES Q11 to Q17](../EDGE-CASES.md#cases-that-cause-issues).

## The hold call from outside (through the gateway, `localhost:8080`)

| Path posted | Answer |
|---|---|
| `/internal/ledger/holds` | 404 from the gateway |
| `/api/ledger/../internal/...`, `%2e%2e/`, `..%2f`, `/;/../` | 404 from the gateway |
| `/api/ledger/..;/..;/internal/ledger/holds`, `%2e%2e;/`, `..%3b/`, the same under `/app/ledger/` | **Reached the Ledger**, which answered 400 (Spring Security's firewall refuses `;`). No hold row was written |

`docker port ledger-1`: `0.0.0.0:8081`. The instances' ports are published on every interface, so the hold call (no token, by design) is reachable from the network without the gateway.

## Hostile hold bodies (`localhost:8081`)

| Body | Answer |
|---|---|
| `amountCents` given twice | 200: the last value is used |
| 100,000 nested `[` (200 KB) | 413 in 0.03 s |
| A 60,000-digit number | 422 in 0.01 s |
| 70 KB, chunked with no length | 413 |
| `1e2`, `2^63` | 422 |
| `Long.MAX_VALUE` | 200 `INSUFFICIENT_FUNDS` |
| Upper-case UUIDs | 200; stored lower-case, one pair of accounts for the student |
| `charset=ISO-8859-1` | 200 |
| `GET` | 405 |

## MySQL frozen, not stopped (`docker pause devkit-mysql-1` for 12 s)

| Call | Answer |
|---|---|
| Hold on instance 1 | 200 after **12.29 s**, once MySQL was back (a stopped MySQL gives 503 in about 1 s: [LED-21](led-21-outages.md)) |
| `/actuator/health` on instance 2 | 503 in 3.0 s |

Afterwards both instances were UP with no manual step. The log shows Hikari dropping connections that failed validation while MySQL was paused.

## The books on the dev kit (every scenario run since Friday)

419 movements and 185 holds. I1 to I6 hold: postings sum to 0, no movement or student off, HELD equals open holds plus reserved cash-outs, hold movements match their states, settlement totals match the charges, and nothing is unpublished after a minute. No open hold on a closed deal, and no parked refund.

## From the code (not run)

- A consumer retries a failed event 5 times (5 + 10 + 20 + 40 + 80 s = 155 s), then dead-letters it (`EventConsumerConfig`). A MySQL outage over about 2.5 min therefore dead-letters valid events; LED-21 was run to 2 min 13 s.
- `DailyCheck.check()` checks I1 and I2, not I3 or I5.
- `ContractJson` ignores unknown fields, but an unknown enum value fails to read and goes to `ledger.dlt` at once.
- CI runs no container tests (the organisers' rule), and `./check` only runs them with `--with-containers`.

**Follow-up, 05:00–05:05 UTC, branch `item-4/outage-hardening`** (Q11, Q12, Q13, Q15 fixed). The suites passed with 26 unit, 13 contract and 103 container tests, and `./check` said Ready (334 and 328 MiB):
- `docker port ledger-1` now shows `127.0.0.1:8081`. The LAN address `192.168.1.48:8081` doesn't answer, and the gateway still does (`/api/ledger/me` 401).
- MySQL paused 12 s: three holds answered 503 in 2.20, 2.60 and 10.16 s (the last waited on two 5 s socket timeouts). Holds were normal 3 s after it returned.
- MySQL stopped for 200 s: a top-up sent 8 s in waited 195 s (past the platform's 155 s), was credited 16 s after MySQL returned, and `ledger_dead_letters` stayed at 2 with no `Dead letter:` line. Both instances were UP after.
- The books on the dev kit: I1 to I6 hold over 420 movements and 186 holds.
