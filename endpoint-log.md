| Request | Status | Body summary | Time |
| :--- | :--- | :--- | :--- |
| `POST /payments` (valid JSON) | `201 Created` | *Empty body* (Location header returned) | 42ms |
| `POST /payments` (amountMinor: -50) | `400 Bad Request` | ProblemDetail JSON: "Validation Failure" | 12ms |
| `POST /payments` (merchantId: "") | `400 Bad Request` | ProblemDetail JSON: "Validation Failure" | 11ms |
| `GET /payments/settlement?merchantId=MR-4471` | `200 OK` | `{"merchantId":"MR-4471", "amountOwedMinor":124469}` | 28ms |
| `GET /payments/settlement?merchantId=UNKNOWN` | `404 Not Found` | ProblemDetail JSON: "Merchant Not Found" | 15ms |
