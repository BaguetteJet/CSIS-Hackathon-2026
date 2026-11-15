// The lunch rush, as PLT-14 runs it: 100 hold calls a second for 20 s over 10 deals, each student once per deal,
// while students read their Account page and the desk reads balances. Driven by scripts/drills/load_checks.py.
//
//   docker run --rm --network hackathon -v "$PWD/scripts/k6:/scripts" -v <dir>:/data -e DEALS=<10 ids> \
//     grafana/k6:0.57.0 run /scripts/rush.js
//
// <dir>/students.json: [{id, token}] for 200 funded students; <dir>/finance.txt: a UNION_FINANCE token.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const students = JSON.parse(open('/data/students.json'));
const finance = open('/data/finance.txt').trim();
const deals = __ENV.DEALS.split(',');
const replicas = ['http://ledger-1:8080', 'http://ledger-2:8080'];
const gateway = 'http://gateway:80';

export const options = {
  scenarios: {
    holds: { executor: 'constant-arrival-rate', exec: 'hold', rate: 100, timeUnit: '1s', duration: '20s', preAllocatedVUs: 50, maxVUs: 300 },
    pages: { executor: 'constant-arrival-rate', exec: 'page', rate: 30, timeUnit: '1s', duration: '20s', preAllocatedVUs: 10, maxVUs: 100 },
    desk: { executor: 'constant-arrival-rate', exec: 'desk', rate: 5, timeUnit: '1s', duration: '20s', preAllocatedVUs: 5, maxVUs: 20 },
  },
  thresholds: {
    'http_req_duration{call:hold}': ['max<2000'],
    'http_req_duration{call:me}': ['p(95)<250'],
    'http_req_duration{call:entries}': ['p(95)<250'],
    'http_req_duration{call:desk}': ['p(95)<250'],
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export function hold() {
  // Deal by deal, as orders fill: iteration i is student i % 200 joining deal i / 200.
  const i = exec.scenario.iterationInTest;
  if (i >= deals.length * students.length) return;
  const body = JSON.stringify({
    orderId: uuid(),
    studentId: students[i % students.length].id,
    dealId: deals[Math.floor(i / students.length)],
    cutoffAt: '2026-11-15T23:00:00Z',
    amountCents: 100,
  });
  const res = http.post(`${replicas[i % 2]}/internal/ledger/holds`, body, { headers: { 'Content-Type': 'application/json' }, tags: { call: 'hold' } });
  check(res, {
    'hold 200': (r) => r.status === 200,
    'hold PLACED': (r) => r.status === 200 && r.json('result') === 'PLACED',
  }, { call: 'hold' });
}

export function page() {
  const s = students[Math.floor(Math.random() * students.length)];
  const entries = exec.scenario.iterationInTest % 2 === 1;
  const res = http.get(`${gateway}/api/ledger/me${entries ? '/entries' : ''}`, { headers: { Authorization: `Bearer ${s.token}` }, tags: { call: entries ? 'entries' : 'me' } });
  check(res, { 'page 200': (r) => r.status === 200 });
}

export function desk() {
  const s = students[Math.floor(Math.random() * students.length)];
  const res = http.get(`${gateway}/api/ledger/students/${s.id}`, { headers: { Authorization: `Bearer ${finance}` }, tags: { call: 'desk' } });
  check(res, { 'desk 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return { '/data/summary.json': JSON.stringify(data), stdout: '' };
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
