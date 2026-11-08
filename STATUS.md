# Status

Updated Sunday 8 November 2026.

## Done when (from the brief)

| Item | State | How it was checked |
|---|---|---|
| Two instances run locally within 512 MB each against the dev kit | **Done, against a stand-in** | `./check` started `platform-1` and `platform-2` from `compose.yml` with a 512 MB limit each. Both were UP, using 362 MB and 331 MB. A `UserRegistered` sent to `users.v1` reached `known_user`, exactly one instance held the outbox lease, and the logs had no errors. The real dev kit does not exist yet, so the stand-in was MySQL and Kafka on the `hackathon` network under the names in `service.env` |
| A timer created on one instance fires exactly once when that instance is killed | Done | `TwoInstancesTest`: a third instance schedules a timer and is shut down before it is due; after the clock moves on, the timer fires once and is FIRED. Also, 40 due timers with two instances running each fire exactly once |
| An event published twice is processed once; an early event is parked, then applied | Done | `ConsumerPatternsTest`, through real Kafka and MySQL: a repeated `UserRegistered` sent after a rename does not undo the rename; a `RoleChanged` sent before its `UserRegistered` is parked, then applied, and the parking table empties |
| A malformed message lands on the dead-letter topic and the consumer keeps going | Done | `ConsumerPatternsTest`: an invalid JSON message and a JSON message missing fields both reach `platform.dlt` with the reason header, and the next valid event is still stored |
| With both instances publishing, one key's events arrive in order | Done | `TwoInstancesTest`: 100 events for one deal, published from both instances by 8 threads, arrive with sequences 1 to 100, in order, none missing or repeated |
| CI passes on Forgejo and pushes an image | **Done on a local Forgejo 17.0.1** | Rehearsal repository `hackathon-2026/starter`: CI went green on `main` and on the tag `v0.1.0`, and pushed the image tagged `sha-80f139a` and `v0.1.0`. The push went to a plain registry, because the rehearsal server's internal name could not be reached for Docker's login; on the real server both names are the same. Not yet run on cs4297.skynet.ie |
| `make check` passes from a clean clone on Windows (Git Bash) and macOS | **Windows done; macOS not checked** | A fresh clone from the rehearsal Forgejo, with `core.autocrlf=true`: `./check --down` printed `Ready`. The execute bits survived |

Test totals:
- `./gradlew test`: 10 tests, all passing (8 platform unit tests and 2 contract tests), plus the zero-sum template, skipped on purpose.
- `./gradlew containerTest`: 18 tests, all passing.

## What is in it

- **Platform plumbing** (`platform/`):
  - outbox with a single publisher (lease row) and per-key sequences;
  - consumer inbox (once per eventId), parking table, error-handling deserializer, retries and dead-letter topic, broadcast listener factory;
  - timers with `SKIP LOCKED`, platform clock;
  - hold client (HTTP with a 2-second limit, and a stub with four modes);
  - Problem Details, `Idempotency-Key`, JWT resource server, `CurrentUser` with access helpers;
  - pages served at `/app/<service>/`, health and metrics.
- **Examples** (`example/`): `GET /api/<service>/hello`; a `users.v1` consumer showing once, parking and ignoring; a broadcast consumer.
- **Pages:** `ui-kit.css` (the prototype's colours, light and dark, Inter), `ui-kit.js` (header and navigation), `api.js` (token, errors, euro and Irish-time helpers), and an example page with a form, a table and errors.
- **Running it:** Dockerfile, `compose.yml` (two instances, 512 MB, `hackathon` network), `service.env`, `local.env`, `./check`, Makefile, `scripts/run-local.sh`, `scripts/set-service-name.sh`.
- **CI:** `.forgejo/workflows/ci.yml` (tests, jar, image push, Gradle cache).
- **Test support:** `ContainersTest`, `TestTokens`, `TestClock`, and second instances in one test.
- **Scripts:** k6 last-place (`scripts/last-place.sh`, k6 in Docker), the zero-sum property template, and `./gradlew liveConnections` (2,000 server-sent-event connections).
- **README** for students, with the trap list.

## Found and fixed while proving it

- **Testcontainers 1.20.5**, the version Spring Boot 3.4.3 brings, cannot talk to Docker 29. Pinned 1.21.4.
- **Forgejo sets `FORGEJO_TOKEN` in every CI job** to the job's own token, which silently replaced the secret. Both this workflow and the contracts one now pass credentials as `ORG_GRADLE_PROJECT_*`. Contracts' publish step was rehearsed again as 1.0.1 and passed.
- **The workflow's own token cannot read the organisation's packages.** Hence `PACKAGES_TOKEN` (OPEN-QUESTIONS item 1).
- **The broadcast example dead-lettered bad messages a second time.** It now has its own error handler that skips them.
- **Mail health would have marked a service DOWN whenever Mailpit was.** It is now left out of health.
- **Kafka's ports were reversed from the Week 8 lab.** They now match: `kafka:29092` inside Docker, `localhost:9092` outside.

## Open

1. **The decisions in OPEN-QUESTIONS.md items 1 to 3**, before the starter is copied on Thursday.
2. **The dev kit must provide the names in OPEN-QUESTIONS.md** ("The dev kit must match these"), or `service.env` and `local.env` change to match it. Then re-run `./check` against the real dev kit.
3. **Not run yet:** on macOS, on cs4297.skynet.ie's runners, the k6 last-place test (it needs Orders and seeded tokens), and the 2,000-connection script (it needs Student experience). Both scripts compile and are wired into Gradle and Docker.
4. **Copying in on Thursday:** run `scripts/set-service-name.sh <name>` in each team repository, set its `PACKAGES_TOKEN` secret, and push.
