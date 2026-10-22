# SJV-L3 — JMH benchmarks of the Ledger settlement service

Every number in this document comes from `benchmarks/results.json`, the raw JMH output of one final run. `perf/BenchLab.java` writes the tables from that file; the console output of the same run is `benchmarks/results-console.txt`.

## 1. The module and how to run it

- **The build is now two Maven modules under one aggregator `pom.xml`.**
  - `ledger-service/` holds the service, unchanged. It still builds the executable `target/ledger-settlement.jar`.
  - The service module also produces a plain jar of its classes, `ledger-settlement-0.0.1-SNAPSHOT-plain.jar`. The executable jar keeps its classes under `BOOT-INF/`, where no other module can compile against them.
  - `benchmarks/` holds the JMH suite. It depends on `jmh-core` and `jmh-generator-annprocess`, both pinned to 1.37, and on that plain service jar.
- **The shade plugin turns the module into an executable `benchmarks/target/benchmarks.jar`.**
- **The service carries no JMH.** JMH is a dependency of the `benchmarks` module only. `jar tf ledger-service/target/ledger-settlement.jar | findstr jmh` (or `grep jmh`) prints nothing.
- **One command builds and runs everything**, from the repository root with JDK 25 (no database is needed):

  ```text
  java perf/BenchLab.java
  ```

  A full run takes about **65 minutes**: 8 benchmarks × 3 forks × (5 + 10) iterations of 10 s.
- **What that command does:**
  1. Runs `./mvnw -B package`, which builds and tests both modules.
  2. In `benchmarks/`, runs `java -jar target/benchmarks.jar -prof gc -rf json -rff results.json -p machine=…`.
  3. Writes the tables below from `results.json`.

## 2. The methods measured, and why

| Benchmark | Real method | Why this one |
| :--- | :--- | :--- |
| `serialiseSettlementResponse` | Jackson 3 `JsonMapper.writeValueAsString` on `PaymentController.SettlementResponse`, the record every `GET /payments/settlement` returns | **Serialisation.** Runs once per settlement read, the busiest endpoint. SJV-L2 drove it at 300 requests/s, and every one of those responses passes through this call. |
| `parseMerchantDirectory` | `HttpMerchantDirectoryClient.parseTable`: `lines()`, `strip`, `indexOf`, two `substring`s per line, a `LinkedHashMap`, then `Map.copyOf` | **String and collection heavy.** It turns the merchant directory into the status table while the service starts, before it takes traffic. SJV-L1 named that start-up path as a risk. Measured on a 5 000-merchant directory in the stub's exact format. |
| `calculateSettlement` (payments = 2 and 200) | `SettlementService.calculateSettlement`: load the merchant's payments, check its status, sum `amountMinor` with a stream, apply the fee | **The settlement calculation.** It runs behind every settlement read. Money stays in `long` minor units throughout, with no `double`: binary floating point cannot hold an amount like 0.10 exactly. The fee is truncated with integer division, so no rounding mode is needed and no `BigDecimal`. |
| `SettlementArithmeticBenchmark.*` | `SettlementService.owedMinor`, the fee arithmetic inside `calculateSettlement` | The smallest real computation in the service, used in section 3 to show dead-code elimination. |

**Two small refactors make these methods callable from the benchmarks, with behaviour unchanged.** `SettlementService.owedMinor` and `HttpMerchantDirectoryClient.parseTable` were extracted from `calculateSettlement` and `fetchTable`, which now call them. `SettlementArithmeticTest` pins both, for example 128 450 → 124 469.

## 3. Dead-code elimination: the broken benchmark and its fixes

```java
@Benchmark public void brokenDiscardsResult() { SettlementService.owedMinor(grossMinor, feeRateBasisPoints); }          // BROKEN ON PURPOSE
@Benchmark public long fixedReturnsResult() { return SettlementService.owedMinor(grossMinor, feeRateBasisPoints); }
@Benchmark public void fixedConsumesWithBlackhole(Blackhole bh) { bh.consume(SettlementService.owedMinor(grossMinor, feeRateBasisPoints)); }
@Benchmark public void baseline() { }                                                                                  // JMH's own loop, for reference
```

