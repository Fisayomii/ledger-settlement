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
  - Steady state is confirmed by the table of tenths in each run's detail. In the baseline, allocation stays between 6.19 and 6.22 MB/s and the heap after GC between 36.8 and 37.5 MB in every tenth of the 10 minutes, so nothing is still warming up or accumulating.
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
**Classification: allocation-driven, and mild. It is not a pause problem.**

**The pauses are not the problem.**
- The longest pause is 16.54 ms as recorded, about 14 ms once JFR's clock is corrected.
- All 107 pauses add up to 0.33 s, 0.05 % of the ten minutes.
- p95 request latency is 2.78 ms and p99 is 5.36 ms, so a collection rarely reaches a request at all.
- The heap after each collection is flat at 37 MB of a 2 GB maximum, and the committed heap stays under 100 MB. Nothing is retained, promoted or growing.
- A pause problem, by the lab's definition, is modest allocation with long individual collections: pauses of tens or hundreds of milliseconds, with a latency tail that follows them. None of that is present.
- So neither `-Xmx` nor a collector switch has anything to fix. The first submission's switch to ZGC bought shorter pauses that this service does not need, at a cost in throughput and memory.

**What the collector does cost is frequency.**
- There is a young collection about every 5.6 s.
- That frequency is set entirely by the allocation rate: 6.20 MB/s, about 21 KB per request at 300 req/s. Allocate half as much per request and there are half as many collections.

**Where the allocation comes from.**
- No class dominates the profile: `byte[]` 14.5 %, `Object[]` 9.6 %, and nothing else above 3.2 %. This is not the textbook "few classes" picture.
- Most of the allocation is the framework handling a request: Tomcat buffers, Spring MVC, Jackson.
- One application call site, however, carries **37.5 % of everything the service allocates**: `SettlementService.calculateSettlement` line 29, `repository.findByMerchantId(merchantId)`.
- Within that call, **45.4 % (17.0 % of all allocation)** is Hibernate creating `PaymentEntity` instances and registering them in the persistence context (`EntityInitializerImpl`, `StatefulPersistenceContext`, `EntityEntryContext`). Then come the JDBC driver (15.7 %), other Hibernate session work (14.2 %), query creation (13.7 %) and the transaction proxy (8.7 %). These are all in the baseline detail above.
- The service builds a managed entity for every payment, with its dirty-checking snapshot, only to run `mapToLong(PaymentEntity::getAmountMinor).sum()` over the list and discard it.

**So the remedy is a code change at that site, not a flag.** Section 5 asks the database for the count and the sum instead of the rows. That removes the entity loading and the persistence-context bookkeeping per request, and leaves the query, JDBC and transaction layers in place.

## 5. Remedy
**One code change, at the allocation site the profile names.** `SettlementService.calculateSettlement` now asks the database for the count and the sum instead of the rows:

```java
// PaymentRepository
@Query("select new javatesa6.ledgersettlement.MerchantTotals(count(p), coalesce(sum(p.amountMinor), 0L)) "
        + "from PaymentEntity p where p.merchantId = :merchantId")
MerchantTotals totalsForMerchant(@Param("merchantId") String merchantId);

// SettlementService.calculateSettlement, replacing findByMerchantId(...) and the stream sum
MerchantTotals totals = repository.totalsForMerchant(merchantId);
if (totals.paymentCount() == 0) throw new MerchantNotFoundException(merchantId);
// merchant-status check unchanged
long gross = totals.grossMinor();
```

**What stays the same.**
- No `PaymentEntity` is loaded, so nothing enters the persistence context.
- The result is unchanged: the same `long` minor units and the same fee arithmetic. An unknown merchant and an inactive merchant still give 404.
- `SettlementServiceTest` pins this (124 469 for the 128 450 payment), and k6 records the amount returned by every request (section 6).

**What changes, and what it should save.**
- The launch command is identical: `java -jar target/ledger-settlement.jar`, with no JVM flag changed (`perf/gc-stages.properties`).
- The only difference between baseline and tuned is this commit, tagged `gc-tuned`.
- Expected effect: the 17 % of allocation that was entity loading goes away, so there should be fewer young collections. Pause length should not change, since it was never the problem.

**Why this site and not another.**
- It is the largest share of allocation that the application controls: 37.5 % below one call, 17 % in entity loading alone.
- Everything else in the top sites is framework work per request (Tomcat buffers, Spring MVC `ResolvableType`, Jackson). Changing it would mean changing the framework, not the service.
- The next application frame, line 37, is 1.3 %.
- Disabling open-session-in-view (`spring.jpa.open-in-view`) would also trim allocation. But it is configuration that changes when the persistence context closes for every endpoint, and its share is smaller.
- Heap and collector flags were ruled out in section 4.

