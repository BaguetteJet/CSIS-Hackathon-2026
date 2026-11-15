# LED-20: zero-sum property test, Sat 14 Nov 2026

Branch `item-7/zero-sum` on `main` at `f4e9993`, MacBook (arm64), real MySQL and Kafka (Testcontainers). Command:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew containerTest --tests 'ie.ul.cs4297.groupbuy.service.ledger.ZeroSumPropertyTest' --rerun-tasks -i
```

**Result:** pass, 40 s.

```
ZeroSumPropertyTest: 200 runs, 5050 steps: {cash-out end: nothing reserved=306, cash-out paid out=147, cash-out request=631, cash-out returned=156, hold: INSUFFICIENT_FUNDS=20, hold: PLACED=640, payout=638, refund=157, refund: nothing charged=505, release=224, release: nothing open=405, settle orders at, bp=592, top-up=629}
```

Each run gives 3 new students €50, then takes 10 to 40 random steps through the real services: top-up, hold, release, settle (a random part of the open orders listed, price 1 to 8 euro, commission 0 to 30%), refund (up to 8 euro, so some exceed the charge), cash-out request, pay out or return, restaurant payout. "Nothing open/charged/reserved" means the step had nothing to act on.

| Checked | When |
|---|---|
| All postings sum to 0 | After every step |
| No student balance below 0 | After every step |
| Every movement sums to 0 | After every run |
| Each student's running balance equals the sum of its postings | After every run, for that run's students |

**The test can fail:** with `post()`'s zero-sum check commented out and a top-up that debits Union cash 1 cent less, it failed: `Failed with seed -2094875815510571490 after 19 steps (set REPLAY = -2094875815510571490L to rerun)`, `all postings sum to 0 ==> expected: <0> but was: <1>`. Both changes were reverted.

**Follow-up, Sun 15 Nov:** the test now runs 50 random runs by default, to keep the suite quick (17 s, against 133 s for 200); `ZERO_SUM_RUNS=200 ./gradlew containerTest` runs 200 for evidence ([edge-cases-2026-11-15.md](edge-cases-2026-11-15.md)).
