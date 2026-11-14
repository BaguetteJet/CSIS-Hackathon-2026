"""LED-21 outage drills on the dev kit: Kafka paused 5 min, MySQL down 2 min, one instance killed, both down 10 min.
Usage: python3 -I led21_drills.py <dev-kit dir>. Prints a timestamped log; never prints a token or password."""
import base64
import datetime
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

DK = sys.argv[1]
PORTS = (8081, 8082)
CUTOFF = "2026-11-15T23:00:00Z"
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


def http(method, url, body=None, token=None, timeout=15):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else None
    started = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=headers), timeout=timeout) as r:
            raw = r.read()
            return r.status, time.time() - started, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw) if raw else None
        except ValueError:
            parsed = None
        return e.code, time.time() - started, parsed
    except Exception as e:  # connection refused, timeout
        return "ERR " + type(e).__name__, time.time() - started, None


def hold(port, student, deal, cents, order=None):
    order = order or str(uuid.uuid4())
    status, took, body = http("POST", f"http://localhost:{port}/internal/ledger/holds",
                              {"orderId": order, "studentId": student, "dealId": deal, "cutoffAt": CUTOFF, "amountCents": cents})
    result = body.get("result") if isinstance(body, dict) else None
    code = body.get("code") if isinstance(body, dict) else None
    return order, status, took, result or code


def envelope(type_, key, payload, producer, correlation):
    return {"eventId": str(uuid.uuid4()), "type": type_, "version": 1, "key": key, "sequence": int(time.time() * 1000) % 1_000_000_000,
            "occurredAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
            "correlationId": correlation, "causationId": None, "producer": producer, "payload": payload}


def produce(topic, env, times=1):
    line = env["key"] + "|" + json.dumps(env) + "\n"
    r = run(["docker", "exec", "-i", "devkit-kafka-1", "kafka-console-producer", "--bootstrap-server", "localhost:9092", "--topic", topic,
             "--property", "parse.key=true", "--property", "key.separator=|"], stdin=line * times)
    if r.returncode != 0:
        log("produce failed:", r.stderr[-300:])


def top_up(student, cents, topup=None, times=1):
    topup = topup or str(uuid.uuid4())
    produce("payments.v1", envelope("TopUpRecorded", student, {"topUpId": topup, "studentId": student, "amountCents": cents,
                                                               "staffId": str(uuid.uuid4())}, "payments", student), times)
    return topup


def balance(student):
    rows = sql(f"SELECT purpose, balance_cents FROM ledger_account WHERE owner_type='STUDENT' AND owner_id='{student}'")
    found = dict(line.split("\t") for line in rows.splitlines() if line)
    return int(found.get("AVAILABLE", 0)), int(found.get("HELD", 0))


def wait_for(what, condition, limit):
    started = time.time()
    while time.time() - started < limit:
        if condition():
            log(f"{what} after {time.time() - started:.0f} s")
            return True
        time.sleep(2)
    log(f"{what}: not within {limit} s")
    return False


def healthy(port):
    status, _, body = http("GET", f"http://localhost:{port}/actuator/health", timeout=3)
    return status == 200 and isinstance(body, dict) and body.get("status") == "UP"


def metric(name, port=8081):
    status, _, _ = http("GET", f"http://localhost:{port}/actuator/health", timeout=3)
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=5) as r:
            for line in r.read().decode().splitlines():
                if line.startswith(name + "{") or line.startswith(name + " "):
                    return float(line.rsplit(" ", 1)[1])
    except Exception:
        return None
    return None


def events(event_type, field, value):
    return int(sql(f"SELECT COUNT(*) FROM outbox_event WHERE event_type='{event_type}' "
                   f"AND JSON_UNQUOTE(JSON_EXTRACT(message_json, '$.payload.{field}'))='{value}'") or 0)


def books_balanced():
    return sql("SELECT COALESCE(SUM(amount_cents),0) FROM ledger_posting") == "0"


def student_token():
    return run([f"{DK}/token", "student011"]).stdout.strip()


