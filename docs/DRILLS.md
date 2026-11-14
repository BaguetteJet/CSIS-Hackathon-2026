# Outage drills (LED-21)

Four faults from the brief, run on the dev kit against both instances. **Passed** means nothing is lost and everything catches up once. Results: [evidence/led-21-outage-drills.md](evidence/led-21-outage-drills.md). What each fault does and how to fix it in production: [HANDOVER §8 Failures](HANDOVER.md#failures-reference-scenarios-k).

Run from `line-a-ledger/` in Git Bash or a Unix shell, with the dev kit up and both instances built from the commit under test (`./gradlew bootJar && docker compose up -d --build`).

| Tool | Does |
|---|---|
| `scripts/drills/check.sh` | One snapshot: health of both instances, movements by type, unsent outbox rows, parked events, the three "balanced" checks (§8), `ledger.v1` and `ledger.dlt` end offsets, the `ledger` consumer group's lag |
| `scripts/drills/holds.sh N [studentId]` | N hold calls of 1 cent for one student, alternating 8081 and 8082; prints each status, time and result, then the counts |
| `./sim money-in --students N` (in `../dev-kit`) | N new students, each with one top-up |

Every drill: `check.sh` before, the fault, the work during it, the recovery, `check.sh` after. After must show both health 200, `outbox_unsent 0`, `consumer_lag 0`, the three balanced checks 0, `ledger_dlt_end` unchanged, and each movement count up by exactly the work sent.

## D1 Kafka paused 5 minutes (K1)

| Step | Command | Expect |
|---|---|---|
| Fault | `docker pause devkit-kafka-1` | |
| During | `scripts/drills/holds.sh 20` | 20 × 200 PLACED, each well under 2 s: holds don't need Kafka |
| During | `check.sh` | `outbox_unsent` 20 or more; Kafka lines `-` |
| Wait | 5 min from the pause | |
| Recover | `docker unpause devkit-kafka-1` | |
| After | `check.sh` (repeat until `outbox_unsent 0`) | `movements_HOLD` +20; `ledger_v1_end` +20 (more means the outbox resent; consumers skip repeats by eventId) |

## D2 MySQL stopped (K3)

| Step | Command | Expect |
|---|---|---|
| Before | `check.sh`; note a student ID from `holds.sh 1` | |
| Fault | `docker stop devkit-mysql-1` | |
| During | `scripts/drills/holds.sh 10 <studentId>` | 10 × 503 `UNAVAILABLE`, each in about 1 s, never a PLACED |
| Recover | `docker start devkit-mysql-1`, then wait for `healthy` in `docker ps` | |
| After | `scripts/drills/holds.sh 10 <studentId>`, then `check.sh` | 10 × PLACED; `movements_HOLD` +11 (the 1 before and the 10 after), none from the outage |

## D3 One instance killed while top-ups arrive (K2)

| Step | Command | Expect |
|---|---|---|
| Fault | `cd ../dev-kit && ./sim money-in --students 100 && docker kill ledger-2; cd -` | |
| During | `scripts/drills/holds.sh 10` | 8081 answers PLACED; 8082 fails to connect (`000`): callers use the other instance |
| During | `check.sh` after a minute | `ledger-1` took ledger-2's partitions: `consumer_lag 0` |
| Recover | `docker start ledger-2` | |
| After | `check.sh` | `movements_TOP_UP` +100 exactly; `movements_HOLD` +5 |

## D4 Both instances stopped 10 minutes (K3, X4)

| Step | Command | Expect |
|---|---|---|
| Fault | `docker compose stop` | |
| During | `cd ../dev-kit && ./sim money-in --students 20; cd -` | Published; nothing applies them yet |
| During | `scripts/drills/holds.sh 2` | Both fail to connect (`000`) |
| Wait | 10 min from the stop | |
| Recover | `docker compose start`, then wait for health 200 on both | |
| After | `check.sh` | `movements_TOP_UP` +20 exactly; `consumer_lag 0` |
