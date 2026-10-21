// SJV-L1 load profile: 200 concurrent virtual users for 60 s against the main read endpoint.
//   k6 run --summary-export <file>.json perf/threads-load.js
// The same unchanged script is used for the baseline, migrated, pinned and fixed runs.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MERCHANT = 'MR-4471';
// Every amountOwedMinor returned is recorded; its min and max per run show the result is identical in every
// stage (the total depends on what MR-4471 already holds in the database, so no fixed value is assumed).
const amountOwed = new Trend('settlement_amount_minor');

export const options = {
    scenarios: {
        settlement_read: { executor: 'constant-vus', vus: 200, duration: '60s' },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
    // These thresholds always pass; they make k6 report the measured-window ("phase:load") requests
    // separately from the setup requests below.
    thresholds: {
        'http_reqs{phase:load}': ['count>=0'],
        'http_req_duration{phase:load}': ['max>=0'],
        'checks{phase:load}': ['rate>=0'],
        'settlement_amount_minor': ['min>=0'],
    },
};

// Runs once, before the 200 users start, and is not part of the measured window.
export function setup() {
    // 1. Seed the payment the load reads. The id is fixed, so repeating the run does not grow the table.
    const seed = http.post(`${BASE_URL}/payments`,
        JSON.stringify({ id: 'PAY-90312', merchantId: MERCHANT, amountMinor: 128450, currency: 'GBP' }),
        { headers: { 'Content-Type': 'application/json' }, tags: { phase: 'setup' } });
    if (seed.status !== 201) {
        throw new Error(`Seeding failed: HTTP ${seed.status} ${seed.body}`);
    }
    // 2. Twenty sequential requests for a merchant with no payments. They load the web, JPA and
    //    query classes on one thread, so the measured window is not dominated by one-off class loading.
    //    They return 404 before SettlementService reaches MerchantLookup, so they never touch it.
    for (let i = 0; i < 20; i++) {
        http.get(`${BASE_URL}/payments/settlement?merchantId=WARMUP-NO-PAYMENTS`,
            { tags: { phase: 'setup' }, responseCallback: http.expectedStatuses(404) });
    }
}

export default function () {
    const res = http.get(`${BASE_URL}/payments/settlement?merchantId=${MERCHANT}`, { tags: { phase: 'load' } });
    const owed = res.status === 200 ? res.json('amountOwedMinor') : null;
    check(res, {
        'status is 200': (r) => r.status === 200,
        'amountOwedMinor is a positive whole number': () => Number.isInteger(owed) && owed > 0,
    }, { phase: 'load' });
    if (owed !== null) amountOwed.add(owed);
}
