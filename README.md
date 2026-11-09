# Your service

This repository is your team's service, already set up with everything a service in this system needs to be safe and observable: Kotlin and Spring Boot, MySQL with Flyway, Kafka with an outbox, consumers that process each event once, timers that fire once across two instances, sign-in, errors, metrics, a page template with the shared UI kit, Docker, CI and test scripts.

It has **none of your service's logic**. That is your design, and the part you are assessed on.

The versions are the Week 8 lab's: Kotlin 2.1.0, Spring Boot 3.4.3, Gradle 8.12, JDK 21.

## Your first hour

1. **The contracts token, once per laptop.** Create a Forgejo token with **`read:package` and `write:repository`** (your avatar, Settings, Applications). `read:package` is for Gradle (the contracts library) and `docker login` from the first day; `write:repository` is for `git push` to your team's repository, which the organisers fill with this starter on Thursday, so you push from Friday. If you already made the dev kit's token (its README, Install, step 1), it has both: reuse it. A token without `write:repository` makes the first push fail with 403 (the dev kit README's "When something goes wrong" has the fix). Put it in `~/.gradle/gradle.properties` (on Windows, `C:\Users\<you>\.gradle\gradle.properties`), never in this repository:
   ```properties
   forgejoUser=your-forgejo-username
   forgejoToken=the-token
   ```
2. **Start the dev kit** (its README), then run:
   ```bash
   ./check
   ```
   On macOS and Linux, `make check` does the same. It runs the tests, builds the image, starts two instances of your service against the dev kit (each capped at 512 MB), and checks both are healthy. When it prints `Ready`, push: CI runs too.
3. **Point the gateway at your service, and switch off its fake.** Until you do, http://localhost:8080 still sends `/api/<service>/` and `/app/<service>/` to the fake line. In the **dev kit's** `.env` (not this repository), change your service's line and `FAKE_EXCLUDE`, for example for Orders:
   ```properties
   ORDERS_URL=http://orders:8080
   FAKE_EXCLUDE=orders
   ```
   Use `http://<service>:8080` for the two containers `./check` started, or `http://host.docker.internal:8081` while your service runs in IntelliJ (on Linux, `http://172.17.0.1:8081` instead, with the firewall note in "When something goes wrong"). Then, in the dev kit's folder:
   ```bash
   docker compose --profile lite up -d
   ```
   The dev kit README's section "The fake line, and using your own service" has the details, and how to undo it.
4. **Sign in** on the dev kit's sign-in page (http://localhost:8080/signin/): as one of your team's seeded students (student ID and PIN 1234; the dev kit README lists them), or as a restaurant or Union account. Then open your example page at `http://localhost:8080/app/<service>/` and press "Call hello".
5. Read the two examples in `src/main/kotlin/.../example`, then build your first slice in packages of your own.

## What is where

| You need | Use | File |
|---|---|---|
| Publish an event | `outbox.publish(event)` inside your `@Transactional` method | `platform/Outbox.kt` |
| Consume an event once | `@KafkaListener` and `inbox.once(...) { }` | `platform/Events.kt`, `example/KnownUsers.kt` |
| An event that arrives too early | `parking.park(...)`, then `parking.release(...)` | `platform/Events.kt`, `example/KnownUsers.kt` |
| Something to happen at a time | `timers.schedule(type, key, at)` and a `TimerHandler` bean | `platform/Timers.kt` |
| The time | Inject `java.time.Clock`, call `clock.instant()`; never `Instant.now()` | `platform/PlatformClock.kt` |
| The hold call (Orders) | Inject `HoldClient` | `platform/HoldClient.kt` |
| The signed-in user | A `user: CurrentUser` parameter on a controller method | `platform/Security.kt` |
| Own data only, restaurant only, role checks | `user.requireOwner(...)`, `user.requireRestaurant(...)`, `user.requireRole(...)` | `platform/Security.kt` |
| An error the page can act on | `throw Conflict("DEAL_FULL", "...")`, `NotFound(...)`, `Invalid(...)` | `platform/Problems.kt` |
| `Idempotency-Key` | `idempotent.handle(key, caller, body) { ... }` | `platform/Idempotency.kt` |
| A table | A Flyway migration `V3__your_table.sql` in `src/main/resources/db/migration` | |
| A page | Copy `src/main/resources/pages/index.html`; use `api.js` and the UI kit's classes | `pages/` |
| Event classes, topics, Money, states, API shapes | The contracts library | its README |
| A test with real MySQL and Kafka | Extend `ContainersTest` | `src/test/.../support/ContainersTest.kt` |
| A test token, or time you move by hand | `TestTokens.student(id)`, `TestClock.advance(...)` | `src/test/.../support/TestSupport.kt` |

Leave the `platform` package as it is, with one exception: `PUBLIC_PATHS` in `platform/Security.kt`, the list of paths that need no token (Student experience's deal list, deal pages and live stream), is yours to fill in. On those paths the `Authorization` header is ignored, so a browser holding an expired token still sees them (and `CurrentUser?` is always null there). If something in it gets in your way, tell the Platform and reliability team or the organisers. Do not fork it quietly: sixteen services rely on it behaving the same way.

### Publishing

```kotlin
@Transactional
fun approve(dealId: UUID, user: CurrentUser): Deal {
    val deal = deals.findById(dealId).orElseThrow { NotFound("Deal $dealId") }
    // ... your change ...
    outbox.publish(DealPublished(/* the catalogue's fields */))
    return deal
}
```

The event is saved in the same transaction as your change, and the relay sends it to Kafka. Only one instance sends at a time, always in order, and each key gets the next `sequence` number. If Kafka is down, events wait in the outbox and go out in order when it returns.

### Consuming

```kotlin
@KafkaListener(id = "my-deals-listener", idIsGroup = false, topics = [Topics.DEALS_V1])
fun on(record: ConsumerRecord<String, Envelope<ContractEvent>?>) {
    val envelope = record.value() ?: return   // a tombstone, or a type this version does not know
    inbox.once("my-deals-listener", envelope) {
        when (val e = envelope.payload) {
            is DealTipped -> { /* your effect */ }
            else -> {}
        }
    }
}
```

- **Your consumer group** is your service name, so each event reaches one of your two instances.
- **Always write `idIsGroup = false` next to `id`.** Without it Spring Kafka uses the listener's `id` as its consumer group, so the listener would read every event again, on both instances. The service checks this at start-up and stops with `Kafka listener '...' has consumer group '...', not SERVICE_NAME` if a listener is on any other group.
- **A repeated event changes nothing.** Still give every effect a business key too, such as a unique constraint.
- **A failure is retried** five times (1, 2, 4, 8 and 16 seconds apart), then moved to `<service>.dlt` with the reason. A message that cannot be read at all goes there at once, and your consumer carries on.
- **A consumer every instance must run in full**, such as live counts, is a broadcast consumer: see `example/BroadcastExample.kt`. It uses `containerFactory = "broadcastListenerFactory"` and a group per instance, `<service>-live-<instance>` (for example `experience-live-1` and `experience-live-2`, from the containers `experience-1` and `experience-2`), the same at every restart. Only listeners from that factory may have a group other than your service name.

### Timers

```kotlin
timers.schedule("restaurant-answer", dealId.toString(), tippedAt.plus(Duration.ofMinutes(15)))

@Component
class RestaurantAnswerTimer : TimerHandler {
    override val type = "restaurant-answer"
    override fun fire(timer: DueTimer) { /* runs once, in a transaction */ }
}
```

A timer is a database row. It survives restarts and fires once across both instances. Scheduling the same type and key again moves it; `timers.cancel(type, key)` cancels it.

In tests, never wait 15 minutes: move the clock with `TestClock.advance(Duration.ofMinutes(15))`, then wait a moment with Awaitility, because timers are checked every 500 ms:

```kotlin
TestClock.advance(Duration.ofMinutes(15))
await atMost Duration.ofSeconds(5) untilAsserted { assertEquals(1, timesMyHandlerRan()) }   // import org.awaitility.kotlin.*
```

## Run it

### Two instances in Docker (as in integration)

`./check` does this for you. By hand:

```bash
./gradlew bootJar && docker compose up -d --build
```

The instances are `<service>-1` on port 8081 and `<service>-2` on port 8082, on the dev kit's network `hackathon`. The gateway reaches both at `http://<service>:8080`. Stop them with `docker compose down`.

### A released image instead of your build

To run exactly the image CI built and pushed for a Git tag (or a commit, `sha-<first 7 characters>`), for example before you ask for promotion, set `IMAGE` and skip the build:

```bash
IMAGE=cs4297.skynet.ie/hackathon-2026/line-a-orders:v1.0.0 docker compose up -d --no-build
```

Use your own repository's name (`line-a-<service>` or `line-b-<service>`) and tag. It needs `docker login cs4297.skynet.ie` (the dev kit README, Install). Without `IMAGE`, `docker compose` uses your local build, `<service>:local`.

CI builds team images for amd64 only (the integration servers' architecture). On an Apple-silicon Mac, Docker runs them under emulation and warns `The requested image's platform (linux/amd64) does not match the detected host platform (linux/arm64/v8)`. That is expected: it works, only slower to start. Your local build (`./check`) is native.

### In IntelliJ, with the dev kit in Docker

Run `ServiceApplication` with the environment variables from `local.env` (the EnvFile plugin loads the file, or paste the lines into "Environment variables"). Or run:

```bash
scripts/run-local.sh
```

A second instance runs in another terminal:

```bash
scripts/run-local.sh 8082
```

Three things to know when your service runs in the IDE and the dev kit in Docker:
- **The dev kit's containers reach your service at `host.docker.internal:8081`, not `localhost`.** Point the gateway at that address (dev kit README) to put your service in place of its fake. On Linux the gateway cannot use that name: use `172.17.0.1:8081`.
- **Two instances need two ports:** 8081 and 8082, set with `SERVER_PORT`.
- **`Port 8081 was already in use`** means a previous run is still alive (IntelliJ's stop button, or `docker compose down`). It is not a Docker problem.

### Settings

Every setting is an environment variable. A missing required one stops the service at startup and names itself: `Could not resolve placeholder 'DB_URL'`.

| Variable | What | In Docker (`service.env`) |
|---|---|---|
| `SERVICE_NAME` | Your service: deals, orders, ledger, payments, restaurant-ops, support, experience or platform | already set |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka | `kafka:29092` (from your laptop, `localhost:9092`) |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Your own schema and user | `jdbc:mysql://mysql:3306/<service>` |
| `JWT_PUBLIC_KEY_URL` | The dev sign-in's public key | `http://signin:8080/.well-known/jwks.json` |
| `CLOCK_URL` | The platform clock | `http://clock:8080/internal/clock` |
| `MAIL_HOST`, `MAIL_PORT` | Mailpit | `mailpit`, `1025` |
| `LEDGER_URL`, `HOLD_CLIENT` | Orders only: `LEDGER_URL` is the Ledger's address. When it is set, the hold call goes there over HTTP; when it is empty, the in-process stub answers (`HOLD_STUB_MODE`: placed, insufficient, slow or down), and Orders logs a warning at start-up. `HOLD_CLIENT=http` or `HOLD_CLIENT=stub` forces either | Orders: `http` and `http://fakes:8080`, the fake Ledger, so orders reach Paid (in IntelliJ, `local.env`: `http://localhost:8091`). `http://hold-stub:8080` (IntelliJ: `http://localhost:8090`) is the dev kit's hold stub, for testing each answer; with it orders never reach Paid. A real Ledger: `http://ledger:8080`. Other services: `stub`, unused |
| `SERVER_PORT` | The port | 8080 (8081 and 8082 on a laptop) |

## Tests

```bash
./gradlew test
```

Unit and contract tests: fast, no Docker, and what CI runs.

```bash
./gradlew containerTest
```

Tests tagged `@Tag("containers")`, run against real MySQL and Kafka in Docker.

- **Unit tests:** JUnit 5 with MockK, for rules and state machines.
- **Contract tests** (`ContractTest.kt`): add one per event you publish, with `assertMatchesContract(...)`. A broken event fails CI before it reaches the line.
- **Container tests:** extend `ContainersTest`, as `src/test/.../platform/ConsumerPatternsTest.kt` does. Use Awaitility for anything asynchronous (`await atMost Duration.ofSeconds(10) untilAsserted { ... }`); never `Thread.sleep`. In every consumer test, deliver the same event twice and check that nothing changes.
- **Two instances in one test:** `TwoInstancesTest.kt` shows how.

### Test scripts (your team section says which are yours)

| Script | For | Run |
|---|---|---|
| k6 last-place test: 200 students, cap 50, both instances | Orders | Make the tokens in the dev kit (`./token --students 200 > tokens.txt`), then here `scripts/last-place.sh <dealId> ../dev-kit/tokens.txt` (the dev kit cloned next to this repository; any relative or absolute path works), then count the orders in the database |
| Zero-sum property test | Ledger | `src/test/.../templates/ZeroSumPropertyTest.kt`: implement it, remove `@Disabled` |
| 2,000 live-update connections | Student experience | `./gradlew liveConnections -Purl=http://localhost:8080/api/experience/deals/<id>/live` |

k6 runs in Docker, so there is nothing to install.

## CI

`.forgejo/workflows/ci.yml` runs on every push. It does the unit and contract tests, builds the jar, then builds and pushes your image to the Forgejo registry tagged `sha-<commit>`, plus the tag itself for a Git tag. Promotion into integration uses your Git tag.

It needs the repository secret `PACKAGES_TOKEN`, which the organisers set. Gradle's dependencies are cached, but only two runners serve sixteen teams: do not add container tests to CI.

## When something goes wrong

| You see | It means | Fix |
|---|---|---|
| `Could not resolve ie.ul.cs4297.groupbuy:contracts:1.1.0` ... `401` | Gradle has no token for the package registry | Step 1 of "Your first hour" |
| `Could not find ie.ul.cs4297.groupbuy:contracts:1.1.0` | The registry answered, but has no 1.1.0 for you: the organisers have not published that version yet, or your Forgejo account has no access to the `hackathon-2026` organisation's packages | Check https://cs4297.skynet.ie/hackathon-2026/-/packages for `contracts` 1.1.0. If it is missing, tell the organisers; if it is there, ask them to add you to the organisation (and check your token has `read:package`) |
| `The requested image's platform (linux/amd64) does not match the detected host platform (linux/arm64/v8)` with `IMAGE=... --no-build` on an Apple-silicon Mac | CI builds team images for amd64 only; Docker runs them under emulation | Expected: it works, only slower. Use `./check` (a native local build) for everyday work |
| `Could not resolve placeholder 'DB_URL'` (or another name) | A setting is missing | Set it: `service.env` in Docker, `local.env` in the IDE |
| `SERVICE_NAME is 'x'; it must be one of ...` | A typo in the service name | Use one of the eight names exactly |
| `CLOCK_URL is not set. Every service must read the platform clock: ...` and the service stops at start-up | No `CLOCK_URL`: the service would use your laptop's clock, and its cut-offs would disagree with everyone else's | Set it: `CLOCK_URL=http://clock:8080/internal/clock` in `service.env`, `http://localhost:8090/internal/clock` in `local.env` (both files already have it; in IntelliJ, check the run configuration loads `local.env`) |
| `./gradlew: Permission denied` | Git on Windows dropped the execute bit | `git update-index --chmod=+x gradlew check scripts/*.sh`, then commit |
| `the network 'hackathon' does not exist`, or `network hackathon declared as external, but could not be found` | The dev kit is not running: it creates the network | Start the dev kit first (`docker compose --profile lite up -d` in its folder), then run this again |
| `IMAGE=...` with `--no-build` answers `manifest unknown` or `not found` | That tag was never pushed, or the repository name is wrong | Check the tag in your repository's Packages page on Forgejo; CI prints `Pushed:` with the exact names |
| `Could not find a valid Docker environment` in container tests | Docker is not running, or Testcontainers is older than 1.21.4 (Docker 29 refuses it) | Start Docker Desktop; keep `testcontainers.version` as it is in `build.gradle.kts` |
| `Port 8081 was already in use` | A previous run is still alive | Stop it: IntelliJ's stop button, or `docker compose down` |
| A 401 with `"code": "UNAUTHENTICATED"` | No token, or it expired after 24 hours | Sign in again on the dev sign-in page |
| A 401 with `"code": "UNAUTHENTICATED"` for a fresh token, when you started the service in IntelliJ before the dev kit | The service fetches the sign-in's public key (`JWT_PUBLIC_KEY_URL`, http://localhost:8080/.well-known/jwks.json) at the first signed call; the dev kit was not up, so the fetch failed. The log shows nothing at INFO (Spring Security logs `Couldn't retrieve remote JWK set` only at TRACE) | Start the dev kit, check that URL opens in your browser, then call again: a failed fetch is retried on the next call. If it still fails, restart the service |
| `NOTHING_ANSWERS` from the gateway on **Linux** with `<SERVICE>_URL=http://host.docker.internal:8081` | The gateway cannot look up `host.docker.internal` on Linux | Use `<SERVICE>_URL=http://172.17.0.1:8081` and let Docker reach the port through your firewall (for example `sudo ufw allow from 172.16.0.0/12 to any port 8081`), or run your service as two containers (`./check`) and use `http://<service>:8080`. See the dev kit README, "Networking" |
| A 500 where you expected 401 | A controller reads the user some other way | Take `user: CurrentUser` as a parameter, and never read the token by hand |
| A redirect to a login page | Someone replaced the security setup | Keep `platform/Security.kt`: it answers 401, never 302 |
| Your data vanished after a redeploy | `ddl-auto` was changed to `create-drop` | Keep `validate`; change tables only through Flyway migrations |
| `Schema-validation: missing table [x]` | An entity has no migration | Add `V<n>__x.sql` |
| `Outbox.publish must run inside @Transactional` | You published outside a transaction | Publish in the same `@Transactional` method as the change |
| A consumer seems stuck on one event | Its effect keeps failing; it is being retried | Read the log: after about 30 seconds it goes to `<service>.dlt` and the consumer moves on |
| Events from your service go to the wrong place, or are split with another service | A hard-coded consumer group | Never set `groupId` on a normal listener: the group is `SERVICE_NAME` |
| The service stops at start-up with `Kafka listener 'my-listener' has consumer group 'my-listener', not SERVICE_NAME` | `@KafkaListener(id = "my-listener", ...)` without `idIsGroup = false`: Spring Kafka made the id the group | Add `idIsGroup = false`, as the message says. A consumer every instance must run uses `containerFactory = "broadcastListenerFactory"` (`example/BroadcastExample.kt`) |
| `Received status code 429` from Maven Central | Too many downloads at once on the venue network | Wait a minute and rebuild; Docker builds never download (they copy the jar) |
| An instance restarts with `OutOfMemoryError` | It needed more than 512 MB | Look for something kept in memory without limit; do not raise the limit (integration holds sixteen instances) |
