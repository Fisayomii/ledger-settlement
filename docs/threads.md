### Performance Baseline vs. Virtual Threads Migration

| Metric | Baseline (Platform Threads) | Virtual Threads Migration |
| :--- | :--- | :--- |
| **Throughput** | 1571.54 req/s | 1405.26 req/s |
| **p50 Latency** | 107.64 ms | 124.34 ms |
| **p95 Latency** | 251.21 ms | 257.18 ms |
| **p99 Latency** | 661.33 ms | 442.93 ms |

**Observation:** Throughput got slightly worse.