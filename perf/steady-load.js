// SJV-L2 steady load: a constant arrival rate against the busiest endpoint for ten minutes.
//   k6 run perf/steady-load.js                      the measured 10-minute window (run it unchanged)
//   k6 run -e PHASE=warmup perf/steady-load.js      the same rate for 2 minutes, to warm the JVM first
// Endpoint: GET /payments/settlement is the service's read path; every merchant dashboard polls it, while
// POST /payments happens once per payment. Rate: 300 requests/s, about a fifth of what this service sustained
// on the same laptop in SJV-L1, so it is busy but never queueing, and latency reflects GC, not saturation.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WARMUP = __ENV.PHASE === 'warmup';
const PHASE = WARMUP ? 'warmup' : 'steady';
const MERCHANT = 'MR-4471';
const amountOwed = new Trend('settlement_amount_minor');

export const options = {
    scenarios: {
        settlement_reads: {
            executor: 'constant-arrival-rate',
            rate: 300,
            timeUnit: '1s',
            duration: WARMUP ? '2m' : '10m',
            preAllocatedVUs: 50,
            maxVUs: 200,
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
    // These thresholds always pass; they make k6 report the measured requests separately from the seed request.
    thresholds: {
        [`http_reqs{phase:${PHASE}}`]: ['count>=0'],
        [`http_req_duration{phase:${PHASE}}`]: ['max>=0'],
        [`http_req_failed{phase:${PHASE}}`]: ['rate>=0'],
        settlement_amount_minor: ['min>=0'],
    },
};

// Seeds the payment the load reads. Its id is fixed, so repeated runs never grow the table.
export function setup() {
    const seed = http.post(`${BASE_URL}/payments`,
        JSON.stringify({ id: 'PAY-90312', merchantId: MERCHANT, amountMinor: 128450, currency: 'GBP' }),
        { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'setup' } });
    if (seed.status !== 201) {
        throw new Error(`Seeding failed: HTTP ${seed.status} ${seed.body}`);
    }
}

export default function () {
    const res = http.get(`${BASE_URL}/payments/settlement?merchantId=${MERCHANT}`, { tags: { phase: PHASE } });
    const owed = res.status === 200 ? res.json('amountOwedMinor') : null;
    check(res, {
        'status is 200': (r) => r.status === 200,
        'amountOwedMinor is a positive whole number': () => Number.isInteger(owed) && owed > 0,
    }, { phase: PHASE });
    if (owed !== null) amountOwed.add(owed);
}
