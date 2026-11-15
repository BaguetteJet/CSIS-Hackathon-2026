# LED-23: the Ledger dashboard, live

Sat 14 Nov 2026, one laptop (Windows, Docker). Dev kit with `--profile full`, `LEDGER_URL=http://ledger:8080`, `FAKE_EXCLUDE=ledger`. Both instances from `main` at `8f4edc7` (`docker compose up -d --build`, 12:13 UTC). Dashboard: `grafana/ledger.json` as merged in #40, Scrape job `services`. Times UTC.

## Holds

Students funded with `./sim money-in --students 204`; holds from `scripts/k6/holds.js` (100/s for 20 s, alternating `ledger-1` and `ledger-2`).

| Run | k6 | Dashboard |
|---|---|---|
| 12:34, first traffic after start | 1,717 requests: 169 answered 503 in the first 4 s (`ledger-1` 62, `ledger-2` 107), then steady; p95 3.33 s, max 4.68 s | Holds not answered (UNAVAILABLE) **169**; by instance: `ledger-1` and `ledger-2` |
| 12:48, warm | 2,001 at 99.9/s, 0 failed; avg 15 ms, p95 30 ms, max 213 ms | 2,001 PLACED, 0 UNAVAILABLE |

The 503s: each instance's 10 database connections were busy (`waiting=104`) and a call waits at most 1 s for one (`connection-timeout: 1000`), then answers 503 as designed (LED-21). After those first seconds there were none. Over the 40 minutes, PLACED on the dashboard was 3,549, the counters' exact total.

## Settlement

`./sim deal-confirmed --repeat` at 13:28: deal `fb8dc4a1-8a31-4c4b-a345-bf35c35608c0`, an order list with no holds, sent twice.

| Where | Saw |
|---|---|
| `deal_settlement` | One row, 13:28:57, 0 charged |
| `ledger.v1` | One `DealSettled` (partition 5, offset 799) and 32 `SettlementIssue`; outbox 0 unsent |
| Dashboard | Settlements **1**, SettlementIssue count **32**, settlement time max 0.30 s (`ledger-1`) |

## What the check changed in the dashboard

- Prometheus scrapes each container twice (`services` as `ledger-1:8080`, and `in-your-ide` as `host.docker.internal:8081`), so sums were doubled. The Scrape job selector shows one.
- A counter appears only on first use, so `increase()` read 0 for the 169 503s. The counts are now the value at the end of the range minus the value at its start, or the whole value for a counter first seen inside it.

## Still to do

- Dead letters, parked refunds, and the alert for a refund parked over 6 h (items 4, 6).
- Holds and settlement could register their counters at start-up with 0 for each result (items 1, 5). Then rates and the mean settlement time also show the first event after a start.
- First traffic after a start: about 10% of holds answered 503 for 4 s at 100/s (item 1).

**Follow-up, Sun 15 Nov:** #60 added parked refunds, dead letters and the 6 h alert. The Scrape job list filtered on its own value (`job="$job"`), so it offered only the job already chosen and `in-your-ide` couldn't be picked; it now lists every job that has Ledger hold metrics.
