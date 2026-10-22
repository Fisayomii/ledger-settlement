# SJV-L2 — Garbage collection under steady load

Ledger settlement service · branch `feature/gc-tuning` · PR #2 into `master`. Every figure here was written by `perf/GcLab.java` from the Flight Recorder files and the k6 summaries; the raw outputs are in `perf/results/sjv-l2-raw.zip` and every run is a line in `perf/results/sjv-l2-runs.csv`.

## 1. Starting point
The JVM's effective heap settings and the machine they were derived from:

<!-- BEGIN:starting-point -->
```text
Recorded 2026-10-22T08:59:40.583852400+01:00[Africa/Lagos]
$ java -XX:+PrintFlagsFinal -version | findstr "MaxHeapSize UseG1GC"
   size_t MaxHeapSize                              = 2099249152                                {product} {ergonomic}
     bool UseG1GC                                  = true                                      {product} {ergonomic}
java version "25.0.4" 2026-07-21 LTS
Java(TM) SE Runtime Environment (build 25.0.4+7-LTS-189)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.4+7-LTS-189, mixed mode, sharing)
Machine: Windows 11 10.0, 8 logical CPUs, 8006 MiB RAM
$ k6 version
k6.exe v2.2.0 (commit/00a9a1b7f5, go1.26.5, windows/amd64)
```
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
```text
baseline-1 (in the gc-baseline build): java -jar target/ledger-settlement.jar
baseline-1: k6 run -e PHASE=warmup perf/steady-load.js
baseline-1: jcmd 15112 JFR.start name=gc duration=10m filename=gc-baseline.jfr settings=profile
baseline-1: k6 run --summary-export target/sjv-l2/runs/baseline-1.k6-summary.json perf/steady-load.js
```
<!-- END:baseline-commands -->

### The four baseline numbers
<!-- BEGIN:baseline-numbers -->
From `gc-baseline.jfr` (600 s recording, collector G1New/G1Old):

| Measure | Value | JDK Mission Control view |
| :--- | ---: | :--- |
| Total allocation rate | 6.20 MB/s (3719 MB in 600 s) | Memory → Allocation (sum of `jdk.ObjectAllocationSample` weights) |
| Top three allocating classes | byte[] (14.5 %); Object[] (9.6 %); java.lang.String (3.2 %) | Memory → Allocation → by class |
| Collection count | 107 (73 young, 34 mixed/old) | Garbage Collections (one row per collection) |
| Longest pause | 16.54 ms as recorded | Garbage Collections → Longest Pause column |

**How the figures were checked.** The allocation total leaves out each thread's first sample (891 MB): a sample's weight is everything its thread allocated since its previous sample, so the first one also carries the start-up and warm-up. Independently, heap growth between consecutive collections gives 6.38 MB/s. `jfr print --events jdk.GarbageCollection gc-baseline.jfr` lists 107 collections, and the longest single `jdk.GCPhasePause` is 16.54 ms. Total pause time 325.6 ms (0.054 % of the run). JFR's clock on this machine ran 1.19× fast (the 600 s recording spans 711 s of JFR timestamps), so every JFR duration, in JDK Mission Control too, reads 1.19× long: the real longest pause is about 14.0 ms. Both sides of any comparison carry the same factor. Load in the same window (k6): 299.9 req/s, p99 5.36 ms, failed 0.000 %, dropped iterations 53.
<!-- END:baseline-numbers -->

