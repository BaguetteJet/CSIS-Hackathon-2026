// [LED-22] - Speed: 100 hold calls a second for 20 s, straight at both replicas (the hold route has no token).
// Every answer must be a 200 hold result within 2 s.
//
//   docker run --rm --network hackathon -v "$PWD/scripts/k6:/scripts" -v <dir with students.json>:/data \
//     grafana/k6:0.57.0 run /scripts/holds.js
//
// students.json: a JSON array of funded student IDs (fund them first with ./sim money-in --students 204).
import http from 'k6/http';
import { check } from 'k6';

const students = JSON.parse(open('/data/students.json'));
const replicas = (__ENV.LEDGER_URLS || 'http://ledger-1:8080,http://ledger-2:8080').split(',');
const deal = __ENV.DEAL_ID || uuid();

export const options = {
  scenarios: {
    holds: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '20s', preAllocatedVUs: 50, maxVUs: 200 },
  },
  thresholds: {
    http_req_duration: ['max<2000'],
    checks: ['rate==1.0'],
  },
};

export default function () {
  const body = JSON.stringify({
    orderId: uuid(),
    studentId: students[Math.floor(Math.random() * students.length)],
    dealId: deal,
    cutoffAt: '2026-11-14T12:00:00Z',
    amountCents: 100,
  });
  const res = http.post(`${replicas[__ITER % replicas.length]}/internal/ledger/holds`, body, { headers: { 'Content-Type': 'application/json' } });
  check(res, {
    'answered 200': (r) => r.status === 200,
    'a hold result': (r) => r.status === 200 && ['PLACED', 'INSUFFICIENT_FUNDS'].includes(r.json('result')),
  });
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
