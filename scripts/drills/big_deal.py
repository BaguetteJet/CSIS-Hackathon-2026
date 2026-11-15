"""X9: a deal of N orders settles while holds arrive at 100/s for the same students (another deal).
Usage: python3 -I big_deal.py N [N ...]. Uses the running ledger-1/2 on 8081/8082 and the dev kit's MySQL and Kafka."""
import concurrent.futures as cf
import datetime
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

CUTOFF = "2026-11-15T23:00:00Z"


def log(*parts):
    print(time.strftime("%H:%M:%S", time.gmtime()), *parts, flush=True)


def run(args, stdin=None):
    return subprocess.run(args, input=stdin, capture_output=True, text=True)


def sql(query):
    return run(["docker", "exec", "devkit-mysql-1", "sh", "-c", 'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" ledger -e "$1" 2>/dev/null', "sh", query]).stdout.strip()


def count(query):
    return int(sql(query) or 0)


def envelope(type_, key, payload, producer):
    return {"eventId": str(uuid.uuid4()), "type": type_, "version": 1, "key": key, "sequence": int(time.time() * 1000) % 1_000_000_000,
            "occurredAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "correlationId": key, "causationId": None, "producer": producer, "payload": payload}


def produce(topic, envelopes):
    lines = "".join(e["key"] + "|" + json.dumps(e) + "\n" for e in envelopes)
    r = run(["docker", "exec", "-i", "devkit-kafka-1", "kafka-console-producer", "--bootstrap-server", "localhost:9092", "--topic", topic,
             "--producer-property", "max.request.size=5000000",
             "--property", "parse.key=true", "--property", "key.separator=|"], stdin=lines)
    if r.returncode != 0:
        log("produce failed:", r.stderr[-400:])


def hold(port, order, student, deal):
    body = json.dumps({"orderId": order, "studentId": student, "dealId": deal, "cutoffAt": CUTOFF, "amountCents": 100}).encode()
    started = time.time()
    try:
        req = urllib.request.Request(f"http://localhost:{port}/internal/ledger/holds", data=body, method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, time.time() - started, json.loads(r.read()).get("result")
    except urllib.error.HTTPError as e:
        return e.code, time.time() - started, None
    except Exception as e:
        return "ERR " + type(e).__name__, time.time() - started, None


def metric(name, port):
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=5) as r:
            for line in r.read().decode().splitlines():
                if line.startswith(name + "{") or line.startswith(name + " "):
                    return float(line.rsplit(" ", 1)[1])
    except Exception:
        return 0.0
    return 0.0


def timer():
    return (sum(metric("ledger_settlement_seconds_count", p) for p in (8081, 8082)),
            sum(metric("ledger_settlement_seconds_sum", p) for p in (8081, 8082)))


def wait_for(what, cond, limit, since=None):
    started = since or time.time()
    while time.time() - started < limit:
        if cond():
            log(f"{what}: {time.time() - started:.2f} s")
            return time.time() - started
        time.sleep(0.25)
    log(f"{what}: NOT within {limit} s")
    return None


def restarts():
    return run(["docker", "inspect", "-f", "{{.Name}}={{.RestartCount}} oom={{.State.OOMKilled}} started={{.State.StartedAt}}", "ledger-1", "ledger-2"]).stdout.strip().replace("\n", "; ")


def quoted(ids):
    return ",".join(f"'{i}'" for i in ids)


def one(n):
    log(f"===== Deal of {n} orders")
    students = [str(uuid.uuid4()) for _ in range(n)]
    topups = [str(uuid.uuid4()) for _ in students]
    produce("payments.v1", [envelope("TopUpRecorded", s, {"topUpId": t, "studentId": s, "amountCents": 1000, "staffId": str(uuid.uuid4())}, "payments")
                            for s, t in zip(students, topups)])
    wait_for(f"{n} students funded", lambda: count(f"SELECT COUNT(*) FROM ledger_account WHERE purpose='AVAILABLE' AND balance_cents=1000 AND owner_id IN ({quoted(students)})") == n, 300)

    big, other, restaurant = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    orders = [str(uuid.uuid4()) for _ in students]
    with cf.ThreadPoolExecutor(32) as pool:
        placed = list(pool.map(lambda i: hold(8081 + i % 2, orders[i], students[i], big), range(n)))
    ok = sum(1 for p in placed if p[2] == "PLACED")
    log(f"{ok} of {n} holds placed on the big deal")

    # The rush: 100 holds/s on another deal, for the same students in the order settlement locks them.
    rush_n = min(n, 3000)
    rush_orders = [str(uuid.uuid4()) for _ in range(rush_n)]
    results = [None] * rush_n
    pool = cf.ThreadPoolExecutor(64)

    def fire(i):
        results[i] = hold(8081 + i % 2, rush_orders[i], students[i % n], other)

    rush_start = time.time()
    futures = []

    def schedule():
        for i in range(rush_n):
            delay = rush_start + i / 100 - time.time()
            if delay > 0:
                time.sleep(delay)
            futures.append(pool.submit(fire, i))

    sched = threading.Thread(target=schedule)
    sched.start()
    time.sleep(3)
    before = timer()
    sent = time.time()
    produce("deals.v1", [envelope("DealConfirmed", big, {"dealId": big, "restaurantId": restaurant, "priceCents": 95, "commissionBasisPoints": 1000,
                                                         "orders": [{"orderId": o, "studentId": s} for o, s in zip(orders, students)]}, "deals")])
    produced = time.time() - sent
    wall = wait_for(f"deal of {n} settled (from before the send; producing took {produced:.1f} s)",
                    lambda: count(f"SELECT COUNT(*) FROM deal_settlement WHERE deal_id='{big}'") == 1, 600, since=sent)
    after = timer()
    took = after[1] - before[1] if after[0] - before[0] == 1 else None
    sched.join()
    for f in futures:
        f.result()
    pool.shutdown()

    statuses = {}
    for r in results:
        statuses[(r[0], r[2])] = statuses.get((r[0], r[2]), 0) + 1
    slowest = max(r[1] for r in results)
    over2 = sum(1 for r in results if r[1] >= 2)
    log(f"settlement transaction {took if took is None else round(took, 3)} s (timer); charged {sql(f'SELECT charged_count FROM deal_settlement WHERE deal_id=' + repr(big))}")
    log(f"rush: {rush_n} holds over {time.time() - rush_start:.0f} s; answers {statuses}; slowest {slowest:.2f} s; {over2} at or over 2 s")

    # Orders retries a 503 with the same orderId (ORD-13): every retry should now be decided.
    retry = [i for i, r in enumerate(results) if r[0] != 200]
    with cf.ThreadPoolExecutor(16) as p2:
        again = list(p2.map(lambda i: hold(8081 + i % 2, rush_orders[i], students[i % n], other), retry))
    log(f"{len(retry)} retried with the same orderId: {sorted(set(str((a[0], a[2])) for a in again))}")

    wait_for("outbox empty", lambda: count("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL") == 0, 300)
    log(f"books sum {sql('SELECT COALESCE(SUM(amount_cents),0) FROM ledger_posting')}; "
        f"negative {count('SELECT COUNT(*) FROM ledger_account WHERE owner_type=' + repr('STUDENT') + ' AND balance_cents < 0')}")
    produce("deals.v1", [envelope("DealCancelled", other, {"dealId": other, "reason": "WITHDRAWN"}, "deals")])
    wait_for("rush deal's holds released", lambda: count(f"SELECT COUNT(*) FROM hold_decision WHERE deal_id='{other}' AND state='OPEN'") == 0, 300)
    log(f"instances: {restarts()}")
    mem = run(["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.MemUsage}}", "ledger-1", "ledger-2"]).stdout.strip().replace("\n", "; ")
    log(f"memory: {mem}")


for size in sys.argv[1:]:
    one(int(size))