### Commands, as executed
<!-- BEGIN:tuned-commands -->
```text
tuned-1 (in the gc-tuned build): java -jar target/ledger-settlement.jar
tuned-1: k6 run -e PHASE=warmup perf/steady-load.js
tuned-1: jcmd 12244 JFR.start name=gc duration=10m filename=gc-tuned.jfr settings=profile
tuned-1: k6 run --summary-export target/sjv-l2/runs/tuned-1.k6-summary.json perf/steady-load.js
baseline-2 (in the gc-baseline build): java -jar target/ledger-settlement.jar
baseline-2: k6 run -e PHASE=warmup perf/steady-load.js
baseline-2: jcmd 27952 JFR.start name=gc duration=10m filename=gc-baseline-2.jfr settings=profile
baseline-2: k6 run --summary-export target/sjv-l2/runs/baseline-2.k6-summary.json perf/steady-load.js
tuned-2 (in the gc-tuned build): java -jar target/ledger-settlement.jar
tuned-2: k6 run -e PHASE=warmup perf/steady-load.js
tuned-2: jcmd 4680 JFR.start name=gc duration=10m filename=gc-tuned-2.jfr settings=profile
tuned-2: k6 run --summary-export target/sjv-l2/runs/tuned-2.k6-summary.json perf/steady-load.js
```
<!-- END:tuned-commands -->

Tuned-1 ran in the session before baseline-2 and tuned-2; the runner resumed from the saved run, so its commands are copied here from that session's console output (pid 12244). Its outputs are under `phase2-compare/runs/tuned-1.*` in the raw zip.

### The four tuned numbers
<!-- BEGIN:tuned-numbers -->
From `gc-tuned.jfr` (600 s recording, collector G1New/G1Old):

| Measure | Value | JDK Mission Control view |
| :--- | ---: | :--- |
| Total allocation rate | 5.16 MB/s (3095 MB in 600 s) | Memory → Allocation (sum of `jdk.ObjectAllocationSample` weights) |
| Top three allocating classes | byte[] (13.3 %); Object[] (8.1 %); java.util.LinkedHashMap (4.4 %) | Memory → Allocation → by class |
| Collection count | 83 (59 young, 24 mixed/old) | Garbage Collections (one row per collection) |
| Longest pause | 14.93 ms as recorded | Garbage Collections → Longest Pause column |

**How the figures were checked.** The allocation total leaves out each thread's first sample (696 MB): a sample's weight is everything its thread allocated since its previous sample, so the first one also carries the start-up and warm-up. Independently, heap growth between consecutive collections gives 5.16 MB/s. `jfr print --events jdk.GarbageCollection gc-tuned.jfr` lists 83 collections, and the longest single `jdk.GCPhasePause` is 14.93 ms. Total pause time 483.7 ms (0.081 % of the run). JFR's clock on this machine ran 1.19× fast (the 600 s recording spans 711 s of JFR timestamps), so every JFR duration, in JDK Mission Control too, reads 1.19× long: the real longest pause is about 12.6 ms. Both sides of any comparison carry the same factor. Load in the same window (k6): 300.0 req/s, p99 6.75 ms, failed 0.000 %, dropped iterations 0.
<!-- END:tuned-numbers -->

