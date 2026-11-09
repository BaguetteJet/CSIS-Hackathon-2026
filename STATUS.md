# Status

Updated Monday 9 November 2026.

## Done when (from the brief)

| Item | State | How it was checked |
|---|---|---|
| Two instances run locally within 512 MB each against the dev kit | **Done, against a stand-in** | `./check` started `platform-1` and `platform-2` from `compose.yml` with a 512 MB limit each. Both were UP, using 362 MB and 331 MB. A `UserRegistered` sent to `users.v1` reached `known_user`, exactly one instance held the outbox lease, and the logs had no errors. At the time (before the dev kit was published on 8 November) the stand-in was MySQL and Kafka on the `hackathon` network under the names in `service.env` |
| A timer created on one instance fires exactly once when that instance is killed | Done | `TwoInstancesTest`: a third instance schedules a timer and is shut down before it is due; after the clock moves on, the timer fires once and is FIRED. Also, 40 due timers with two instances running each fire exactly once |
| An event published twice is processed once; an early event is parked, then applied | Done | `ConsumerPatternsTest`, through real Kafka and MySQL: a repeated `UserRegistered` sent after a rename does not undo the rename; a `RoleChanged` sent before its `UserRegistered` is parked, then applied, and the parking table empties |
| A malformed message lands on the dead-letter topic and the consumer keeps going | Done | `ConsumerPatternsTest`: an invalid JSON message and a JSON message missing fields both reach `platform.dlt` with the reason header, and the next valid event is still stored |
| With both instances publishing, one key's events arrive in order | Done | `TwoInstancesTest`: 100 events for one deal, published from both instances by 8 threads, arrive with sequences 1 to 100, in order, none missing or repeated |
| CI passes on Forgejo and pushes an image | **Done on cs4297.skynet.ie** (8 November) | On the real runners, commit `be02b3f`: the `build` job passed tests, jar and image push to the Forgejo registry. Rehearsed first on a local Forgejo 17.0.1 |
| `make check` passes from a clean clone on Windows (Git Bash) and macOS | **Windows done; macOS not checked** | A fresh clone from the rehearsal Forgejo, with `core.autocrlf=true`: `./check --down` printed `Ready`. The execute bits survived |

Test totals:
- `./gradlew test`: 18 tests, all passing (10 platform unit tests, 6 consumer-group check tests and 2 contract tests), plus the zero-sum template, skipped on purpose.
- `./gradlew containerTest`: 20 tests, all passing.

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

- **Single-page apps (8 November, for the front-end starter):** a path under `/app/<service>/` that is not a file and has no extension now answers `index.html`, so a React app's deep links work after a refresh. A missing `.js` or `.css` is still 404. This is in `WebTest`; the starter's 30 tests pass.
- **Accented names were garbled in the header** ("Oisín" read "OisÃ­n"): `api.js` decoded the token's claims as Latin-1. It now decodes them as UTF-8. Checked by running its decode line on Oisín's real token ("Oisín Kelly"); `test` (11) and `containerTest` (19) pass.
- **Dark mode hid secondary buttons' text** (dark green on a dark surface). `ui-kit.css` now uses the normal text colour for them.
- **Dark mode made danger buttons light pink with white text.** They are now a fixed red (`#b42318`, white text, about 6.5:1) in both themes.
- **The page kit produces the restaurant and Union screens.** The fake line's stand-in screens are built from `api.js`, `ui-kit.js` and `ui-kit.css` alone (mockups D1, D2, R1 to R3), and Lighthouse scored them 100.
- **After a deploy, browsers could keep the previous page or stylesheet.** Pages now answer `Cache-Control: no-cache` (a cheap 304 when unchanged); `WebTest` checks it.
- **Testcontainers 1.20.5**, the version Spring Boot 3.4.3 brings, cannot talk to Docker 29. Pinned 1.21.4.
- **Forgejo sets `FORGEJO_TOKEN` in every CI job** to the job's own token, which silently replaced the secret. Both this workflow and the contracts one now pass credentials as `ORG_GRADLE_PROJECT_*`. Contracts' publish step was rehearsed again as 1.0.1 and passed.
- **The workflow's own token cannot read the organisation's packages.** Hence `PACKAGES_TOKEN` (OPEN-QUESTIONS item 1).
- **The broadcast example dead-lettered bad messages a second time.** It now has its own error handler that skips them.
- **Mail health would have marked a service DOWN whenever Mailpit was.** It is now left out of health.
- **Kafka's ports were reversed from the Week 8 lab.** They now match: `kafka:29092` inside Docker, `localhost:9092` outside.
- **An empty `CLOCK_URL` silently used the laptop's clock** (9 November). `ClockUrlCheck` now stops the service at start-up with a message naming the fix, except under the `test` profile, which `ContainersTest` and the second-instance builders set (they run on `TestClock`). `./gradlew check` and `./gradlew containerTest` pass. Not yet seen: the start-up stop itself in a container (no test for it).
- **README:** troubleshooting rows for the `CLOCK_URL` stop, a 401 when the public key could not be fetched (service started before the dev kit), and Linux's `host.docker.internal`; step 3 gives Linux's `172.17.0.1:8081`. `set-service-name.sh` uses `grep -E`, not GNU's `\|`.

## Third review round (9 November, night)

Four fresh reviews (student, kitchen, Union, student developer). Every finding is fixed, except publishing, which waits for the organisers' approval.

