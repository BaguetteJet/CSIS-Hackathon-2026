# Open questions

What the spec did not settle, what the starter does meanwhile, and what to decide.

## Needs a decision before Thursday

1. **Decided (8 November): keep the organisation private and use tokens.**
   - Every student makes one Forgejo token with `read:package` and `write:repository` (README, "Your first hour", and the dev kit README, Install, step 1): `read:package` for Gradle and `docker login` from Tuesday, `write:repository` for `git push` to the team repository from Friday. It goes in `~/.gradle/gradle.properties`.
   - Each team's CI uses the repository secret `PACKAGES_TOKEN`. The workflow's own token cannot read the organisation's packages: it gets 401.

   The starter already works this way. The contracts repository's OPEN-QUESTIONS.md item 5 records the same decision.
2. **Closed (9 November): the contracts library's form.** Spec C1 ("Getting started and testing") now says "The contracts library, as a Gradle dependency from the organisation's package registry", which is what the starter uses. Nothing left to do.
3. **CI pushes images with `PACKAGES_TOKEN`,** so that token needs write:package as well as read:package. Decide whose token it is: one organiser token for all 16 repositories, or one per team.

## The names the starter and the dev kit share

Closed (9 November): the dev kit is built (`repos/dev-kit`, published 8 November) and uses these names (each was found in its files on 9 November). The table stays as the record of what both sides rely on. They all live in `service.env` and `local.env`, so a change touches those two files only.

| What | Starter and dev kit use |
|---|---|
| Docker network | `hackathon`, external |
| Kafka | `kafka:29092` inside Docker, `localhost:9092` from the laptop (as in Week 8); auto-create off; 7 topics × 6 partitions |
| MySQL | `mysql:3306`; one schema per service named after it; user and password both the service name |
| Dev sign-in public key | `http://signin:8080/.well-known/jwks.json` (a JWKS, RS256); from the laptop through the gateway at `http://localhost:8080/.well-known/jwks.json` |
| Token claims | `sub` user ID, `role`, `restaurantId` for staff (spec C1), plus optional `name` (shown in the UI kit header) |
| Token in the browser | `localStorage["groupbuy.token"]`, on the gateway's origin |
| Sign-in page | `/signin/?return=<path>` on the gateway |
| Platform clock | `GET http://clock:8080/internal/clock` answers `{"now": "<ISO UTC>"}` (plain ISO text is also accepted); from the laptop `http://localhost:8090/internal/clock`, never through the gateway |
| Mailpit | `mailpit:1025` |
| The hold call for Orders | `LEDGER_URL=http://fakes:8080` (the fake Ledger, by default; `http://localhost:8091` from IntelliJ), `http://hold-stub:8080` (the hold stub, to test each answer) or `http://ledger:8080` (a real Ledger) |
| Gateway | `/api/<service>/` and `/app/<service>/` to `http://<service>:8080`; both instances join the alias `<service>` |
| Dead-letter topics | `<service>.dlt`, 6 partitions, created by the service at startup. The platform team's replay tool reads them, with the reason in the `kafka_dlt-exception-message` header |

## Choices made where the spec is silent

4. **Retries** before dead-lettering: five, at 1, 2, 4, 8 and 16 seconds (rule 8 says five with growing gaps). They block that partition for about 31 seconds; that is Spring Kafka's standard behaviour.
5. **Broadcast consumers** (one group per instance) are named as spec B's example, `<service>-live-<instance>` (`experience-live-1`), with the instance taken from INSTANCE_ID or the hostname minus a leading `<service>-`. The name is the same at every restart, so a restarted instance carries on from its committed offset; a group that has never run starts from the latest event. They skip bad messages instead of dead-lettering them: the service's main consumer dead-letters them already. This suits live counts, the spec's only use.
6. **The outbox lease** lasts 10 seconds and is renewed before every send, with times taken from MySQL so both instances agree. If the publishing instance stops, the other takes over within 10 seconds.
7. **Timers** are claimed only by an instance that has a handler for their type, so during a deploy an old instance never takes a new type. A failing handler is retried after 1, 2, 4 ... seconds, up to 10 attempts, then marked FAILED.
8. **The hold stub** modes are `placed`, `insufficient`, `slow` (3 seconds, over the 2-second limit) and `down`, as in TASKS item 4.
9. **`Idempotency-Key`** records are per caller (the user ID), kept for ever; the body is compared with its JSON keys sorted.
10. **Health** leaves out mail, so Mailpit being down never marks a service down.
11. **Docker builds copy the jar Gradle built** instead of building inside Docker. This avoids the Maven Central 429 trap and keeps tokens out of images. CI builds the jar first.
12. **`make check` is `./check`.** Git Bash on Windows has no `make`; the Makefile calls `./check` for macOS and Linux.
13. **Testcontainers 1.21.4** replaces Spring Boot 3.4.3's 1.20.5, which cannot talk to Docker 29 (this laptop runs Docker 29.8.2). Students on current Docker Desktop would hit "Could not find a valid Docker environment" otherwise.
14. **The starter's own `SERVICE_NAME` is `platform`,** a valid name, so the template itself runs and passes CI. `scripts/set-service-name.sh <name>` sets the team's name on Thursday. For `orders` it also switches the hold client to HTTP.
15. **The hold client follows `LEDGER_URL`** (spec B: Orders adds only `LEDGER_URL`). Set, it calls the Ledger over HTTP; empty, the in-process stub answers, and Orders logs a warning. `HOLD_CLIENT=http` or `stub` still forces either.
16. **Every non-broadcast `@KafkaListener` with an `id` needs `idIsGroup = false`,** or Spring Kafka uses the id as the group. `ConsumerGroupCheck` stops the service at start-up, naming the listener, if any listener not made by `broadcastListenerFactory` is on a group other than `SERVICE_NAME`.

## For the integration repository

17. **Broadcast group names depend on the hostname.** The starter's `compose.yml` sets `hostname: <service>-1` and `<service>-2`, giving `experience-live-1` and `-2`. Integration should do the same, or set `INSTANCE_ID=1` and `2`. With Docker's default hostname (the container ID) the group would change whenever a container is re-created, which works but leaves old groups behind.
