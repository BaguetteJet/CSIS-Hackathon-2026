"""Write the dev kit's 200 seeded students (id and token) and a UNION_FINANCE token, for load_checks.py and rush.js.
Usage: python3 -I students.py <dev-kit dir> <out dir>. Writes students.json and finance.txt; prints no token."""
import base64
import json
import os
import subprocess
import sys

DK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)


def token(*args):
    return subprocess.run([f"{DK}/token", *args], capture_output=True, text=True, check=True).stdout


def subject(t):
    payload = t.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


tokens = [line.strip() for line in token("--students", "200").splitlines() if line.strip()]
with open(f"{OUT}/students.json", "w") as f:
    json.dump([{"id": subject(t), "token": t} for t in tokens], f)
with open(f"{OUT}/finance.txt", "w") as f:
    f.write(token("ledger-finance").strip())
print(f"{len(tokens)} students and a finance token written to {OUT}")
