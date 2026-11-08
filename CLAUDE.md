# Repository `starter`: the service template

Read the workspace `CLAUDE.md` (two folders up) first. Spec paths are relative to the workspace root.

## Purpose
The project every team starts from. On Thursday it is copied into all 16 team repositories with `SERVICE_NAME` set. **It is plumbing, not answers**: everything a service needs to be safe and observable, and none of any service's business logic.

## Read first
- `spec/TASKS.md`, item 2 (the full list) and item 2b's UI-kit line.
- `spec/B-architecture.md`: "Shared conventions", "Technical conventions", "Timers", "The golden path".
- `spec/C1-overview.md`: "Getting started and testing" (what students are told the starter contains).
- The Week 8 lab blueprint `2026/CS4297-week-08` on the same Forgejo: start from its Gradle build, Dockerfile and `ci.yml`, and remove the LinkLab code.

## Build
Everything in `spec/TASKS.md` item 2. Specifically:
- Spring Boot, Kotlin, MySQL, Flyway (`ddl-auto=validate`), the `contracts` library as a dependency.
- **Outbox** with a single active publisher (lease row), in outbox order; per-key `sequence` from a counter row in the same transaction.
- **Idempotent consumer** helper (processed `eventId` table plus business keys); **parking table** for early events; `ErrorHandlingDeserializer` and a dead-letter topic; consumer group from `SERVICE_NAME`; a **broadcast-consumer** example (a group per instance).
- **Timer helper:** a DB table claimed with `SELECT ... FOR UPDATE SKIP LOCKED`; a timer fires once across two instances; an injected `java.time.Clock` set from the platform clock.
- **Hold client** (used by Orders): HTTP and stub, switched by a property; 2-second timeout; idempotent by order ID.
- Problem Details errors with a `code`; `Idempotency-Key` helper; JWT resource server from `JWT_PUBLIC_KEY_URL`; access helpers (own resources only, 404 otherwise; `restaurantId` for restaurant staff; role checks).
- `/actuator/health`, `/actuator/prometheus`, port 8080, settings only from environment variables.
- **Page template:** plain HTML forms and `api.js` attaching the dev-sign-in token; the **UI kit** stylesheet, header and navigation. Take its colours, type and components from `reference/mockups/` and `reference/prototype.html` (Inter; dark green `#0d3b2e` header; accent `#187a47`). The restaurant and Union mockups (D1, D2, R1 to R3) show what the plain page template should be able to produce.
- Dockerfile capping each instance at 512 MB (container limit and JVM heap flags); Compose snippet running **two instances** on the `hackathon` network.
- Testcontainers base test and Awaitility.
- Forgejo Actions: build, unit tests, contract tests, and the image pushed to the Forgejo registry tagged with the commit and any Git tag. Cache Gradle dependencies.
- **Test scripts:** k6 last-place test (cap 50, 200 parallel commits, two instances); zero-sum property-test template (for the Ledger); 2,000-connection server-sent-events script (for Student experience).
- **`make check`** (works in Git Bash): unit tests, contract tests, then both instances started against the dev kit's fake line, and their health checked. One command that tells a team whether it is ready to ask for promotion.
- A one-endpoint example (`GET /api/<service>/hello`) and one example consumer of `users.v1` storing users, to show the patterns. Nothing more.
- README for students: what is here, how to run one and two instances (ports 8081 and 8082 via `SERVER_PORT`), `host.docker.internal`, and the trap list from the workspace `CLAUDE.md`.

## Done when
- Two instances run locally within 512 MB each against the dev kit.
- A timer created on one instance fires exactly once when that instance is killed.
- An event published twice is processed once; an event arriving before its prerequisite is parked, then applied.
- A malformed message lands on the dead-letter topic and the consumer keeps going.
- With both instances publishing, one key's events arrive in order.
- CI passes on Forgejo and pushes an image; `make check` passes from a clean clone on Windows (Git Bash) and macOS.

## Out of scope
Any service's domain model, rules, state machine or pages.

## When you finish a session

Update `STATUS.md` (done, how it was verified, what is open) and `OPEN-QUESTIONS.md` (anything the spec did not settle). Never mark an item done without running its check.
