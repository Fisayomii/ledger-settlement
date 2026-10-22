# SJV-L2 — Garbage collection under steady load

Ledger settlement service · branch `feature/gc-tuning` · PR #2 into `master`. Every figure here was written by `perf/GcLab.java` from the Flight Recorder files and the k6 summaries; the raw outputs are in `perf/results/sjv-l2-raw.zip` and every run is a line in `perf/results/sjv-l2-runs.csv`.

## 1. Starting point
The JVM's effective heap settings and the machine they were derived from:

<!-- BEGIN:starting-point -->
<!-- END:starting-point -->

**Why these defaults.**
- With no `-Xmx`, the JVM sizes the maximum heap at a quarter of physical memory, so `MaxHeapSize` follows from the machine's RAM.
- G1 is the default collector on any machine with at least 2 CPUs and 1792 MB of memory, so `UseG1GC` is true.

## 2. Load profile
- **Script:** `perf/steady-load.js`, a k6 `constant-arrival-rate` executor at 300 requests/s for `10m`, with no think time, against `GET /payments/settlement?merchantId=MR-4471`. A marker can run it unchanged with `k6 run perf/steady-load.js`.
- **Why this endpoint:** it is the busiest endpoint. Every merchant dashboard polls the settlement read, while `POST /payments` runs once per payment. Each request runs the Postgres query, the merchant-status check and JSON serialisation, so the allocation measured is the service's real request handling.
- **Why this rate:** in SJV-L1 the service sustained roughly 1 400–1 900 req/s on this laptop with 200 users and no think time. 300 req/s keeps it busy but never queueing, so latency reflects garbage collection rather than saturation. k6 reports `dropped_iterations` if the rate was ever not met.
- **Steady state before the measured window:** each run cold-starts the service with `java -jar target/ledger-settlement.jar` and runs the same script for 2 minutes at the same rate (`-e PHASE=warmup`).
  - Only then does Flight Recorder start, via `jcmd` with the lab's options (`duration=10m`, `settings=profile`, the lab's file name), as the measured 10-minute k6 run begins.
  - The lab's command, `-XX:StartFlightRecording=…` at launch, records from JVM start-up, so its first minute would mix class loading and JIT compilation into the GC numbers.
  - Steady state is confirmed by the per-minute table in each run's detail: allocation rate, collections and heap after GC stay level from the first measured minute to the last.
- **Same environment for every run:** the same laptop and JDK, Postgres in Docker, the merchant-directory stub, and the cold start, warm-up and 10-minute window above. The only difference between baseline and tuned is the one change in section 5.

## 3. Baseline
Commands, as executed:

<!-- BEGIN:baseline-commands -->
<!-- END:baseline-commands -->

### The four baseline numbers
<!-- BEGIN:baseline-numbers -->
<!-- END:baseline-numbers -->

### Allocation profile and steady state (baseline run 1)
<!-- BEGIN:baseline-detail -->
<!-- END:baseline-detail -->

## 4. Classification (written before any change)
*To be written from the baseline numbers above and committed before the remedy.*

## 5. Remedy
*Follows from section 4.*

### Commands, as executed
<!-- BEGIN:tuned-commands -->
<!-- END:tuned-commands -->

### The four tuned numbers
<!-- BEGIN:tuned-numbers -->
<!-- END:tuned-numbers -->

### Allocation profile and steady state (tuned run 1)
<!-- BEGIN:tuned-detail -->
<!-- END:tuned-detail -->

## 6. Baseline versus tuned
<!-- BEGIN:comparison -->
<!-- END:comparison -->

## 7. The trade
*Written from section 6.*

## 8. Reproduce and evidence
- **Phase 1:** `java perf/GcLab.java baseline` runs one baseline run.
- **Phase 2:** `java perf/GcLab.java compare` runs tuned 1, baseline 2 and tuned 2. It reads the remedy from `perf/gc-stages.properties`.
- **Prerequisites:** both need Postgres on `localhost:5433`, k6 on the PATH and JDK 25. Each run takes about 13 minutes.
- **Deliverables:** `gc-baseline.jfr` and `gc-tuned.jfr` (repository root), `perf/steady-load.js` and this document.
- **Raw outputs:** `perf/results/sjv-l2-raw.zip` holds, for every run, the k6 console output and summary JSON, the service log, `jfr summary`, `jfr print --events jdk.GarbageCollection`, and the allocation and per-minute analysis.
