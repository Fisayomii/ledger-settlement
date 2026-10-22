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

The broken benchmark's interval overlaps the empty method's. Returning the result makes the measured time 6.22× the broken figure (0.003886 against 0.0006247 us/op), so the broken number understated the real cost by 83.92 %. The two fixes are inconclusive against each other: their intervals do not overlap, but the forks of each disagree more than the two differ (section 6).
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
| Benchmark | Fork means | Fork spread | First warm-up iteration, each fork (% of score) | Drift in each fork, last 3 vs first 3 measured | Reading |
| :--- | :--- | ---: | :--- | :--- | :--- |
| `LedgerHotPathBenchmark.calculateSettlement (payments=2)` | 0.04368, 0.0443, 0.04433 | 1.5 % | 100 %, 93 %, 93 % | -1.4 %, -4.1 %, -1.2 % | no common trend: iteration-to-iteration noise |
| `LedgerHotPathBenchmark.calculateSettlement (payments=200)` | 0.2358, 0.2282, 0.2307 | 3.2 % | 82 %, 93 %, 92 % | +6.9 %, +18.3 %, -9.9 % | no common trend: iteration-to-iteration noise |
| `LedgerHotPathBenchmark.parseMerchantDirectory` | 1083, 1087, 1029 | 5.4 % | 91 %, 93 %, 91 % | -8.7 %, -1.4 %, -0.9 % | no common trend: iteration-to-iteration noise |
| `LedgerHotPathBenchmark.serialiseSettlementResponse` | 0.37, 0.3859, 0.3764 | 4.2 % | 95 %, 96 %, 96 % | +2.5 %, -2.6 %, +1.1 % | no common trend: iteration-to-iteration noise |
| `SettlementArithmeticBenchmark.baseline` | 0.0005321, 0.0005915, 0.0005962 | 11.2 % | too small for the console's 3 decimals | +15.4 %, -2.5 %, +3.3 % | no common trend: iteration-to-iteration noise |
| `SettlementArithmeticBenchmark.brokenDiscardsResult` | 0.0005998, 0.0005944, 0.0006797 | 13.6 % | too small for the console's 3 decimals | +5.2 %, -1.0 %, +2.8 % | no common trend: iteration-to-iteration noise |
| `SettlementArithmeticBenchmark.fixedConsumesWithBlackhole` | 0.001216, 0.003399, 0.003499 | 84.4 % | too small for the console's 3 decimals | +1.4 %, +19.7 %, -10.0 % | no common trend: iteration-to-iteration noise |
| `SettlementArithmeticBenchmark.fixedReturnsResult` | 0.003855, 0.00364, 0.004161 | 13.4 % | too small for the console's 3 decimals | -20.7 %, -9.4 %, +13.4 % | no common trend: iteration-to-iteration noise |
<!-- END:steadiness -->

**Reading this table.**
- **The warm-up was long enough.**
  - Where the console prints enough digits, the very first 10-second warm-up iteration of every fork already runs at 82–100 % of the final score. The JIT had compiled these methods within that first iteration.
  - Inside the measured iterations, no benchmark gets faster in every fork. The drift changes sign from fork to fork (for example `calculateSettlement (payments=200)`: +6.9 %, +18.3 %, −9.9 %), which is iteration-to-iteration noise on a laptop whose clock speed varies, not unfinished warm-up.
- **Forks matter, and this run shows why.** `fixedConsumesWithBlackhole` ran at 1.2 ns in fork 1 and at 3.4–3.5 ns in forks 2 and 3. The same code got different machine code in different JVMs. With `@Fork(1)`, whichever JVM happened to start would have decided the answer.
- **Correcting my own first reading.** The first version of this table judged "settled" by comparing each fork's drift with JMH's error bar. That error bar is the uncertainty of the mean of all 30 samples, far tighter than the noise of three single iterations, so it flagged 7 of 8 benchmarks as unsettled. That conclusion was noise, and the table now tests what matters: the first warm-up iteration, and whether all forks drift the same way.

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
Five comparisons were made, and two are inconclusive.

<!-- BEGIN:comparisons -->
A comparison counts only when the two 99.9 % intervals do not overlap **and** the difference between the two scores is larger than the spread between fork means of either benchmark. JMH's interval treats all 30 iterations as independent; when forks of the same benchmark disagree more than the two benchmarks do, the difference is between JVMs, not between the methods. Otherwise the comparison is marked **inconclusive**.

| A | B | Intervals | Fork-mean spread (A, B) | Verdict |
| :--- | :--- | :--- | :--- | :--- |
| `brokenDiscardsResult` | `baseline` | [0.0005945, 0.0006548] vs [0.0005446, 0.000602] us/op | 0.00008525, 0.00006403 | **inconclusive** (intervals overlap) |
| `brokenDiscardsResult` | `fixedReturnsResult` | [0.0005945, 0.0006548] vs [0.003535, 0.004236] us/op | 0.00008525, 0.000521 | different: B takes 6.22× as long as A |
| `fixedReturnsResult` | `fixedConsumesWithBlackhole` | [0.003535, 0.004236] vs [0.001895, 0.003514] us/op | 0.000521, 0.002282 | **inconclusive** (forks disagree more than the two benchmarks differ) |
| `calculateSettlement (payments=2)` | `calculateSettlement (payments=200)` | [0.04288, 0.04533] vs [0.2155, 0.2476] us/op | 0.0006475, 0.007523 | different: B takes 5.251× as long as A |
| `serialiseSettlementResponse` | `calculateSettlement (payments=2)` | [0.3687, 0.3861] vs [0.04288, 0.04533] us/op | 0.01586, 0.0006475 | different: A takes 8.558× as long as B |
<!-- END:comparisons -->

