// k6 last-place test (Orders): 200 students try to join a deal with a cap of 50, all at once,
// spread over both instances. Exactly 50 may get a place; everyone else must get DEAL_FULL.
//
//   scripts/last-place.sh <dealId> tokens.txt
//
// tokens.txt holds one student token per line (200 different students; the dev kit README shows
// how to get tokens from its token endpoint). The deal must be open with cap 50 and nobody in it.
// Afterwards, check the database too: responses can lie, rows cannot.

import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { SharedArray } from "k6/data";

const tokens = new SharedArray("tokens", () => open(__ENV.TOKENS_FILE).split(/\r?\n/).filter((t) => t.trim()));
const targets = (__ENV.TARGETS || "http://orders-1:8080,http://orders-2:8080").split(",");
const dealId = __ENV.DEAL_ID;
const cap = Number(__ENV.CAP || 50);

const placesTaken = new Counter("places_taken"); // CONFIRMED or PENDING
const dealFull = new Counter("deal_full");
const other = new Counter("other_answers");

export const options = {
  scenarios: {
    rush: { executor: "per-vu-iterations", vus: Number(__ENV.STUDENTS || 200), iterations: 1, maxDuration: "60s" },
  },
  thresholds: {
    places_taken: [`count<=${cap}`],   // never more than the cap
    other_answers: ["count==0"],       // every answer is a place or DEAL_FULL
  },
};

export default function () {
  const token = tokens[(__VU - 1) % tokens.length];
  const base = targets[__VU % targets.length];
  const res = http.post(`${base}/api/orders/deals/${dealId}/orders`, null, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { instance: base },
  });
  let body = {};
  try { body = res.json(); } catch (e) { /* not JSON */ }
  if ((res.status === 200 || res.status === 201) && (body.status === "CONFIRMED" || body.status === "PENDING")) placesTaken.add(1);
  else if (res.status === 409 && body.code === "DEAL_FULL") dealFull.add(1);
  else other.add(1, { status: String(res.status), code: String(body.code || body.status) });
  check(res, { "a place or DEAL_FULL": () => true });
}

export function handleSummary(data) {
  const taken = data.metrics.places_taken ? data.metrics.places_taken.values.count : 0;
  const full = data.metrics.deal_full ? data.metrics.deal_full.values.count : 0;
  const odd = data.metrics.other_answers ? data.metrics.other_answers.values.count : 0;
  const verdict = taken <= cap && odd === 0 ? (taken === cap ? "PASS" : "CHECK") : "FAIL";
  return {
    stdout: `\nLast-place test: ${taken} places taken (cap ${cap}), ${full} DEAL_FULL, ${odd} other answers: ${verdict}\n` +
      (verdict === "CHECK" ? "Fewer places than the cap were taken: some students had no balance, or the deal was not empty.\n" : "") +
      "Now count the orders in the database as well.\n",
  };
}
