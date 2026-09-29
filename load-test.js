import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 200,          // 200 concurrent users
    duration: '60s',   // run for exactly 60 seconds
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)']
};

export default function () {
    // Drive the main read endpoint for Aurora Coffee
    const res = http.get('http://localhost:8080/payments/settlement?merchantId=MR-4471');

    // Verify the endpoint is actually returning data
    check(res, {
        'status is 200': (r) => r.status === 200
    });
}