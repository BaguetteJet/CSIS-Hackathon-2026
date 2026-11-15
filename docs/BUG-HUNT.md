# Bug hunt

From Sunday 18:00, AI test agents use the frozen line as students, Union finance and restaurant staff while faults are injected. Each confirmed bug costs 5 marks (critical), 2 (major) or 0.5 (minor), at most 25 (project handover §20.5); a bug counts against the service that caused it. They judge against the assessment spec: the reference's rules E1 to E12 and our team document's LED stories, not our own docs. This page is how we find their bugs first. Findings and fixes live in [EDGE-CASES "Cases that cause issues"](EDGE-CASES.md#cases-that-cause-issues).

## What they judge by

| They do | Judged by | Ours |
|---|---|---|
| Bad IDs in a path: not a UUID, `..;`, encoded `/`, 10 KB | E1: 404 NOT_FOUND, never 400 | Desk balance |
| No token, wrong role, then a bad ID or body | E2: 401, 403, 404, 422, in that order | Every page call |
| Bad bodies: invalid JSON, wrong type, null, no Content-Type, over 64 KB | E3, E5: 422 with `errors[]`; 413 | Hold call |
| Zero amounts | E7: 422 on a call; dead-letter at once on an event | Hold call, all money events |
| MySQL stopped or frozen, Kafka paused | E10: 503 within 10 s, normal within 60 s of the return; E11: no valid event dead-lettered within 2 min | Everything |
| Double clicks, retries, events twice, instance killed | LED-04, LED-06, LED-21: once, same answer | Holds, settlement, every consumer |
| 100 joins a second while a cap-sized deal settles | LED-03, LED-22: every hold answer within 2 s | Hold call |
| Compare the Account page and statement with what they did | LED-07, LED-11: live balance, newest first, nets to available, "set aside" | `/me`, `/me/entries` |
| The platform clock moved | DEC X1: the Ledger never refuses for time | Holds, statement |

## Before the freeze

Run on the commit to be tagged `release`, with both instances from `./check` (not the CI image on an arm64 laptop: [INTEGRATION-CHECK.md](INTEGRATION-CHECK.md)).

- [ ] `./check --with-containers` says Ready
- [ ] `python3 -I scripts/drills/agent_probes.py <dev-kit dir>`: all passed
- [ ] LED-21 drills: MySQL stopped and paused, Kafka paused, an instance killed mid-settlement ([HANDOVER](HANDOVER.md))
- [ ] `python3 -I scripts/drills/big_deal.py 2000`: every hold answered within 2 s
- [ ] `python3 -I scripts/drills/load_checks.py`: the rush, cancellations, a kitchen failure
- [ ] HANDOVER §8 SQL: the books sum to zero, nothing stuck
- [ ] Every open EDGE-CASES Q row is fixed, or written in HANDOVER §10 as a known limitation

## Found on 15 November

A code read of `src/main` against the spec, then fixes. Status here; what and why in each EDGE-CASES row.

| Row | Spec it broke | Status |
|---|---|---|
| [Q24](EDGE-CASES.md#cases-that-cause-issues): a listed order that can't be charged had its hold released | LED-10 | Fixed |
| Q25: a hold answer could pass 2 s, or 10 s with MySQL frozen | LED-03, E10 | Fixed |
| Q26: the statement sorted by the platform clock | LED-11 | Fixed |
| Q27: injected or 10 KB desk IDs answered 400 | E1, E2 | Fixed |
| Q28: refunds woken by settlement lock a student out of order | Decision 0004 (ours) | Known limitation |
| Q29: starter examples still served (`/api/ledger/hello`, `/app/ledger/`) | None | Known limitation |

## Where the next ones hide

Patterns from this hunt. Check each against any change before it merges.

| Pattern | Seen here | Check |
|---|---|---|
| Time limits add up across layers | Pool wait 1 s + transaction 1 s + a retry passed Orders' 2 s (Q25) | Add every wait on the path; the sum is under the caller's limit |
| A fix that is sensible but not what the spec says | Q1's fix released holds LED-10 says to keep (Q24) | Quote the spec line in the PR; if it disagrees, ask the product channel first |
| Something answers before our code | Spring's firewall and Tomcat's header limit gave 400 (Q27) | Probe with hostile paths through the gateway, not only with unit tests |
| Time from the platform clock moves | The statement sorted by `posted_at` (Q26) | Never order or decide by platform time where posting order is meant |
| Another team sends what the contract allows but we never saw | An unknown enum value dead-letters (Q17) | Read the schema's full range, not the sample |
| Docs drift from the code | EDGE X2 said nothing stops a negative balance | Update the row in the same PR as the behaviour |
| A dev-kit result that isn't the integration machine | OOM kills were x86 emulation ([evidence](evidence/deploy-rehearsal-2026-11-15.md)) | Say which machine and image every number came from |

## Filing one

1. Add a row to EDGE-CASES "Cases that cause issues" (next Q number): what happens, evidence, whose code, fix.
2. Fix it in the owner's area, with a test that fails first, and update the row's Fix in the same PR.
3. After the freeze, no code: write it in HANDOVER §10 as a known limitation instead.