### Allocation profile and steady state (tuned run 1)
<!-- BEGIN:tuned-detail -->
```text
Recording 600 s (jfr summary). Allocation 3094.8 MB = 5.16 MB/s; each thread's first sample (696.1 MB, allocated before the recording started) is left out.
Cross-check: heap growth between consecutive collections 3093.3 MB = 5.16 MB/s.
Whole machine CPU (all processes, jdk.CPULoad machineTotal): average 45.1 %, highest 97.8 %; this JVM: 5.6 %.
JFR clock: the 600 s recording spans 711 s of JFR time (569 one-per-second jdk.CPULoad samples), factor 1.185 (1.000 = accurate; JFR durations below are as recorded).

Top allocating classes:
   13.3 %  byte[]
    8.1 %  Object[]
    4.4 %  java.util.LinkedHashMap
    3.2 %  java.lang.String
    3.2 %  java.util.HashMap$Node[]
    3.1 %  java.util.concurrent.ConcurrentHashMap$Node
    2.3 %  java.util.ArrayList
    2.2 %  org.springframework.core.ResolvableType
    2.2 %  java.util.LinkedHashMap$Entry
    2.1 %  java.util.concurrent.ConcurrentHashMap

Top allocation sites (frame where the object was created):
    3.2 %  java.util.HashMap.resize:711
    2.8 %  java.util.Arrays.copyOf:3537
    2.7 %  java.lang.invoke.DirectMethodHandle.allocateInstance:500
    2.6 %  java.util.concurrent.ConcurrentHashMap.putVal:1031
    2.6 %  jdk.internal.misc.Unsafe.allocateUninitializedArray:1396
    2.3 %  java.util.concurrent.CopyOnWriteArrayList.toArray:338
    2.2 %  java.util.LinkedHashMap.newNode:281
    2.1 %  java.util.Arrays.copyOfRange:3849
    2.0 %  java.util.ArrayList.grow:240
    1.8 %  org.springframework.core.ResolvableType.forType:1533
    1.8 %  org.springframework.web.context.request.AbstractRequestAttributes.<init>:36
    1.8 %  org.springframework.web.context.request.ServletRequestAttributes.<init>:70
    1.7 %  java.nio.HeapByteBuffer.<init>:75
    1.2 %  java.time.Instant.create:416
    1.0 %  org.hibernate.internal.SessionFactoryImpl$1.createSession:551

First application frame on the allocating stack:
   70.9 %  (no application frame on the stack)
   28.6 %  javatesa6.ledgersettlement.SettlementService.calculateSettlement:27
    0.4 %  javatesa6.ledgersettlement.SettlementService$$SpringCGLIB$$0.calculateSettlement
    0.1 %  javatesa6.ledgersettlement.PaymentController.getSettlement:51

What javatesa6.ledgersettlement.SettlementService.calculateSettlement:27 allocated, by layer (share of that call, share of all):
   25.7 %    7.4 %  other Hibernate (session, JDBC coordination)
   25.2 %    7.2 %  query creation and parameter binding
   21.7 %    6.2 %  JDBC driver (PgJDBC)
   14.8 %    4.2 %  Spring transaction and repository proxy
    8.2 %    2.4 %  Hibernate entity loading and persistence context
    2.3 %    0.7 %  Hikari connection checkout
    2.1 %    0.6 %  other

Tenths of the recording (alloc MB/s, collections, longest pause ms, avg heap after GC MB):
 1: 5.14 MB/s, 8 GCs, 14.93 ms, 37.7 MB
 2: 5.17 MB/s, 8 GCs, 11.59 ms, 37.5 MB
 3: 5.15 MB/s, 8 GCs, 11.79 ms, 37.3 MB
 4: 5.17 MB/s, 8 GCs, 9.55 ms, 36.9 MB
 5: 5.15 MB/s, 8 GCs, 11.75 ms, 36.8 MB
 6: 5.17 MB/s, 8 GCs, 8.89 ms, 36.6 MB
 7: 5.17 MB/s, 9 GCs, 7.00 ms, 36.6 MB
 8: 5.15 MB/s, 8 GCs, 5.22 ms, 36.2 MB
 9: 5.16 MB/s, 9 GCs, 7.30 ms, 36.5 MB
10: 5.15 MB/s, 9 GCs, 6.07 ms, 36.7 MB
```
<!-- END:tuned-detail -->

## 6. Baseline versus tuned
<!-- BEGIN:comparison -->
Each cell: mean of the runs, every run in brackets. Change counts only when it is larger than the run-to-run spread (highest minus lowest run) inside either side; otherwise it is marked *within noise*.

| Measure | Baseline | Tuned | Change |
| :--- | :--- | :--- | :--- |
| Allocation rate (MB/s) | 6.22 (6.20, 6.23) | 5.16 (5.16, 5.16) | -17.0 % (better) |
| Collection count (10 min) | 124 (107, 140) | 86 (83, 89) | -30.4 % (better) |
| Longest pause (ms) | 14.64 (16.54, 12.74) | 17.92 (14.93, 20.91) | +22.4 % (within noise) |
| p99 request latency (ms) | 6.19 (5.36, 7.01) | 6.80 (6.75, 6.85) | +9.9 % (within noise) |
| Throughput (req/s; held at 300 by the arrival rate) | 299.9 (299.9, 299.9) | 300.0 (300.0, 300.0) | +0.0 % (unchanged) |
| Dropped iterations (rate not met) | 27 (53, 0) | 0 (0, 0) | -100.0 % (within noise) |
| Total GC pause time (ms) | 546.2 (325.6, 766.8) | 483.7 (483.7, 483.7) | -11.4 % (within noise) |
| p50 / p95 latency (ms) | 3.10 / 4.42 | 3.91 / 5.95 | +25.8 % (within noise) / +34.6 % (within noise) |
| Heap after GC, average (MB) | 35.7 (37.0, 34.3) | 37.4 (36.9, 37.8) | +4.8 % (within noise) |
| Heap committed, max (MB) | 88.6 (97.5, 79.7) | 96.5 (97.5, 95.4) | +8.9 % (within noise) |
| Resident set size, max (MB) | 351.9 (361.7, 342.0) | 368.8 (372.5, 365.0) | +4.8 % (within noise) |
| JVM CPU (% of machine) | 4.6 (2.8, 6.3) | 5.2 (5.6, 4.7) | +13.4 % (within noise) |
| Whole machine CPU, all processes (%) | 35.8 (24.8, 46.8) | 40.4 (45.1, 35.7) | +12.8 % (within noise) |
| Start-up until serving (s) | 14.5 (12.5, 16.4) | 20.2 (16.9, 23.6) | +39.9 % (within noise) |
| Failed requests (%) | 0.000 (0.000, 0.000) | 0.000 (0.000, 0.000) | n/a |

