"""The test agents' cheap checks (docs/BUG-HUNT.md), on the dev kit with both instances from ./check: every page call's
401/403/404 order and bad IDs (DEC E1, E2), the hold call's bad input, repeats and answer time (E3, E5, E7, LED-04), the
statement's order and wording (LED-11), and the books (I1, I2). Moves no money: holds go to students with no account.
Usage: python3 -I agent_probes.py <dev-kit dir>. Exits 1 on any FAIL. Never prints a token."""
import base64
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

DK = sys.argv[1]
GATEWAY, INSTANCE = "http://localhost:8080", "http://localhost:8081"
failures = []


def check(ok, what):
    print("PASS" if ok else "FAIL", what, flush=True)
    if not ok:
        failures.append(what)


def token(*args):
    return subprocess.run([f"{DK}/token", *args], capture_output=True, text=True, check=True).stdout.strip().splitlines()[0]


def subject(t):
    payload = t.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


def call(url, method="GET", body=None, bearer=None, content_type="application/json"):
    """(status, parsed body or None, seconds)."""
    request = urllib.request.Request(url, data=body, method=method)
    if body is not None and content_type:
        request.add_header("Content-Type", content_type)
    if bearer:
        request.add_header("Authorization", f"Bearer {bearer}")
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=15) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    try:
        parsed = json.loads(raw) if raw else None
    except ValueError:
        parsed = None
    return status, parsed, time.time() - started


def code(parsed):
    return parsed.get("code") if isinstance(parsed, dict) else None


def sql(query):
    r = subprocess.run(["docker", "exec", "devkit-mysql-1", "sh", "-c", 'mysql -N -uroot -p"$MYSQL_ROOT_PASSWORD" ledger -e "$1" 2>/dev/null', "sh", query],
                       capture_output=True, text=True)
    return r.stdout.strip()


student = token("--students", "1")
finance = token("ledger-finance")
me = subject(student)

# Page API through the gateway: 401, then 403, then 404 (E2), and bad IDs are 404, never 400 (E1).
for path in ["/api/ledger/me", "/api/ledger/me/entries", f"/api/ledger/students/{me}"]:
    check(call(GATEWAY + path)[0] == 401, f"{path} with no token is 401")
    check(call(GATEWAY + path, bearer="not.a.token")[0] == 401, f"{path} with a broken token is 401")
check(call(GATEWAY + "/api/ledger/me", bearer=finance)[0] == 403, "/me for Union finance is 403")
check(call(GATEWAY + f"/api/ledger/students/{me}", bearer=student)[0] == 403, "the desk balance for a student is 403")
status, unknown, _ = call(GATEWAY + f"/api/ledger/students/{uuid.uuid4()}", bearer=finance)
check(status == 200 and unknown.get("availableCents") == 0 and unknown.get("heldCents") == 0, "the desk balance for an unknown well-formed ID is 200 with zeros")
for bad in ["not-a-uuid", "1-2-3-4-5", "..;", "a;b=1", "%2e%2e;", "a%2Fb", "a%5Cb", "a%25b", "x" * 10_000]:
    status, parsed, _ = call(GATEWAY + f"/api/ledger/students/{bad}", bearer=finance)
    check(status == 404 and code(parsed) == "NOT_FOUND", f"desk ID {bad[:12]!r} is 404 NOT_FOUND (got {status})")

# The statement: newest first, student wording, nets to available when it holds every entry (LED-11).
status, balance, _ = call(GATEWAY + "/api/ledger/me", bearer=student)
status_e, entries, _ = call(GATEWAY + "/api/ledger/me/entries", bearer=student)
check(status == 200 and status_e == 200, "/me and /me/entries answer 200 for a student")
if status_e == 200:
    times = [e["at"] for e in entries]
    check(times == sorted(times, reverse=True), "statement entries are newest first")
    words = " ".join(e["description"].lower() for e in entries)
    check(" hold" not in f" {words}" and "wallet" not in words, "statement descriptions never say hold or wallet")
    if len(entries) < 100:
        net = sum(e["amountCents"] if e["credit"] else -e["amountCents"] for e in entries)
        check(net == balance["availableCents"], f"statement nets to availableCents ({net} vs {balance['availableCents']})")

# The hold call: inside the network only, bad input 422 or 413, a repeat gets its first answer, every answer within 2 s.
check(call(GATEWAY + "/internal/ledger/holds", "POST", b"{}")[0] == 404, "the gateway never routes the hold call")
HOLDS = INSTANCE + "/internal/ledger/holds"


def hold_body(**override):
    fields = {"orderId": str(uuid.uuid4()), "studentId": str(uuid.uuid4()), "dealId": str(uuid.uuid4()),
              "cutoffAt": "2026-11-14T12:00:00Z", "amountCents": 600}
    fields.update(override)
    return json.dumps({k: v for k, v in fields.items() if v is not ...}).encode()


for what, body, content_type in [
    ("0 cents", hold_body(amountCents=0), "application/json"),
    ("-1 cents", hold_body(amountCents=-1), "application/json"),
    ("6.5 cents", hold_body(amountCents=6.5), "application/json"),
    ("no orderId", hold_body(orderId=...), "application/json"),
    ("a null dealId", hold_body(dealId=None), "application/json"),
    ("a studentId that isn't a UUID", hold_body(studentId="42"), "application/json"),
    ("invalid JSON", b"{not json", "application/json"),
    ("text/plain", hold_body(), "text/plain"),
    ("no Content-Type", hold_body(), None),
]:
    status, parsed, _ = call(HOLDS, "POST", body, content_type=content_type)
    check(status == 422 and code(parsed) == "VALIDATION_FAILED", f"a hold with {what} is 422 VALIDATION_FAILED (got {status})")
status, parsed, _ = call(HOLDS, "POST", b'{"pad":"' + b"x" * 70_000 + b'"}')
check(status == 413 and code(parsed) == "PAYLOAD_TOO_LARGE", f"a 70 KB hold is 413 (got {status})")
check(call(HOLDS)[0] == 405, "GET on the hold call is 405")

order = str(uuid.uuid4())
first = call(HOLDS, "POST", hold_body(orderId=order))
check(first[0] == 200 and first[1].get("result") == "INSUFFICIENT_FUNDS" and first[1].get("availableCents") == 0,
      "a hold for a student with no account is INSUFFICIENT_FUNDS with 0")
for port in ["8081", "8082"]:
    repeat = call(f"http://localhost:{port}/internal/ledger/holds", "POST", hold_body(orderId=order, amountCents=1))
    check(repeat[0] == 200 and repeat[1] == first[1], f"a repeat on {port} with another body gets the first answer")
slowest = max(call(HOLDS, "POST", hold_body())[2] for _ in range(50))
check(slowest < 2.0, f"50 holds each answered within 2 s (slowest {slowest:.2f} s)")

# The books (HANDOVER §8): every movement sums to zero, no student below zero.
check(sql("SELECT COUNT(*) FROM (SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents) <> 0) x") == "0",
      "every movement's sides add up to zero")
check(sql("SELECT COUNT(*) FROM ledger_account WHERE owner_type = 'STUDENT' AND balance_cents < 0") == "0", "no student balance below zero")

print(f"\n{len(failures)} failed" if failures else "\nall passed")
sys.exit(1 if failures else 0)