### Allocation profile and steady state (baseline run 1)
<!-- BEGIN:baseline-detail -->
```text
Recording 600 s (jfr summary). Allocation 3719.1 MB = 6.20 MB/s; each thread's first sample (891.0 MB, allocated before the recording started) is left out.
Cross-check: heap growth between consecutive collections 3826.3 MB = 6.38 MB/s.
Whole machine CPU (all processes, jdk.CPULoad machineTotal): average 24.8 %, highest 96.0 %; this JVM: 2.8 %.
JFR clock: the 600 s recording spans 711 s of JFR time (582 one-per-second jdk.CPULoad samples), factor 1.185 (1.000 = accurate; JFR durations below are as recorded).

Top allocating classes:
   14.5 %  byte[]
    9.6 %  Object[]
    3.2 %  java.lang.String
    2.8 %  java.nio.HeapByteBuffer
    2.7 %  java.util.HashMap$Node[]
    2.7 %  java.util.LinkedHashMap
    2.1 %  org.springframework.core.ResolvableType
    2.0 %  java.nio.StringCharBuffer
    2.0 %  java.util.concurrent.ConcurrentHashMap$Node
    2.0 %  java.util.LinkedHashMap$Entry

Top allocation sites (frame where the object was created):
    2.9 %  java.util.IdentityHashMap.init:266
    2.9 %  java.util.Arrays.copyOf:3537
    2.7 %  java.util.HashMap.resize:711
    2.4 %  java.lang.invoke.DirectMethodHandle.allocateInstance:500
    2.1 %  jdk.internal.misc.Unsafe.allocateUninitializedArray:1396
    2.0 %  java.nio.CharBuffer.wrap:552
    2.0 %  java.nio.ByteBuffer.allocate:398
    2.0 %  java.nio.HeapByteBuffer.<init>:75
    2.0 %  java.util.LinkedHashMap.newNode:281
    1.9 %  sun.nio.cs.ISO_8859_1.newEncoder:65
    1.6 %  java.util.concurrent.ConcurrentHashMap.putVal:1031
    1.6 %  java.util.Arrays.copyOfRange:3849
    1.6 %  sun.util.calendar.Gregorian.newCalendarDate:94
    1.6 %  java.util.concurrent.CopyOnWriteArrayList.toArray:338
    1.5 %  org.springframework.core.ResolvableType.forType:1533

First application frame on the allocating stack:
   60.9 %  (no application frame on the stack)
   37.5 %  javatesa6.ledgersettlement.SettlementService.calculateSettlement:29
    1.3 %  javatesa6.ledgersettlement.SettlementService.calculateSettlement:37
    0.2 %  javatesa6.ledgersettlement.SettlementService$$SpringCGLIB$$0.calculateSettlement
    0.2 %  javatesa6.ledgersettlement.PaymentController.getSettlement:51
    0.0 %  javatesa6.ledgersettlement.SettlementService$$SpringCGLIB$$0.recordPayment

What javatesa6.ledgersettlement.SettlementService.calculateSettlement:29 allocated, by layer (share of that call, share of all):
   45.4 %   17.0 %  Hibernate entity loading and persistence context
   15.7 %    5.9 %  JDBC driver (PgJDBC)
   14.2 %    5.3 %  other Hibernate (session, JDBC coordination)
   13.7 %    5.1 %  query creation and parameter binding
    8.7 %    3.3 %  Spring transaction and repository proxy
    1.3 %    0.5 %  Hikari connection checkout
    0.9 %    0.4 %  other

Tenths of the recording (alloc MB/s, collections, longest pause ms, avg heap after GC MB):
 1: 6.19 MB/s, 11 GCs, 8.21 ms, 37.5 MB
 2: 6.20 MB/s, 9 GCs, 7.44 ms, 37.0 MB
 3: 6.19 MB/s, 11 GCs, 7.01 ms, 37.0 MB
 4: 6.20 MB/s, 12 GCs, 3.06 ms, 36.9 MB
 5: 6.19 MB/s, 10 GCs, 3.13 ms, 36.8 MB
 6: 6.19 MB/s, 11 GCs, 3.45 ms, 37.0 MB
 7: 6.22 MB/s, 10 GCs, 7.49 ms, 37.0 MB
 8: 6.20 MB/s, 12 GCs, 5.90 ms, 37.0 MB
 9: 6.21 MB/s, 11 GCs, 11.37 ms, 37.0 MB
10: 6.20 MB/s, 10 GCs, 16.54 ms, 37.3 MB
```
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
