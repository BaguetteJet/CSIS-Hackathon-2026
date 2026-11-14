# LED-21: outage drills

All four drills passed: nothing lost, everything applied once. Four findings below. Procedure: [DRILLS.md](../DRILLS.md).

Sat 14 Nov 2026, one laptop (Windows, Docker). Dev kit with `--profile full`, `LEDGER_URL=http://ledger:8080`, `FAKE_EXCLUDE=ledger`. Both instances from `main` at `13e63ea` (`docker compose up -d --build`, started 18:33; schema V9). Times UTC. Start, 18:34:08: HOLD 3,549, TOP_UP 416, outbox unsent 0, ledger.v1 4,210, ledger.dlt 0, lag 0, balanced checks all 0. Before and after are from `scripts/drills/check.sh`.

| Drill | Fault, for how long | Work during it | During | Before → after | Passed |
|---|---|---|---|---|---|
| D1 | Kafka paused, 18:51:02–19:01:58 (10 min 56 s; planned 5) | 20 holds | 20 × 200 PLACED, alternating instances, max 1.08 s (the first call to each instance; the rest 0.03–0.32 s). At 19:01:53: unsent 20, HOLD 3,569, health 200 on both, balanced checks 0 | At 19:05:49: HOLD 3,549 → 3,569; unsent 20 → 0; ledger.v1 4,210 → 4,231 (20 HoldPlaced, one of them twice: finding 1); lag 0; dlt 0; balanced checks 0 | **Pass** |
| D2 | MySQL stopped, 20:08:26–20:13:18 (healthy) (the laptop slept from about 19:13 to 20:06, before the stop) | 10 holds | 10 × 503, none PLACED: seven in 1.01–1.05 s, three in 1.98, 2.97 and 3.34 s (finding 2) | At 20:14:20, after 10 × 200 PLACED (0.02–0.17 s): HOLD 3,569 → 3,581, which is the 1 hold at 19:12, 1 more at 20:05:37 (finding 3) and these 10; none during the outage; unsent 0; lag 0; dlt 0; balanced checks 0. Then, 20:14:54: both instances UP, pools 10 connections, 0 pending, no WARN or ERROR since MySQL came back; every `devkit-*` container up | **Pass** |
| D3 | `ledger-2` killed (`docker kill`, exit 137, not restarted by Docker) at 20:18:14, right after `./sim money-in --students 100` (existing students, new top-ups), until 20:23:03 (`docker start`, health 200) | 100 top-ups, 10 holds | Top-ups applied 20:18:13.8–20:19:01.9: 42 before the kill, 42 after `ledger-1` took `ledger-2`'s partitions at 20:18:59 (45 s, Kafka's session timeout), the rest in between. Holds: 5 × 200 PLACED on 8081, 5 × no connection on 8082. At 20:19:47: TOP_UP 416 → 516, HOLD 3,581 → 3,586, ledger.v1 +105 (100 TopUpCredited, 5 HoldPlaced, no repeats), unsent 0, lag 0, balanced checks 0 | At 20:23:19, after the restart: TOP_UP 516, HOLD 3,586, ledger.v1 4,348, all unchanged; health 200 on both; unsent 0; lag 0; dlt 0; balanced checks 0 | **Pass** |
| D4 | Both stopped (`docker compose stop`) 20:31:51.4, started (`docker compose start`) 20:41:57.6, health 200 on both 20:42:11: down 10 min 6 s, not answering 10 min 20 s (Docker's `FinishedAt` and `StartedAt`) | 20 top-ups, 2 holds | Both shut down cleanly in about 1 s (consumers left the group, pool closed); `ledger-1` still exited 137, `ledger-2` 143, not explained. At 20:37:51: holds no connection on both, the gateway's `/api/ledger/hello` 502 `NOTHING_ANSWERS`; the 20 top-ups waiting in Kafka (lag users.v1 20, payments.v1 20) | The 20 top-ups applied at 20:42:14.3–14.8, 3 s after both were healthy. At 20:42:32: TOP_UP 516 → 536, HOLD 3,586 unchanged, ledger.v1 4,348 → 4,368 (20 TopUpCredited, no repeats), lag 40 → 0, unsent 0, dlt 0, balanced checks 0, health 200 on both | **Pass** |

After every drill: health 200 on both, outbox unsent 0, consumer lag 0, postings sum 0, no movement out of balance, no student balance off its postings, `ledger.dlt` unchanged.

## What we found

1. **D1: one event sent twice after Kafka came back.** HoldPlaced `49f75289-4c37-474b-86e3-a7d59dba2796` is on `ledger.v1` partition 5 at offsets 800 and 820, about 1 s apart; its outbox row was saved 18:55:43 and marked sent 19:02:01. Cause: a send waits up to 60 s for Kafka's metadata (the producer's `max.block.ms` default) before the relay's own 10 s limit applies, and the publisher lease lasts 10 s. `ledger-1` held the lease, blocked in sends, and `ledger-2` took the lease at 18:55:53 while `ledger-1` kept sending until 19:02:01 ("no longer the outbox publisher"). For those 6 minutes both relays sent, and both sent the first unsent row when Kafka returned. Nothing was lost and the books took each hold once; consumers skip a repeated eventId (inbox), as the contracts expect. Possible fix, configuration only: `spring.kafka.producer.properties.max.block.ms` under 10 s (item 1; `platform/` stays as it is).
2. **D2: three of the ten 503s took 2–3.3 s, over the hold call's 2 s.** The other seven took about 1 s, the pool's `connection-timeout`. The slow ones include the first call to each instance after the stop. Not yet explained: likely each call first checks the pool's idle connections, now dead, one at a time (`validation-timeout: 250` each), before the 1 s wait for a new one. Outcome is still safe: every call failed with no hold placed, so nothing is half-saved.
3. **D2: one extra hold before the outage.** 1 cent for the drill student `acb10966-fbc9-3577-92de-f45dbab185fb`, deal `eeb42ac7-122c-4d06-a545-1b2013a45a0f`, at 20:05:37, about 3 min before MySQL stopped: D2 step 1 (`check.sh; holds.sh 1`) run a second time by hand when the laptop woke. Outside the outage, so it does not change D2's result. Not a Ledger fault.
4. **D3: top-ups on the dead instance's partitions waited 45 s.** Kafka moved `ledger-2`'s partitions to `ledger-1` only when its session timed out (default 45 s); 42 of the 100 top-ups were applied then, once each. Nothing lost, but a student sees those top-ups up to 45 s late after a crash. A clean stop (`docker compose stop`) leaves the group at once. A shorter `session.timeout.ms` would cut the wait (item 1 to decide).

## Still to do

| What | Who |
|---|---|
| Explain finding 2 (503s over 2 s with MySQL down) | Item 4 (Shakeeba); any pool change: item 1 (Hersh) |
| Alert on unsent outbox rows (EDGE-CASES X8) | Item 4 (Shakeeba) |
| Finding 1: producer `max.block.ms` under the 10 s lease | Item 1 (Hersh) |
| Finding 4: shorter consumer `session.timeout.ms` | Item 1 (Hersh), with item 9 (Cian) |
| Crash between save and publish (EDGE-CASES O2, O3, K4) | Item 9 (Cian) |