`amountOwedMinor` returned: baseline 125438–125438, tuned 125438–125438: the result is identical in every response of every run.

**Runs set aside.**
- Baseline-2 was set aside twice in the session after tuned-1: k6 needed 990 s and then 2 477 s for the 10-minute profile, because the laptop went into standby. Both attempts are kept under `phase2-compare/runs/discarded/` in the raw zip, and baseline-2 was repeated with standby disabled.
- An earlier compare session was abandoned entirely. Its runs started 2× slower than in phase 1 and one recorded a 2.1 s "pause", which was the operating system starving the JVM, not garbage collection. None of its figures are used.

**Reading the table.**
- Allocation rate and collection count fall well outside the run-to-run spread: −17 % and −30 %.
- Everything else, pauses, latency, memory, CPU and start-up, moves within the spread between runs of the same build, so no change is claimed for it.
- Allocation per request is a property of the code and barely varies: 6.20 and 6.23 MB/s for the two baselines, 5.16 and 5.16 for the two tuned runs.
- Latency, CPU and start-up depend on what else the laptop was doing. Whole-machine CPU ranged from 25 % to 47 % across runs, so those figures carry the machine's noise.
<!-- END:comparison -->

## 7. The trade
**Regressions, named with their figures.**
- **Throughput:** none. Both builds held 300 req/s with 0 dropped iterations in the tuned runs.
- **Memory footprint:**
  - Heap after GC rose from 35.7 to 37.4 MB (+4.8 %).
  - Resident set size peaked at 368.8 against 351.9 MB (+4.8 %).
  - Committed heap peaked at 96.5 against 88.6 MB.
  - All three are within the spread between the two baseline runs. With fewer collections, a little more garbage sits in the heap between them.
- **Start-up time:** 20.2 s against 14.5 s (+39.9 %), but the runs overlap: 16.9 and 23.6 s tuned, 12.5 and 16.4 s baseline. The change adds one `@Query` for Spring Data to parse at start-up, which is milliseconds, so the difference is not attributed to it.
- **Latency:** p99 6.80 against 6.19 ms, and the longest pause 17.9 against 14.6 ms as recorded. Both are within noise.
- **Code:** one JPQL aggregate to maintain instead of a derived query.

**Why the trade is acceptable.** For the settlement read that every merchant dashboard polls, the service now does the same work with 17 % less allocation and 30 % fewer collections, and no measurable cost in latency, memory or throughput. The money arithmetic is untouched, on the same `long` minor units. The cost is one query that says exactly what the code needs, a merchant's count and sum, which is clearer than loading every payment to add it up.

**When it would stop being acceptable.**
- If settlement starts applying rules per payment, such as per-payment fees, refunds or currency conversion, a database sum no longer expresses the calculation. The rows, or a projection of the needed columns, would have to come back, and with them the allocation.
- If this ever became a pause problem instead, for example with a much larger heap or long-lived caches, the next step would be a heap or collector change measured the same way, not more query tuning.

**After release, watch:**
- the young-collection rate (`jvm.gc.pause` count per minute), which should be about 30 % lower than before at equal traffic;
- `/payments/settlement` p99 latency, which should be unchanged;
- the settlement totals against the daily reconciliation.

## 8. Reproduce and evidence
- **Phase 1:** `java perf/GcLab.java baseline` runs one baseline run.
- **Phase 2:** `java perf/GcLab.java compare` runs tuned 1, baseline 2 and tuned 2. It reads the remedy from `perf/gc-stages.properties`.
- **Prerequisites:** both need Postgres on `localhost:5433`, k6 on the PATH and JDK 25. Each run takes about 13 minutes.
- **Deliverables:** `gc-baseline.jfr` and `gc-tuned.jfr` (repository root), `perf/steady-load.js` and this document.
- **Raw outputs:** `perf/results/sjv-l2-raw.zip` holds, for every run, the k6 console output and summary JSON, the service log, `jfr summary`, `jfr print --events jdk.GarbageCollection`, and the allocation and per-minute analysis.
  - `phase1-baseline/` holds baseline-1, including its first-pass analysis before the correction described in the git history.
  - `phase2-compare/` holds tuned-1, baseline-2 and tuned-2, and the two set-aside attempts.
- **Recompute:** `java perf/GcLab.java reanalyse` recomputes every figure from the `.jfr` files.
- **Before a run on a laptop:** set the screen and sleep timeouts to Never while plugged in. On Modern Standby machines, a screen timeout puts the machine into standby whatever the runner requests.