The first version of this rule looked only at interval overlap, and it called the return fix and the Blackhole fix "different": the return fix 1.44× slower. The intervals miss each other by 0.00002 µs. But the Blackhole benchmark's own forks differ by 0.0023 µs, more than the 0.0012 µs between the two fixes. That 1.44× was a difference between JVMs, so the rule now requires the gap to exceed the fork spread, and the comparison is reported as inconclusive.

## 7. Allocation, connected to the code
Bytes per operation are from `-prof gc` (`gc.alloc.rate.norm`) in the same run. The per-object figures below are estimates from object sizes on a 64-bit JVM with compressed references: a `String` is 24 bytes plus its `byte[]` of 16 bytes plus one byte per character, rounded up to 8.

- **`parseMerchantDirectory`: 1 170 000 B per call, about 234 B per merchant line.** That matches what `HttpMerchantDirectoryClient.parseTable` creates for each line:
  - `body.lines()` creates the line as a new `String` (about 56 B);
  - `line.substring(0, eq)` creates the id (about 48 B);
  - `line.substring(eq + 1)` creates the status (48–56 B);
  - `table.put` adds a `LinkedHashMap` entry (40 B);
  - `Map.copyOf(table)` copies every entry into a second, immutable table.

  `strip()` allocates nothing here, because the lines have no surrounding spaces. The two `substring` calls are the largest share. They are what a parser of `id=status` lines has to produce, so the allocation is proportional to the directory, as expected.
- **`calculateSettlement`: 200 B per call, the same for 2 and for 200 payments.**
  - The allocation does not grow with the payments, so summing them allocates nothing per payment.
  - The 200 B is the fixed cost of `payments.stream().mapToLong(PaymentEntity::getAmountMinor).sum()` in `SettlementService`: the stream's head, its `mapToLong` stage, the terminal reduce operation and its sink.
  - A further 24 B or so is the argument array that the benchmark's in-memory repository (a `java.lang.reflect.Proxy`) builds for each call, which the real repository would not.
- **`serialiseSettlementResponse`: 592 B per call.** About 96 B of it is the result itself, the 49-character JSON `String`. The rest is what `writeValueAsString` builds for every call: a generator and a string writer with its buffer. In the live service, Spring writes straight to the response stream rather than through a `String`, so the real figure is likely lower (see the limits).
- **The fee arithmetic: about 0 B.** `owedMinor` works on two `long`s and allocates nothing. The 0.000002 B/op shown for the fixes is JMH's own bookkeeping spread over billions of operations.

## 8. Conclusions
1. **The broken benchmark measured nothing.**
   - `brokenDiscardsResult` (0.000625 ± 0.000030 µs) cannot be told apart from an empty method (0.000573 ± 0.000029 µs).
   - Returning the result shows the real cost: 0.00389 ± 0.00035 µs, 6.2× the broken figure.
   - So the broken number understated the fee arithmetic by 84 %. It would have reported a real computation, a multiply, a divide and a subtract, as free.
2. **Return and Blackhole are interchangeable here.** Their difference is inconclusive (section 6). Either fix is correct, and returning is simpler when there is one result.
3. **The settlement calculation is cheap, and its cost grows with the number of payments, not its allocation.**
   - 0.0441 ± 0.0012 µs for MR-4471's 2 payments, and 0.232 ± 0.016 µs for 200: about 0.95 ns per extra payment on top of about 0.042 µs fixed.
   - At SJV-L2's 300 requests per second, even the 200-payment case is about 70 µs of CPU per second.
   - Faster arithmetic or a different number type would not move the service. SJV-L2 found the cost under this call in Hibernate and the database, which is where its fix went.
4. **Serialising the response costs 8.6× the calculation.** 0.377 ± 0.009 µs and 592 B, against 0.044 µs for MR-4471. That is still under half a microsecond per response, so neither is worth optimising at today's traffic.
5. **Parsing the directory is the largest single cost measured:** 1 067 ± 33 µs and 1.17 MB for 5 000 merchants, about 0.21 µs and 234 B per merchant.
   - It runs once at start-up, next to a merchant-directory call that takes about 3 s (SJV-L1), so it adds about 0.04 % to start-up.
   - It would matter only if the table were refreshed often or the directory grew a hundredfold.
6. **How far to trust these numbers.** On this laptop the real methods' forks agree within 1.5–5.4 %. The sub-nanosecond benchmarks vary 11–14 % between forks, and one varies 84 %. Differences of a few per cent between real methods should not be read from this suite. Every claim above is a factor of 5 or more, or is stated as inconclusive.

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
