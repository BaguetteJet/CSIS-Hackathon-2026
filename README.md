# Your service

This repository is your team's service, already set up with the plumbing every service needs: Kotlin 2.1.0 and Spring Boot 3.4.3 (the Week 8 lab's versions, JDK 21), MySQL with Flyway, Kafka with an outbox, sign-in, errors, metrics, a page template, Docker and CI. It has none of your service's logic: that is your design, and what you are assessed on. Your team's document in the spec (spec/teams/) says what to build.

## What is here

| You need | Use | Where |
|---|---|---|
| Publish an event | `outbox.publish(event)` inside your `@Transactional` method | `platform/Outbox.kt` |
| Consume an event once | `@KafkaListener(id = "...", idIsGroup = false, ...)` and `inbox.once(...) { }` | `platform/Events.kt`, `example/KnownUsers.kt` |
| An event that arrives too early | `parking.park(...)`, then `parking.release(...)` | `platform/Events.kt`, `example/KnownUsers.kt` |
| A consumer every instance runs (live counts) | `containerFactory = "broadcastListenerFactory"` | `example/BroadcastExample.kt` |
| Something to happen at a time | `timers.schedule(type, key, at)` and a `TimerHandler` bean | `platform/Timers.kt` |
| The time | Inject `java.time.Clock`; never `Instant.now()` | `platform/PlatformClock.kt` |
| The hold call (Orders) | Inject `HoldClient` | `platform/HoldClient.kt` |
| The signed-in user, own data only, roles | A `user: CurrentUser` parameter; `user.requireOwner(...)`, `requireRestaurant(...)`, `requireRole(...)` | `platform/Security.kt` |
| An error the page can act on | `throw Conflict("DEAL_FULL", "...")`, `NotFound(...)`, `Invalid(...)` | `platform/Problems.kt` |
| `Idempotency-Key` | `idempotent.handle(key, caller, body) { ... }` | `platform/Idempotency.kt` |
| A table | A Flyway migration `V3__your_table.sql` | `src/main/resources/db/migration` |
| A page | Copy `index.html`; use `api.js` and the UI kit's classes | `src/main/resources/pages/` |
| Event classes, topics, Money, states | The contracts library | its README |
| Tests with real MySQL and Kafka | Extend `ContainersTest`; `TestTokens`, `TestClock` | `src/test/.../support/` |
| Your team's load or property test | `scripts/last-place.sh`, `src/test/kotlin/.../templates/ZeroSumPropertyTest.kt`, `./gradlew liveConnections` | your team section says which |

Leave the `platform` package as it is, except `PUBLIC_PATHS` in `platform/Security.kt` (the paths that need no token), which is yours to fill in. If something in it gets in your way, tell the Platform and reliability team or the organisers.

## Before you start

Start the dev kit and make its Forgejo token (dev kit README, "Install"): https://cs4297.skynet.ie/hackathon-2026/dev-kit. Put the token in `~/.gradle/gradle.properties` (on Windows, `C:\Users\<you>\.gradle\gradle.properties`), never in this repository:

```properties
forgejoUser=your-forgejo-username
forgejoToken=the-token
```

Personal or secret values go in a file ending `.local.env`, which Git ignores. A secret committed at any point in the history multiplies your project mark by 0.75 (handover, Part E, "Penalties"); the `DB_PASSWORD` in `local.env` and `service.env` is the dev kit's local value and does not count.

To put your service in place of its fake behind the gateway, and to sign in, see the dev kit README, "The fake line, and using your own service" and "Signing in".

## Commands

Tests, image, two instances against the dev kit (512 MB each), health checked. When it prints `Ready`, push:

```bash
./check
```

On macOS and Linux, `make check` does the same.

Unit tests and contract tests only (no Docker):

```bash
./gradlew test
```

Tests against real MySQL and Kafka:

```bash
./gradlew containerTest
```

In IntelliJ, run `ServiceApplication` with the variables from `local.env` (EnvFile plugin, or paste them into "Environment variables"). Or from a terminal:

```bash
scripts/run-local.sh
```

A second instance, in another terminal:

```bash
scripts/run-local.sh 8082
```

The dev kit's containers reach a service in the IDE at `host.docker.internal:8081`, not `localhost` (on Linux, `172.17.0.1:8081`).

Two instances in Docker without the checks (`<service>-1` on port 8081, `<service>-2` on 8082; stop with `docker compose down`):

```bash
./gradlew bootJar && docker compose up -d --build
```

An image CI built and pushed, for a Git tag or `sha-<first 7 characters of the commit>`:

```bash
IMAGE=cs4297.skynet.ie/hackathon-2026/line-a-orders:v1.0.0 docker compose up -d --no-build
```

Use your own repository's name and tag; it needs `docker login cs4297.skynet.ie`. This is how the deploy gate runs your service: the reference machine has Git and Docker but no Java, so your handover document's deploy step must be this command with your `release` tag, never `./gradlew` or `./check`. On an Apple-silicon Mac, Docker warns that the image is `linux/amd64`: that is expected, it runs under emulation.

## CI

Every push to `main`, every Git tag and every pull request runs "Unit tests", then "Contract tests" as its own step (the Platform team reads it), then builds and pushes your image tagged `sha-<commit>`, and also with the tag name for a Git tag. Promotion into integration uses your Git tag.

## Large files: Git LFS

A binary file over 1 MB (an image, a video, a PDF, an archive) goes through Git LFS; committed directly, it multiplies your project mark by 0.75 (handover, Part E, "Penalties"). Git for Windows includes Git LFS; on macOS run `brew install git-lfs`, on Ubuntu `sudo apt install git-lfs`. Then, before you add the file:

```bash
git lfs install
git lfs track "*.png" "*.jpg" "*.mp4" "*.pdf" "*.zip"
git add .gitattributes
```

CI fetches the LFS files before it builds your image.

## Three mistakes the go/no-go rehearsal hit

- Require `Idempotency-Key` only where your OpenAPI file says it is required (the Payments desk calls), not on every call.
- Just after the clock moves, take the time from the injected `Clock`, and allow up to 5 seconds for every service to follow it.
- A page call can arrive before your own consumer has seen the event that creates what it asks for. Answer 404 and let the page retry, as the journey test does.

## When something goes wrong

| You see | What to do |
|---|---|
| `./gradlew: Permission denied` | Git on Windows dropped the execute bit: `git update-index --chmod=+x gradlew check scripts/*.sh`, then commit |
| `Could not resolve ie.ul.cs4297.groupbuy:contracts:1.3.0` ... `401` | Gradle has no packages token: see "Before you start", and check the token has `read:package` |
| `Received status code 429` from Maven Central | Too many downloads at once on the venue network: wait a minute and build again |
| A 500 where you expected 401 | A controller reads the user some other way: take `user: CurrentUser` as a parameter, never a non-null token you read by hand |
| A redirect (302) to a login page | The security setup was replaced: keep `platform/Security.kt`, which answers 401 |
| `Kafka listener '...' has consumer group '...', not SERVICE_NAME` at start-up | Add `idIsGroup = false` next to `id`, as the message says |
| `Could not resolve placeholder 'DB_URL'` (or another name) | A setting is missing: `service.env` in Docker, `local.env` in the IDE |
| `network hackathon declared as external, but could not be found` | Start the dev kit first: it creates the network |

For anything about the dev kit, the gateway or sign-in, see the dev kit README, "When something goes wrong". The rules your service follows are in spec B, "Shared conventions" and "Technical conventions".
