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
<!-- END:steadiness -->

## 5. Results
<!-- BEGIN:environment -->
<!-- END:environment -->

<!-- BEGIN:results -->
<!-- END:results -->

## 6. Comparisons
<!-- BEGIN:comparisons -->
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
