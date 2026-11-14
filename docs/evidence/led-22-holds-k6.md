# LED-22: holds at 100/s

Sat 14 Nov, dev kit, both replicas from `./check`. Students funded with `./sim money-in --students 204`.

```bash
docker run --rm --network hackathon -v "$PWD/scripts/k6:/scripts" -v <dir with students.json>:/data grafana/k6:0.57.0 run /scripts/holds.js
```

| Run | Requests | Checks | Max | p95 |
|---|---|---|---|---|
| Alone | 2,000 at 100/s | 100% | 337 ms | 45 ms |
| During a 202-order settlement on the same students | 1,992 at 100/s | 100% | 666 ms | 154 ms |

Settlement took 786 ms (`ledger_settlement_seconds_max`): 202 charged at 95 cents, 2,020 commission (half up). After both runs: no negative balance, every held balance equals the student's open holds, nothing unsent, postings sum to 0. Memory peaked at 379 of 512 MiB.
