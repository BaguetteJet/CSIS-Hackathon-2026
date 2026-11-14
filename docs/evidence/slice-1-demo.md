# Slice 1 demo evidence (item 4)

Owner: Shakeeba · Backup: Hersh



## Bench setup (done Fri 13 Nov)

- Dev kit running with the lite profile

- Ledger built with bootJar, two replicas started

- Health: 8081 UP, 8082 UP

- dev-kit .env: LEDGER_URL=http://ledger:8080, FAKE_EXCLUDE=ledger

- Gateway check: /api/ledger/hello answered from ledger-2 as Aoife Murphy



## Commands used

(paste the commands that worked, in order)



## Windows notes

- ./sim must be run through Git Bash

- ./sim money-in prints nothing and exits 1, but publishes (HTTP 200)



## Evidence still to capture

- [ ] One TopUpCredited per top-up after money-in --repeat

- [ ] Funded hold: PLACED + HoldPlaced event

- [ ] Refusal: INSUFFICIENT_FUNDS

- [ ] Replay on the other replica: same answer, no new event