**Money:**
- Part refunds of one order are rounded on the running total (`Money.refundReversal(..., refundedBeforeCents)`). Together they reverse exactly the commission charged.
- A kitchen failure refunds what is left of each order, not its full price. An order the Ledger charges after the failure is refunded once its charge arrives. A refund the Ledger refuses shows on the kitchen-failures screen.
- An order is REFUNDED once its refunds add up to the price. A no-show can be refunded (NO_SHOW → REFUNDED, R21).

**The fake Ledger:**
- It refuses holds on a confirmed, failed or cancelled deal with DEAL_CLOSED.
- It releases holds for orders that are not on the final list (E1 step 3).
- The starting balance has its own statement line.

**Complaints and accounts:**
- A complaint is allowed once per order.
- Unblocking a student who is not blocked is refused (409 NOT_BLOCKED).
- A refund can carry a note.

**Collection:**
- The collection code is signed (F2): `GB1.<order>.<deal>.<HMAC>`.
- The complaint page has a written path, `/app/support/complain?order=<id>`.

**The kitchen screens:**
- A confirmed deal never shows as gone.
- An accept that lost the race says so.
- An early hand-over explains the collection window.

**The student site:** a blocked student sees a notice in place of the Join button.

**Elsewhere:**
- The simulators refuse a cap below the first tier.
- The demo keeps B1's 20-minute gap.
- Schema IDs and OpenAPI versions say 1.1.0.
- The starter refuses to start without `CLOCK_URL`.

**Verified on rebuilt images:**
- Tests: contracts, starter and dev kit pass; fakes has 27 tests, all passing.
- Both journeys pass.
- After a reset and `./demo`, 37 of 37 live checks pass.
- Lighthouse scores 100 on 12 screens.
- Without `CLOCK_URL`, the starter stops with the documented message.

## Fourth review round (9 November)

**Changed:**
- **Consumer group (blocker).** `@KafkaListener(id = ...)` made the id the group (Spring Kafka's `idIsGroup` defaults to true), so `known-users` was not on `SERVICE_NAME`. The example listener and the README snippet now say `idIsGroup = false`, and the README says why. New `platform/ConsumerGroups.kt`: `ConsumerGroupCheck` (a `SmartLifecycle` in phase 0, before any listener container starts) stops the service, naming the listener and the fix, when a listener's group is not `SERVICE_NAME`. Listeners from `broadcastListenerFactory` are exempt: the factory records them in `BroadcastListeners`; their group must start with `<service>-`.
- **Expired token on public pages.** `OpenPathsTokenResolver` ignores the `Authorization` header on the platform's open paths (health, metrics, `/app/**`, `/internal/**`, `/error`) and on `PUBLIC_PATHS`. Everywhere else a missing, expired or forged token is still 401 with a Problem, never 302.
- **Orders and the stub.** `HOLD_CLIENT` now defaults to `http` when `LEDGER_URL` is set and `stub` when not; Orders on the stub logs a warning. `service.env` and `local.env` carry `LEDGER_URL=` instead of `HOLD_CLIENT=stub`; `set-service-name.sh orders` still writes both lines (checked on a copy). README settings table updated.
- **Test clock.** `PlatformClock.kt` and `Timers.kt` say `TestClock`. README and `TestSupport.kt` say to advance it and then wait with Awaitility (timers poll every 500 ms), with an example.
- **Problems example** shows the title the code really sends (`"Conflict"`, the reason phrase).
- **README:** step 1 asks for `read:package` and `write:repository` and says which is for what and when (as the dev kit README does). `service.env` points to the real section, "In IntelliJ, with the dev kit in Docker". New trouble row for the group check.
- **Broadcast groups** are `<service>-live-<instance>` as in spec B (`experience-live-1` for the container `experience-1`), without the random suffix, so a restart reuses the group.
- **OPEN-QUESTIONS.md:** item 2 closed (C1 now says Gradle dependency); the dev kit section closed (built; its files use these names); item 5 updated; items 15 to 17 added.

**Verified:**
- `./gradlew --offline test`: 18 tests, all passing (10 platform unit, 6 `ConsumerGroupCheckTest`, 2 contract), plus the zero-sum template, skipped. `ConsumerGroupCheckTest` builds real listener containers from `@KafkaListener` annotations (never started, no broker): an id without `idIsGroup = false` is reported and stops the context; `idIsGroup = false`, no id, and broadcast listeners pass.
- `./gradlew --offline containerTest`: 20 tests, all passing, including the new `WebTest` case (expired token: health and pages 200, `hello` 401 without a redirect). Every container test starts the full service, so the group check passed on the real listeners.

## Open

1. **The decision in OPEN-QUESTIONS.md item 3** (items 1 and 2 are settled), before the starter is copied on Thursday.
2. **Re-run `./check` against the real dev kit** (the 512 MB run used a stand-in before the dev kit was published). The names match (OPEN-QUESTIONS.md, "The names the starter and the dev kit share").
3. **Not run yet:** on macOS, the k6 last-place test (it needs Orders and seeded tokens), and the 2,000-connection script (it needs Student experience). Both scripts compile and are wired into Gradle and Docker.
4. **Copying in on Thursday:** run `scripts/set-service-name.sh <name>` in each team repository, set its `PACKAGES_TOKEN` secret, and push.
