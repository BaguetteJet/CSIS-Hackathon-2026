# Open questions

What the spec did not settle, what the starter does meanwhile, and what to decide.

## Needs a decision before Thursday

1. **Decided (8 November): keep the organisation private and use tokens.**
   - Every student puts a `read:package` token in `~/.gradle/gradle.properties` (README, "Your first hour").
   - Each team's CI uses the repository secret `PACKAGES_TOKEN`. The workflow's own token cannot read the organisation's packages: it gets 401.

   The starter already works this way. The contracts repository's OPEN-QUESTIONS.md item 5 records the same decision.
2. **Spec C1 says the contracts library comes "as a Git submodule".** The contracts brief says it is a published Maven package, and that is what the starter uses. C1 needs one line changed: "The contracts library, as a Gradle dependency (the README shows the token setup)".
3. **CI pushes images with `PACKAGES_TOKEN`,** so that token needs write:package as well as read:package. Decide whose token it is: one organiser token for all 16 repositories, or one per team.

## The dev kit must match these (the starter assumes them)

The dev kit is not built yet, so the starter chose the names below. They all live in `service.env` and `local.env`, so a different choice changes those two files only. Most follow the Week 8 lab.

| What | Starter assumes |
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
| The hold call for Orders | `LEDGER_URL=http://hold-stub:8080` (the dev kit's hold stub, by default), `http://fakes:8080` (the fake Ledger) or `http://ledger:8080` (a real Ledger) |
| Gateway | `/api/<service>/` and `/app/<service>/` to `http://<service>:8080`; both instances join the alias `<service>` |
| Dead-letter topics | `<service>.dlt`, 6 partitions, created by the service at startup. The platform team's replay tool reads them, with the reason in the `kafka_dlt-exception-message` header |

## Choices made where the spec is silent

4. **Retries** before dead-lettering: five, at 1, 2, 4, 8 and 16 seconds (rule 8 says five with growing gaps). They block that partition for about 31 seconds; that is Spring Kafka's standard behaviour.
5. **Broadcast consumers** (one group per instance) start from the latest event, on a group that is new at each start, and skip bad messages instead of dead-lettering them: the service's main consumer dead-letters them already. This suits live counts, the spec's only use.
6. **The outbox lease** lasts 10 seconds and is renewed before every send, with times taken from MySQL so both instances agree. If the publishing instance stops, the other takes over within 10 seconds.
7. **Timers** are claimed only by an instance that has a handler for their type, so during a deploy an old instance never takes a new type. A failing handler is retried after 1, 2, 4 ... seconds, up to 10 attempts, then marked FAILED.
8. **The hold stub** modes are `placed`, `insufficient`, `slow` (3 seconds, over the 2-second limit) and `down`, as in TASKS item 4.
9. **`Idempotency-Key`** records are per caller (the user ID), kept for ever; the body is compared with its JSON keys sorted.
10. **Health** leaves out mail, so Mailpit being down never marks a service down.
11. **Docker builds copy the jar Gradle built** instead of building inside Docker. This avoids the Maven Central 429 trap and keeps tokens out of images. CI builds the jar first.
12. **`make check` is `./check`.** Git Bash on Windows has no `make`; the Makefile calls `./check` for macOS and Linux.
13. **Testcontainers 1.21.4** replaces Spring Boot 3.4.3's 1.20.5, which cannot talk to Docker 29 (this laptop runs Docker 29.8.2). Students on current Docker Desktop would hit "Could not find a valid Docker environment" otherwise.
14. **The starter's own `SERVICE_NAME` is `platform`,** a valid name, so the template itself runs and passes CI. `scripts/set-service-name.sh <name>` sets the team's name on Thursday. For `orders` it also switches the hold client to HTTP.
