# Ledger settlement service

A Spring Boot service that records payments (`POST /payments`) and reports what a merchant is owed (`GET /payments/settlement?merchantId=…`). The build is one Maven project with two modules:

| Module | What it builds |
| :--- | :--- |
| `ledger-service/` | The service: `ledger-service/target/ledger-settlement.jar` |
| `benchmarks/` | The JMH suite: `benchmarks/target/benchmarks.jar` |

Requirements: JDK 25. The Maven wrapper (`./mvnw`, or `mvnw.cmd` on Windows) downloads Maven itself.

## Build and test

```text
./mvnw -B verify
```

## Run the service

It needs Postgres on `localhost:5433` (database `ledger`, user `postgres`, password `password`) and the merchant directory on port 9090. Start the directory with `java perf/MerchantDirectoryStub.java`. Then:

```text
java -jar ledger-service/target/ledger-settlement.jar
```

## Run the benchmarks

One command, from the repository root. No database is needed.

```text
java perf/BenchLab.java
```

- **Duration:** about 65 minutes for the full suite: 8 benchmarks, 3 forks each, 5 warm-up and 10 measured iterations of 10 s.
- **What it does:**
  1. Builds both modules.
  2. Runs `java -jar target/benchmarks.jar -prof gc -rf json -rff results.json` in `benchmarks/`.
  3. Writes the result tables into `docs/benchmarks.md`.
- **Outputs:** `benchmarks/results.json` (raw JMH results) and `benchmarks/results-console.txt`.
- **Before you start:** keep the machine plugged in and otherwise idle. On a laptop, set screen and sleep to Never; the runner also asks Windows to stay awake.

To run JMH by hand after `./mvnw -B package`:

```text
cd benchmarks
java -jar target/benchmarks.jar -prof gc -rf json -rff results.json
```

Results and their analysis: `docs/benchmarks.md`.
