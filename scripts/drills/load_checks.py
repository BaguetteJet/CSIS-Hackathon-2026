"""Interface targets under load (docs/Constraints.md), on the dev kit with both instances from ./check.
1. The rush (scripts/k6/rush.js): 100 holds/s over 10 deals, page and desk calls, 20 top-ups; a 200-order deal settles mid-burst.
2. A 200-order deal settles with nothing else running.  3. Eight deals cancelled: 1,600 releases.
4. A kitchen failure: 300 RefundApproved at once.  5. The books after all of it.
Times "from before the send" include about 1 s of starting Kafka's console producer, so they are upper bounds.
Usage: python3 -I load_checks.py <dev-kit dir> <repo dir> <data dir with students.json, finance.txt>. Never prints a token."""
import datetime
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

DK, REPO, DATA = sys.argv[1], sys.argv[2], sys.argv[3]
STUDENTS = [s["id"] for s in json.load(open(f"{DATA}/students.json"))]
PRICE, COMMISSION_BP = 95, 1000
failures = []


def log(*parts):
    print(time.strftime("%H:%M:%S", time.gmtime()), *parts, flush=True)


def check(ok, what):
    log("PASS" if ok else "FAIL", what)
    if not ok:
        failures.append(what)


def run(args, stdin=None):
    return subprocess.run(args, input=stdin, capture_output=True, text=True)


def sql(query):
    r = run(["docker", "exec", "devkit-mysql-1", "sh", "-c", 'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" ledger -e "$1" 2>/dev/null', "sh", query])
    return r.stdout.strip()


def count(query):
    return int(sql(query) or 0)


def envelope(type_, key, payload, producer):
    return {"eventId": str(uuid.uuid4()), "type": type_, "version": 1, "key": key, "sequence": int(time.time() * 1000) % 1_000_000_000,
            "occurredAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "correlationId": key, "causationId": None, "producer": producer, "payload": payload}


def produce(topic, envelopes):
    lines = "".join(e["key"] + "|" + json.dumps(e) + "\n" for e in envelopes)
    r = run(["docker", "exec", "-i", "devkit-kafka-1", "kafka-console-producer", "--bootstrap-server", "localhost:9092", "--topic", topic,
             "--property", "parse.key=true", "--property", "key.separator=|"], stdin=lines)
    if r.returncode != 0:
        log("produce failed:", r.stderr[-300:])


def wait_for(what, condition, limit, every=0.25, since=None):
    """Seconds from `since` (default now) until condition holds; `since` before a send counts the send too."""
    started = since or time.time()
    while time.time() - started < limit:
        if condition():
            took = time.time() - started
            log(f"{what}: {took:.2f} s")
            return took
        time.sleep(every)
    log(f"{what}: not within {limit} s")
    return None


def metric(name, port):
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=5) as r:
            for line in r.read().decode().splitlines():
                if line.startswith(name + "{") or line.startswith(name + " "):
                    return float(line.rsplit(" ", 1)[1])
    except Exception:
        return None
    return None


def settlement_timer():
    """Both instances' settlement timer: (count, total seconds). The difference across one settlement is its own duration."""
    counts = [metric("ledger_settlement_seconds_count", p) or 0 for p in (8081, 8082)]
    sums = [metric("ledger_settlement_seconds_sum", p) or 0 for p in (8081, 8082)]
    return sum(counts), sum(sums)


def settle_seconds(before):
    after = settlement_timer()
    return round(after[1] - before[1], 3) if after[0] - before[0] == 1 else None


def placed(deal):
    rows = sql(f"SELECT order_id, student_id FROM hold_decision WHERE deal_id='{deal}' AND result='PLACED'")
    return [line.split("\t") for line in rows.splitlines() if line]


def confirm(deal, restaurant):
    orders = [{"orderId": o, "studentId": s} for o, s in placed(deal)]
    produce("deals.v1", [envelope("DealConfirmed", deal, {"dealId": deal, "restaurantId": restaurant, "priceCents": PRICE,
                                                          "commissionBasisPoints": COMMISSION_BP, "orders": orders}, "deals")])
    return len(orders)


