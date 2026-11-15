# Deploy-gate rehearsal, 15 Nov 2026

Sun 15 Nov, 08:37–08:39 UTC. HANDOVER §4 and §5 from `main` `4dcc5a5`, run as written on the laptop's dev kit (`cf62874` content) and fake line, image `sha-7816b08` (the same code; `release` not tagged yet). Before the run: the old instances stopped and the dev kit's `ledger` database dropped and created empty, so Flyway built it from nothing.

| Step | Measured | Result |
|---|---|---|
| §4.1 clone, `.env` | 2 s | Pass |
| §4.2 dev kit with our dashboard and alerts | 10 s (already running; `--force-recreate grafana`) | Pass |
| §4.3–4.4 both instances UP | 47 s after `compose up` | Pass |
| §5.1 `/hello` | `"service":"ledger"`, `"instance":"ledger-2"` | Pass |
| §5.2–5.3 money-in, `/me` | 0 at once, 2000 four seconds later | Pass, slow: step 3 now says to wait |
| §5.4 one hold, three calls, two instances | INSUFFICIENT_FUNDS three times (run 1 s after step 3, before the top-up landed) | Not shown; see above |
| §5.5 short of money, bad input | INSUFFICIENT_FUNDS with availableCents 2000; 422 VALIDATION_FAILED | Pass |
| §5.6 golden path | 12 CONFIRMED, heldCents 600; settlement 12 / 6000 / 600, matching the charges | Pass |
| §5.7 hold after close | DEAL_CLOSED | Pass |
| §5.8 books | 0, 0, 0 | Pass |
| Instances after the run | 0 restarts | Pass |

From opening §4 to the end of §5: 86 s of commands.

## Differences from the reference machine

| Here | Reference machine | Checked |
|---|---|---|
| macOS: `sed -i ''`, `uuidgen` | Ubuntu 24.04: `sed -i`, `/proc/sys/kernel/random/uuid` | Both Linux forms run on `ubuntu:24.04`: the `sed` sets the two lines, `/proc` gives a UUID |
| Dev kit already running | Dev kit cloned, images pulled, not started | §4.2 starts it; about a minute more |
| `git clone` used a saved Forgejo login | Logged in to the container registry; nothing said about Git | **Open:** the repository is private. Ask the assessor whether the reference machine can clone it |
