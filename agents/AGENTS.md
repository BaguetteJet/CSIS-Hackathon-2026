# AGENTS.md

The Ledger service for Campus Group-Buy, line A. Read before changing anything:

| File | For |
|---|---|
| [PHASES.md](../PHASES.md) | What's done and what's left |
| [CHECKLIST.md](../CHECKLIST.md) | The brief's "Done when" for each LED, and what's left before the final |
| [OWNERS.md](../OWNERS.md), [work-items/](../work-items/README.md) | Who owns what; your item's steps |
| [docs/NEIGHBOURS.md](../docs/NEIGHBOURS.md) | What we own and don't; what each team sends us and reads from us |
| [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md), [docs/decisions/](../docs/decisions/README.md) | The design and why |
| [docs/SCHEMA.md](../docs/SCHEMA.md) | Every table and key, and how they fit the contracts |
| [docs/HANDOVER.md](../docs/HANDOVER.md) | Deploy, runbooks, SQL checks, known limitations |
| [GLOSSARY.md](../GLOSSARY.md) | The words, including the ones students see |
| [docs/EDGE-CASES.md](../docs/EDGE-CASES.md) | What the Ledger does in every case; check before a PR |

Every story is an LED item from the team spec (Ledger §5, LED-01 to LED-24). Issue tracker: Forgejo issues on `hackathon-2026/line-a-ledger`, one per LED item, assigned to its owner; the steps live in `work-items/`.

## Commands

Use JDK 21 (Gradle 8.12 fails on 24). On macOS: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

| Command | Runs |
|---|---|
| `./gradlew test` | Unit tests |
| `./gradlew contractTest` | Contract tests (CI runs these) |
| `./gradlew containerTest` | Real MySQL and Kafka, two instances (needs Docker; CI does **not** run these) |
| `./check` | Tests, image, two instances healthy within 512 MB. Run before every PR |

Dev kit: in `dev-kit/.env` set `LEDGER_URL=http://ledger:8080` and `FAKE_EXCLUDE=ledger`, then `docker compose --profile lite up -d`.

## Rules the design depends on

- Money moves only through `LedgerPostingService.post`.
- A balance used for a decision or put on an event comes from `lockStudent` / `lockStudents`.
- Lock order: business rows, then students (ascending id), then publish (decision 0004).
- Every effect has a business key; consume events inside `inbox.once`.
- One Kafka listener per topic: add a branch to the existing one (decision 0006).
- Time only from the injected `Clock`. The Ledger never refuses for time.
- Leave `src/.../platform/` as it is.
- Migrations: take the next free `V` number when you merge; never edit an applied one, not even a comment.
- Secrets only in `~/.gradle/gradle.properties` or `*.local.env`; binaries over 1 MB through Git LFS. Either mistake costs the team ×0.75.

## Boundaries

- The Ledger owns the books only. Orders, deals, the desk, refund approval, users and alerts belong to other teams ([NEIGHBOURS §1](../docs/NEIGHBOURS.md#1-what-we-own-what-we-dont)). Don't build their logic here; take the values in their events (price, commission rate, amounts) as given.
- Event and API shapes come from the `contracts` library, 1.3.0. Never change or copy them. To read one: `unzip -p "$(find ~/.gradle/caches -name 'contracts-1.3.0-sources.jar' | head -1)" contracts/schemas/<topic>/<Event>.schema.json`. `contracts/openapi/` has the APIs; `EventCatalogue.kt` has each event's producer and key.
- A new input or output: add its row to NEIGHBOURS §2 or §3, and a contract test for each new event.

## Working

- Stay in your area's files ([work-items/README.md](../work-items/README.md)); others' only through the shared interfaces.
- Branch `item-<n>/<topic>` from `main`. Small PRs, merged with a merge commit, never squashed.
- Commit messages: one plain subject line, no body.
- Several sessions may share one checkout: work in your own `git worktree`, and never edit another session's uncommitted files.
- **Done** means:
  - all three test suites are green and `./check` says Ready;
  - the behaviour is checked live on the dev kit;
  - there's a contract test for each new event;
  - every case in [docs/EDGE-CASES.md](../docs/EDGE-CASES.md) your change touches behaves as its row says, and new cases have a row;
  - your item's checkbox and PHASES.md are updated.

## Style

- Comments: the decision and its reason, one line. Plain Kotlin: `if` and `for` over clever chains.
- Docs: short, tables, each fact in one place (below). Numbers come from a real run in `docs/evidence/`, or are marked **Not yet** or **Planned**.
- Student-facing text says "set aside" and "Account", never "hold" or "wallet".

## Docs: where each fact lives

Change the file that owns a fact in the same PR as the behaviour; elsewhere, link to it.

| Fact | Lives in |
|---|---|
| Status of each LED item | PHASES.md |
| The brief's "Done when" for each LED, with its proof | CHECKLIST.md |
| What each neighbour needs in time and load, with measured results | docs/INTERFACE-TARGETS.md |
| The check before a promotion, on x86_64 | docs/INTEGRATION-CHECK.md |
| Owner and backup | OWNERS.md |
| An item's steps, and what it dropped and why (its Out of scope table) | work-items/ |
| Ownership, inputs and outputs, event keys | docs/NEIGHBOURS.md |
| Invariants, flows, failures | docs/ARCHITECTURE.md |
| Why, and the options rejected | docs/decisions/ |
| Tables, columns, keys, contract fit, planned tables | docs/SCHEMA.md |
| Deploy, verify, runbooks, known limitations | docs/HANDOVER.md |
| Words | GLOSSARY.md |
| How the test agents judge us, the pre-freeze rehearsal, where the next bugs hide | docs/BUG-HUNT.md |
| Measured results, with date and commit | docs/evidence/: never rewritten; add a follow-up line instead |

## Skills

In `.claude/skills/`, read by both Claude Code and OpenCode. What each is for, and its source: [.claude/skills/README.md](../.claude/skills/README.md).

| Doing | Use |
|---|---|
| Building an LED story | `ledger-story` (with `test-driven-development`) |
| Proving it works | `ledger-verify` (with `verification-before-completion`) |
| Shipping a PR | `ledger-pr`; reviewing one: `code-review`; answering one: `receiving-code-review` |
| A failing or flaky test | `systematic-debugging` |
| Keeping a change small | `ponytail`, `ponytail-review` |
| Comments | `comment-refactor` |
| Design, or practising its defence | `codebase-design`, `grilling` |

## Gotchas

- `Migration checksum mismatch` at start-up: the dev kit's `ledger` database has an old schema. Drop and create it, on a dev kit only.
- The 200-order settlement test has a 3 s limit and can fail on a busy laptop. Rerun it before blaming the code.
- Other tests with a time limit that a busy laptop can fail: [evidence/flaky-tests-2026-11-14.md](../docs/evidence/flaky-tests-2026-11-14.md).