def settled(deal):
    return count(f"SELECT COUNT(*) FROM deal_settlement WHERE deal_id='{deal}'") == 1




def memory():
    r = run(["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}", "ledger-1", "ledger-2"])
    return r.stdout.strip().replace("\n", "; ")


# ---------------------------------------------------------------------------------------------------------------------

restaurant = str(uuid.uuid4())
deals = [str(uuid.uuid4()) for _ in range(10)]
log(f"deals {deals[0][:8]}..{deals[-1][:8]}, restaurant {restaurant[:8]}")

log("== 1 The rush: 100 holds/s for 20 s over 10 deals; 30 page calls/s; 5 desk calls/s")
# label=disable: SELinux (Fedora) otherwise denies both mounts, which k6 reports as "couldn't be found"; --user so it can write summary.json.
k6 = subprocess.Popen(["docker", "run", "--rm", "--network", "hackathon", "--security-opt", "label=disable", "--user", f"{os.getuid()}:{os.getgid()}",
                       "-v", f"{REPO}/scripts/k6:/scripts", "-v", f"{DATA}:/data",
                       "-e", "DEALS=" + ",".join(deals), "grafana/k6:0.57.0", "run", "--quiet", "/scripts/rush.js"],
                      stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
peak = []
sampling = True


def sample():
    while sampling:
        peak.append(memory())
        time.sleep(2)


threading.Thread(target=sample, daemon=True).start()
started = time.time()
wait_for("deal 1's 200 holds placed", lambda: len(placed(deals[0])) >= 200, 30)
timer0 = settlement_timer()
confirmed_at = time.time()
listed = confirm(deals[0], restaurant)
log(f"DealConfirmed for deal 1 with {listed} orders sent {time.time() - started:.1f} s into the burst; holds for deals 2-10 go on")

time.sleep(max(0.0, 10 - (time.time() - started)))
topups = {}
for s in STUDENTS[:20]:
    topups[str(uuid.uuid4())] = s
sent_at = time.time()
produce("payments.v1", [envelope("TopUpRecorded", s, {"topUpId": t, "studentId": s, "amountCents": 500, "staffId": str(uuid.uuid4())}, "payments")
                        for t, s in topups.items()])
ids = ",".join(f"'{t}'" for t in topups)
credited = f"SELECT COUNT(*) FROM outbox_event WHERE event_type='TopUpCredited' AND JSON_UNQUOTE(JSON_EXTRACT(message_json,'$.payload.topUpId')) IN ({ids})"
log(f"20 top-ups sent {sent_at - started:.1f} s into the burst")
topup_took = []
threading.Thread(target=lambda: topup_took.append(wait_for("all 20 top-ups credited, from before the send", lambda: count(credited) == 20, 120, since=sent_at)),
                 daemon=True).start()

wait_for("deal 1 settled, from before the send", lambda: settled(deals[0]), 60, since=confirmed_at)
burst_settle = settle_seconds(timer0)
out, _ = k6.communicate()
sampling = False
if out.strip():
    log("k6:", out.strip()[-1500:])
while not topup_took and time.time() - sent_at < 120:
    time.sleep(0.5)
topup_took = topup_took[0] if topup_took else None
log(f"memory samples (last 3): {peak[-3:]}")

summary = json.load(open(f"{DATA}/summary.json"))
m = summary["metrics"]
for call in ("hold", "me", "entries", "desk"):
    t = m.get(f"http_req_duration{{call:{call}}}", {}).get("values", {})
    log(f"  {call}: med {t.get('med', 0):.0f} ms, p95 {t.get('p(95)', 0):.0f} ms, p99 {t.get('p(99)', 0):.0f} ms, max {t.get('max', 0):.0f} ms")
holds_total = count(f"SELECT COUNT(*) FROM hold_decision WHERE deal_id IN ({','.join(repr(d) for d in deals)})")
holds_placed = count(f"SELECT COUNT(*) FROM hold_decision WHERE deal_id IN ({','.join(repr(d) for d in deals)}) AND result='PLACED'")
log(f"  hold decisions stored {holds_total}, PLACED {holds_placed}")
for group in summary.get("root_group", {}).get("checks", []):
    log(f"  check {group['name']}: {group['passes']} pass, {group['fails']} fail")
hold_max = m.get("http_req_duration{call:hold}", {}).get("values", {}).get("max", 1e9)
check(hold_max < 2000, f"every hold answered within 2 s (max {hold_max:.0f} ms)")
check(burst_settle is not None and burst_settle < 3.0, f"deal 1 (200 orders) settled during the burst in {burst_settle} s; target under 3 s")
check(topup_took is not None and topup_took < 5, f"20 top-ups sent mid-rush, all credited {topup_took} s after the send; target under 5 s")

log("== 2 A 200-order deal settles with nothing else running")
time.sleep(5)
timer1 = settlement_timer()
confirm(deals[1], restaurant)
wait_for("deal 2 settled", lambda: settled(deals[1]), 60)
alone = settle_seconds(timer1)
check(float(alone or 99) < 1.0, f"deal 2 (200 orders) settled alone in {alone} s; target under 1 s")

log("== 3 Deals 3-10 cancelled at once: 1,600 releases")
open_before = count(f"SELECT COUNT(*) FROM hold_decision WHERE deal_id IN ({','.join(repr(d) for d in deals[2:])}) AND state='OPEN'")
cancelled_at = time.time()
produce("deals.v1", [envelope("DealCancelled", d, {"dealId": d, "reason": "WITHDRAWN"}, "deals") for d in deals[2:]])
rel = wait_for(f"{open_before} open holds released",
               lambda: count(f"SELECT COUNT(*) FROM hold_decision WHERE deal_id IN ({','.join(repr(d) for d in deals[2:])}) AND state='OPEN'") == 0, 300, since=cancelled_at)
check(rel is not None and rel < 60, f"{open_before} releases done in {rel} s; target under 60 s")

log("== 4 A kitchen failure: 300 RefundApproved at once")
charged = [line.split("\t") for line in sql(f"SELECT order_id, deal_id, student_id FROM order_charge WHERE deal_id IN ('{deals[0]}','{deals[1]}') LIMIT 300").splitlines() if line]
refunds = [str(uuid.uuid4()) for _ in charged]
refunds_at = time.time()
produce("support.v1", [envelope("RefundApproved", s, {"refundId": r, "orderId": o, "dealId": d, "studentId": s, "amountCents": 50,
                                                      "reason": "KITCHEN_FAILURE", "approvedBy": str(uuid.uuid4())}, "support")
                       for r, (o, d, s) in zip(refunds, charged)])
rids = ",".join(f"'{r}'" for r in refunds)
ref = wait_for(f"{len(refunds)} refunds decided", lambda: count(f"SELECT COUNT(*) FROM refund WHERE refund_id IN ({rids}) AND outcome IS NOT NULL") == len(refunds), 300, since=refunds_at)
paid = count(f"SELECT COUNT(*) FROM refund WHERE refund_id IN ({rids}) AND outcome='POSTED'")
check(ref is not None and ref < 60 and paid == len(refunds), f"{paid} of {len(refunds)} refunds posted in {ref} s; target under 60 s")

log("== 5 The books")
check(sql("SELECT COALESCE(SUM(amount_cents),0) FROM ledger_posting") == "0", "every posting sums to 0")
check(count("SELECT COUNT(*) FROM ledger_account WHERE owner_type='STUDENT' AND balance_cents < 0") == 0, "no student balance below 0")
wait_for("outbox drained", lambda: count("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL") == 0, 60)
check(count("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL") == 0, "every event published")
log(f"memory now: {memory()}")
log("FAILURES: " + "; ".join(failures) if failures else "all checks passed")
