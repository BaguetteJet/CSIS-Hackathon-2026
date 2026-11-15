# Integration check

Run before we promote an image: the CI image, on an **x86_64 (amd64)** machine, through the rush, big deals and the books. About 25 minutes. Targets and earlier results: [INTERFACE-TARGETS.md](INTERFACE-TARGETS.md).

**Why x86_64:** CI builds `linux/amd64` only, the same as integration. On an arm64 Mac, Docker runs that image under emulation, which uses about 509 MiB of the 512 MB at rest, and the instances are killed (exit 137). A native arm64 build of the same code sits at about 340 MiB ([evidence](evidence/deploy-rehearsal-2026-11-15.md)). On a Mac, use `./check` in step 2 instead of the CI image, and say so in your report.

## You need

- Docker with 8 GB or more, `python3`, and the dev kit.
- The commit to check: `main`'s head, or the tag about to be promoted. Its CI run must be green, so the image `cs4297.skynet.ie/hackathon-2026/line-a-ledger:sha-<first 7 of the commit>` exists.
- Nobody else on the dev kit: say in the team chat that you're starting, and when you're done.

## Steps

1. **Machine.** `uname -m` says `x86_64`. Write down `git -C line-a-ledger rev-parse --short HEAD`.
2. **Deploy.** [HANDOVER §4](HANDOVER.md#4-deploy), steps 1 to 4, with `IMAGE=cs4297.skynet.ie/hackathon-2026/line-a-ledger:sha-<commit>` in step 3 instead of `:release`. Then [§5](HANDOVER.md#5-verify), every step.
3. **Fund the students and get tokens,** in `line-a-ledger/`:
   ```bash
   (cd ../dev-kit && ./sim money-in --students 204)
   python3 -I scripts/drills/students.py ../dev-kit /tmp/ledger-check
   ```
4. **The rush:** 100 holds/s over 10 deals, page and desk calls, top-ups, a 200-order settlement mid-rush, 1,600 releases, 300 refunds. About 2 minutes.
   ```bash
   python3 -I scripts/drills/load_checks.py ../dev-kit . /tmp/ledger-check | tee /tmp/ledger-check/rush.log
   ```
5. **Big deals:** 500 and 2,000 orders settle while holds arrive at 100/s for the same students. About 3 minutes.
   ```bash
   python3 -I scripts/drills/big_deal.py 500 2000 | tee /tmp/ledger-check/big.log
   ```
6. **Instances,** after all of it:
   ```bash
   docker inspect -f '{{.Name}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' ledger-1 ledger-2
   docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' ledger-1 ledger-2
   ```

## Pass

| Check | Pass | Last result (arm64, 15 Nov) |
|---|---|---|
| §5 Verify | Every step as written | Passed on `sha-7816b08` ([rehearsal](evidence/deploy-rehearsal-2026-11-15.md)) |
| Step 4 | Last line `all checks passed` | Passed on `e27571f`, before #64–#68: holds max 289 ms, pages p95 under 50 ms ([evidence](evidence/interface-load-2026-11-15.md)). Not yet on today's `main` |
| Step 5, 500 orders | `charged 500`; rush answers all `(200, 'PLACED')`; `books sum 0; negative 0` | Settled in 1.0 s; slowest hold 0.28 s ([evidence](evidence/big-deal-2026-11-15.md)) |
| Step 5, 2,000 orders | `charged 2000`; every rush hold `PLACED` or `503` and every retry `PLACED`; books 0 | Settled in 6.0 s; 210 holds 503, all placed on retry; 7 took 2.0–2.1 s ([evidence](evidence/big-deal-2026-11-15.md), [known limitation](HANDOVER.md#10-known-limitations)) |
| Step 6 | `restarts=0 oom=false` for both; each under 512 MiB | 0 restarts; 419 and 402 MiB |

A fail is a 500, a hold over 2 s at 500 orders or fewer, a wrong `charged` count, books not 0, a negative balance, or a restart.

## Report

Post in the team chat: the machine (`uname -m`), the commit, each row above as pass or fail, and the logs in `/tmp/ledger-check/` if anything failed. A pass on x86_64 is the go to promote that commit (PHASES, "What's left" row 1).