<!-- BEGIN:dce -->
| Benchmark | Time per operation | Allocated |
| :--- | ---: | ---: |
| `baseline`, empty method (JMH's loop only) | 0.0005733 ± 0.0000287 us/op | 0.0000004 ± 0.00000002 B/op |
| `brokenDiscardsResult`, **broken**: result discarded | 0.0006247 ± 0.00003014 us/op | 0.0000004362 ± 0.00000002128 B/op |
| `fixedReturnsResult`, fixed: result returned | 0.003886 ± 0.0003502 us/op | 0.000002713 ± 0.0000002445 B/op |
| `fixedConsumesWithBlackhole`, fixed: result consumed by a Blackhole | 0.002705 ± 0.0008097 us/op | 0.00000189 ± 0.0000005652 B/op |

The broken benchmark's interval overlaps the empty method's. Returning the result makes the measured time 6.22× the broken figure (0.003886 against 0.0006247 us/op), so the broken number understated the real cost by 83.92 %. The two fixes are distinguishable (their intervals do not overlap).
<!-- END:dce -->

**What the JIT removed.**
- C2 inlines `owedMinor` into the benchmark loop. The method is static and has no side effects: a multiply, a divide and a subtract on two `long`s.
- In `brokenDiscardsResult` the result goes nowhere, so the whole expression is dead and is deleted, and the two field loads feeding it go with it.
- What remains is JMH's own loop, so the broken score lands on the empty `baseline`.

The broken benchmark stays in the code, named as broken, so the error can be reproduced.

**When to use each fix.**
- **Return the value** when the benchmark has a single result. JMH consumes whatever a `@Benchmark` method returns.
- **Use a `Blackhole`** when there are several values to sink, or values produced inside a loop. `Blackhole.consume` costs a few nanoseconds itself, which is why the two fixes are compared against each other below.

## 4. Inputs and run configuration

**Inputs.** All inputs are fields of `@State(Scope.Benchmark)` classes, filled in `@Setup`. No measured method contains a literal or a constant that the JIT could fold:
- `grossMinor = 129 450` and `feeRateBasisPoints = 310`. These are MR-4471's gross in the load-test database and `ledger.fee-rate` from `application.yaml`. Together they settle to the 125 438 that every k6 response returned in SJV-L1 and SJV-L2.
- **Settlement, 2 payments:** MR-4471 as it is in that database, payments of 128 450 and 1 000 minor units. `calculateSettlement` returns 125 438 for it.
- **Settlement, 200 payments:** a busy merchant, amounts 1 000–500 000 minor units from `new Random(4471)`, so every run gets the same data.
- **Repository:** answers from memory with those rows, so the database is not measured (see the limits).
- **Merchant directory:** 5 000 lines `MR-00001=ACTIVE` … with every 20th merchant `SUSPENDED`, about 80 KB, in the format of `perf/MerchantDirectoryStub.java`.

**Configuration.** On both classes: `@BenchmarkMode(Mode.AverageTime)`, `@OutputTimeUnit(TimeUnit.MICROSECONDS)`, `@Warmup(iterations = 5)`, `@Measurement(iterations = 10)`, `@Fork(3)`. The iteration time is left at JMH's default of 10 s; neither warm-up nor forks were reduced.

**Why forks matter:** each fork is a fresh JVM, so one run's JIT decisions and the profile it built up cannot skew the others. The spread between fork means below shows how far a single JVM could mislead.

<!-- BEGIN:steadiness -->
Per benchmark: the mean of each fork, and the largest drift inside any fork between its first three and last three measured iterations. Drift smaller than the error means the iterations had settled when measurement began.

| Benchmark | Fork means | Fork spread | Largest drift in a fork | Error | Settled? |
| :--- | :--- | ---: | ---: | ---: | :--- |
| `LedgerHotPathBenchmark.calculateSettlement (payments=2)` | 0.04368, 0.0443, 0.04433 | 1.5 % | 4.1 % | 2.8 % | **no: drift exceeds the error** |
| `LedgerHotPathBenchmark.calculateSettlement (payments=200)` | 0.2358, 0.2282, 0.2307 | 3.2 % | 18.3 % | 6.9 % | **no: drift exceeds the error** |
| `LedgerHotPathBenchmark.parseMerchantDirectory` | 1083, 1087, 1029 | 5.4 % | 8.7 % | 3.1 % | **no: drift exceeds the error** |
| `LedgerHotPathBenchmark.serialiseSettlementResponse` | 0.37, 0.3859, 0.3764 | 4.2 % | 2.6 % | 2.3 % | **no: drift exceeds the error** |
| `SettlementArithmeticBenchmark.baseline` | 0.0005321, 0.0005915, 0.0005962 | 11.2 % | 15.4 % | 5.0 % | **no: drift exceeds the error** |
| `SettlementArithmeticBenchmark.brokenDiscardsResult` | 0.0005998, 0.0005944, 0.0006797 | 13.6 % | 5.2 % | 4.8 % | **no: drift exceeds the error** |
| `SettlementArithmeticBenchmark.fixedConsumesWithBlackhole` | 0.001216, 0.003399, 0.003499 | 84.4 % | 19.7 % | 29.9 % | yes |
| `SettlementArithmeticBenchmark.fixedReturnsResult` | 0.003855, 0.00364, 0.004161 | 13.4 % | 20.7 % | 9.0 % | **no: drift exceeds the error** |
<!-- END:steadiness -->

## 5. Results
<!-- BEGIN:environment -->
From `results.json`: JMH 1.37, JDK 25.0.4 (Java HotSpot(TM) 64-Bit Server VM 25.0.4+7-LTS-189), machine `Windows 11 10.0 / Intel64 Family 6 Model 142 Stepping 12; GenuineIntel / 8 logical CPUs / 8006 MiB RAM`, 3 forks × 5 warm-up iterations of 10 s + 10 measured iterations of 10 s, 1 thread.

```text
Recorded 2026-10-22T15:05:31.112975700+01:00[Africa/Lagos]
$ java -version
java version "25.0.4" 2026-07-21 LTS
Java(TM) SE Runtime Environment (build 25.0.4+7-LTS-189)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.4+7-LTS-189, mixed mode, sharing)
$ mvnw -v
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
Maven home: C:\Users\PC\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0
Java version: 25.0.4, vendor: Oracle Corporation, runtime: C:\Program Files\Java\jdk-25.0.4
Default locale: en_US, platform encoding: UTF-8
OS name: "windows 11", version: "10.0", arch: "amd64", family: "windows"
Machine: Windows 11 10.0 / Intel64 Family 6 Model 142 Stepping 12; GenuineIntel / 8 logical CPUs / 8006 MiB RAM
Suite wall-clock time: 60 min
```
<!-- END:environment -->

<!-- BEGIN:results -->
Score ± error is JMH's 99.9 % confidence interval over 3 forks × 10 measured iterations (30 samples). Allocation is `gc.alloc.rate.norm` from `-prof gc`, in the same run.

| Benchmark | Time per operation | 99.9 % interval | Allocated per operation |
| :--- | ---: | :--- | ---: |
| `LedgerHotPathBenchmark.calculateSettlement (payments=2)` | 0.0441 ± 0.001224 us/op | [0.04288, 0.04533] | 200 ± 0.0000008597 B/op |
| `LedgerHotPathBenchmark.calculateSettlement (payments=200)` | 0.2316 ± 0.01605 us/op | [0.2155, 0.2476] | 200 ± 0.00001121 B/op |
| `LedgerHotPathBenchmark.parseMerchantDirectory` | 1067 ± 32.87 us/op | [1034, 1099] | 1170000 ± 0.02374 B/op |
| `LedgerHotPathBenchmark.serialiseSettlementResponse` | 0.3774 ± 0.008708 us/op | [0.3687, 0.3861] | 592 ± 0.000006073 B/op |
| `SettlementArithmeticBenchmark.baseline` | 0.0005733 ± 0.0000287 us/op | [0.0005446, 0.000602] | 0.0000004 ± 0.00000002 B/op |
| `SettlementArithmeticBenchmark.brokenDiscardsResult` | 0.0006247 ± 0.00003014 us/op | [0.0005945, 0.0006548] | 0.0000004362 ± 0.00000002128 B/op |
| `SettlementArithmeticBenchmark.fixedConsumesWithBlackhole` | 0.002705 ± 0.0008097 us/op | [0.001895, 0.003514] | 0.00000189 ± 0.0000005652 B/op |
| `SettlementArithmeticBenchmark.fixedReturnsResult` | 0.003886 ± 0.0003502 us/op | [0.003535, 0.004236] | 0.000002713 ± 0.0000002445 B/op |
<!-- END:results -->

## 6. Comparisons
<!-- BEGIN:comparisons -->
A comparison counts only when the two 99.9 % intervals do not overlap; otherwise it is marked **inconclusive**.

| A | B | Intervals | Verdict |
| :--- | :--- | :--- | :--- |
| `brokenDiscardsResult` | `baseline` | [0.0005945, 0.0006548] vs [0.0005446, 0.000602] us/op | **inconclusive** (intervals overlap) |
| `brokenDiscardsResult` | `fixedReturnsResult` | [0.0005945, 0.0006548] vs [0.003535, 0.004236] us/op | different: B takes 6.22× as long as A |
| `fixedReturnsResult` | `fixedConsumesWithBlackhole` | [0.003535, 0.004236] vs [0.001895, 0.003514] us/op | different: A takes 1.437× as long as B |
| `calculateSettlement (payments=2)` | `calculateSettlement (payments=200)` | [0.04288, 0.04533] vs [0.2155, 0.2476] us/op | different: B takes 5.251× as long as A |
| `serialiseSettlementResponse` | `calculateSettlement (payments=2)` | [0.3687, 0.3861] vs [0.04288, 0.04533] us/op | different: A takes 8.558× as long as B |
<!-- END:comparisons -->

## 7. Allocation, connected to the code
*Written from the results above.*

## 8. Conclusions
*Written from the results above.*

## 9. Limits: what this suite does not tell you

1. **Behaviour under concurrency.** Every benchmark runs on one thread. In production, Tomcat (or virtual threads, after SJV-L1) runs hundreds of these calls at once: Jackson's shared caches, the stream pipeline's allocation and the shared `JsonMapper` all behave differently under contention.
   - *Would address it:* the same benchmarks with `@Threads(8)` or `-t 8`, and the SJV-L2 load test with its JFR allocation profile.
2. **Cache effects at production data sizes.** The inputs are small and reused on every invocation: 2 or 200 payments, one 80 KB directory, one response object. They stay in the CPU caches, which real traffic over thousands of merchants would not.
   - *Would address it:* a `@Param` sweep to production sizes (10 000+ payments, a full directory), and inputs drawn from a pool of merchants per invocation instead of a single one.
3. **A harness is not the live request path.** `calculateSettlement` here reads its payments from memory. In the service the same call goes through Spring Data, Hibernate, JDBC and Postgres, which SJV-L2 measured as 37.5 % of all allocation under this one line. Tomcat, the servlet filters, Spring MVC's message converters and the network are not in any benchmark either.
   - *Would address it:* the endpoint measured end to end, with k6 driving it and Flight Recorder attributing time and allocation, as in SJV-L1 and SJV-L2.
4. **One machine, one JVM configuration.** A Windows laptop with power management and turbo boost active, JDK 25.0.4 with default flags. Absolute numbers will differ on a server, and scores move with temperature and power state.
   - *Would address it:* the same suite on the deployment hardware, or in CI with a fixed CPU frequency, comparing ratios rather than absolute times.
5. **One input shape per benchmark.** The JIT sees a single type and data shape, so its compiled code is as specialised as it can be. Production paths that see mixed inputs get less optimised code.
   - *Would address it:* benchmarks fed several shapes in the same fork.

## 10. Evidence

| What | Where |
| :--- | :--- |
| Raw JMH results, final run (scores, 99.9 % intervals, every iteration, `-prof gc` metrics, JDK, machine) | `benchmarks/results.json` |
| Console output of the same run, warm-up iterations included | `benchmarks/results-console.txt` |
| Toolchain and machine | `benchmarks/toolchain.txt` |
| Benchmarks | `benchmarks/src/main/java/javatesa6/ledgersettlement/` |
| Runner | `perf/BenchLab.java` (`java perf/BenchLab.java --report` rewrites these tables from `results.json`) |