def student_of(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


def funded(cents):
    student = str(uuid.uuid4())
    top_up(student, cents)
    wait_for(f"student {student[:8]} funded {cents}", lambda: balance(student)[0] == cents, 60)
    return student


# ---------------------------------------------------------------------------------------------------------------------

def kafka_paused():
    log("== Drill 1: Kafka paused 5 min: hold calls still answered; events wait in the outbox, then go out in per-key order")
    student = funded(50_000)
    deal = str(uuid.uuid4())
    run(["docker", "pause", "devkit-kafka-1"])
    log("Kafka paused")
    answers, slowest = [], 0.0
    started = time.time()
    while time.time() - started < 300:
        for port in PORTS:
            _, status, took, result = hold(port, student, deal, 100)
            answers.append((status, result))
            slowest = max(slowest, took)
        if int(time.time() - started) % 60 < 15:
            log(f"  {int(time.time() - started)} s: unpublished outbox rows {sql('SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL')}")
        time.sleep(15)
    waiting = int(sql("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL") or 0)
    run(["docker", "unpause", "devkit-kafka-1"])
    log(f"Kafka unpaused; {waiting} events waiting")
    check(all(a == (200, "PLACED") for a in answers), f"{len(answers)} hold calls while Kafka was paused all PLACED; slowest {slowest:.2f} s")
    check(slowest < 2, "every hold answer under 2 s")
    check(waiting >= len(answers), f"the {len(answers)} HoldPlaced events waited in the outbox")
    check(wait_for("outbox drained", lambda: sql("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL") == "0", 180), "outbox drains after Kafka returns")
    out_of_order = sql(f"SELECT COUNT(*) FROM outbox_event a JOIN outbox_event b ON a.event_key = b.event_key AND a.id < b.id "
                       f"AND a.published_at > b.published_at WHERE a.event_key = '{student}'")
    check(out_of_order == "0", "the student's events were published in order (outbox id order = publish order)")


def database_down():
    log("== Drill 2: MySQL down 2 min: 503 UNAVAILABLE fast, never a 500 or a hang; normal within 60 s; no valid event dead-lettered")
    token = student_token()
    student = funded(5_000)
    late = str(uuid.uuid4())
    dead_before = metric("ledger_dead_letters")
    run(["docker", "stop", "devkit-mysql-1"])
    down_at = time.time()
    log("MySQL stopped")
    answers = []
    sent = False
    while time.time() - down_at < 120:
        _, status, took, result = hold(8081, student, str(uuid.uuid4()), 100)
        answers.append(("hold", status, took, result))
        for path in ("/api/ledger/me", "/api/ledger/me/entries"):
            status, took, body = http("GET", f"http://localhost:8082{path}", token=token)
            answers.append((path, status, took, body.get("code") if isinstance(body, dict) else None))
        if not sent and time.time() - down_at > 20:
            top_up(late, 700)  # a valid top-up while the database is down
            log("  top-up of 700 sent while MySQL is down")
            sent = True
        time.sleep(10)
    for kind, status, took, result in answers[:3]:
        log(f"  e.g. {kind}: {status} {result} in {took:.2f} s")
    check(all(a[1] == 503 and a[3] == "UNAVAILABLE" for a in answers), f"{len(answers)} calls with MySQL down all 503 UNAVAILABLE")
    check(max(a[2] for a in answers) < 10, f"none over 10 s (slowest {max(a[2] for a in answers):.2f} s)")
    run(["docker", "start", "devkit-mysql-1"])
    back_at = time.time()
    log("MySQL started")

    def answering():
        _, status, _, result = hold(8081, student, str(uuid.uuid4()), 100)
        _, status2, _, _ = hold(8082, student, str(uuid.uuid4()), 100)
        return status == 200 and status2 == 200

    ok = wait_for("both instances answering normally", answering, 120)
    check(ok and time.time() - back_at < 60, f"normal answers within 60 s of MySQL returning ({time.time() - back_at:.0f} s)")
    check(wait_for("the top-up sent while MySQL was down credited", lambda: balance(late)[0] == 700, 240), "the top-up is applied after the outage")
    check(metric("ledger_dead_letters") == dead_before, f"no dead letters added (ledger_dead_letters {dead_before} -> {metric('ledger_dead_letters')})")


def instance_killed():
    log("== Drill 3: one instance killed: the other carries on; events delivered twice change nothing")
    student = funded(50_000)
    deal = str(uuid.uuid4())
    answers = []
    stop = threading.Event()

    def load():
        while not stop.is_set():
            _, status, took, result = hold(8082, student, deal, 100)
            answers.append((status, took, result))
            time.sleep(0.5)

    worker = threading.Thread(target=load)
    worker.start()
    time.sleep(5)
    run(["docker", "kill", "ledger-1"])
    log("ledger-1 killed")
    target = str(uuid.uuid4())
    topups = [top_up(target, 100 * (i + 1), times=2) for i in range(3)]  # each envelope delivered twice
    expected = 100 + 200 + 300
    credited = wait_for("top-ups credited by ledger-2 alone", lambda: balance(target)[0] == expected, 120)
    time.sleep(10)
    stop.set()
    worker.join()
    check(all(a[0] == 200 and a[2] == "PLACED" for a in answers), f"{len(answers)} hold calls on ledger-2 all PLACED; slowest {max(a[1] for a in answers):.2f} s")
    check(credited and balance(target)[0] == expected, f"three top-ups each sent twice credited once each: {balance(target)[0]} = {expected}")
    check(all(events("TopUpCredited", "topUpId", t) == 1 for t in topups), "one TopUpCredited per topUpId")
    run(["docker", "start", "ledger-1"])
    check(wait_for("ledger-1 healthy again", lambda: healthy(8081), 120), "ledger-1 restarts healthy")


def both_down():
    log("== Drill 4: the Ledger down 10 min: settlement, top-ups and releases catch up from the topics, each applied once")
    student = funded(5_000)
    d_confirmed, d_failed, d_other = (str(uuid.uuid4()) for _ in range(3))
    restaurant = str(uuid.uuid4())
    a, _, _, ra = hold(8081, student, d_confirmed, 600)
    b, _, _, rb = hold(8082, student, d_confirmed, 600)
    c, _, _, rc = hold(8081, student, d_failed, 300)
    d, _, _, rd = hold(8082, student, d_other, 200)
    check([ra, rb, rc, rd] == ["PLACED"] * 4, "four holds placed before the outage")
    log(f"  before: {balance(student)}")
    run(["docker", "stop", "ledger-1", "ledger-2"])
    down_at = time.time()
    log("both instances stopped")
    topup = str(uuid.uuid4())
    top_up(student, 1000, topup, times=2)
    top_up(student, 1000, topup)  # the same topUpId in a new envelope
    confirmed = envelope("DealConfirmed", d_confirmed, {"dealId": d_confirmed, "restaurantId": restaurant, "priceCents": 500,
                                                        "commissionBasisPoints": 1000,
                                                        "orders": [{"orderId": a, "studentId": student}, {"orderId": b, "studentId": student}]},
                         "deals", d_confirmed)
    produce("deals.v1", confirmed, times=2)
    produce("deals.v1", envelope("DealFailed", d_failed, {"dealId": d_failed, "count": 1}, "deals", d_failed))
    produce("orders.v1", envelope("OrderWithdrawn", d_other, {"orderId": d, "dealId": d_other, "studentId": student}, "orders", d_other))
    log("  sent while down: a top-up 3 times, DealConfirmed twice, DealFailed, OrderWithdrawn")
    while time.time() - down_at < 600:
        time.sleep(30)
        log(f"  down {int(time.time() - down_at)} s")
    run(["docker", "start", "ledger-1", "ledger-2"])
    log("both instances started")
    check(wait_for("both healthy", lambda: healthy(8081) and healthy(8082), 180), "both instances healthy after 10 min down")
    # 5000 - 1700 set aside; +1000 top-up; settled at 500: 100 back each; 300 and 200 released.
    check(wait_for("balance caught up to 5000 / 0", lambda: balance(student) == (5000, 0), 180), f"every effect applied: {balance(student)} = (5000, 0)")
    check(events("TopUpCredited", "topUpId", topup) == 1, "one TopUpCredited for the top-up sent 3 times")
    check(events("DealSettled", "dealId", d_confirmed) == 1, "one DealSettled for DealConfirmed sent twice")
    check(events("OrderCharged", "dealId", d_confirmed) == 2, "two OrderCharged, one per listed order")
    check(events("HoldReleased", "orderId", c) == 1 and events("HoldReleased", "orderId", d) == 1, "each released hold has one HoldReleased")


if __name__ == "__main__":
    log("LED-21 drills on the dev kit; commit", run(["git", "-C", sys.argv[2], "rev-parse", "--short", "HEAD"]).stdout.strip())
    check(healthy(8081) and healthy(8082), "both instances healthy at the start")
    for drill in (kafka_paused, database_down, instance_killed, both_down):
        try:
            drill()
        except Exception as e:
            check(False, f"{drill.__name__} crashed: {type(e).__name__}: {e}")
    check(books_balanced(), "the books still sum to 0 (HANDOVER §8)")
    log("RESULT:", "all passed" if not failures else f"{len(failures)} failed: " + "; ".join(failures))
