# SJV-L1 — Virtual threads and the MerchantLookup pinning incident

Ledger settlement service · branch `feature/virtual-threads` · PR #1 into `master`. Every figure was written by `perf/ThreadsLab.java` from the raw k6 and JFR output in `perf/results/sjv-l1-raw.zip`.

**Summary.** `GET /payments/settlement` moved from Tomcat's 200-thread platform pool to one virtual thread per request. A planted defect then loaded the merchant table over HTTP inside a class initializer. Flight Recorder showed the first request blocked in `MerchantLookup.<clinit>`, pinning its carrier for about 3 s while every request needing the class waited, also pinned. The fix loads the table once, at start-up and outside any class initializer; a concurrency test proves it loads exactly once and never pins.

## 1. Stages and toolchain
Four stages, each one commit apart and tagged: `lab1-baseline` (`master`, platform threads) → `lab1-migrated` (virtual threads) → `lab1-pinned` (defect planted) → `lab1-fixed`.
Every measurement used this JDK, launched with `java -jar` on the extracted application jar (section 2) and no extra JVM flags. The first submission ran from the IDE, which adds `-XX:TieredStopAtLevel=1`, so none of its figures are reused.

<!-- BEGIN:toolchain -->
<!-- END:toolchain -->

## 2. Load profile and measurement discipline
- **Endpoint:** `GET /payments/settlement?merchantId=MR-4471`, the main read path. It runs one Postgres query plus a merchant-status check and returns `{"merchantId":"MR-4471","amountOwedMinor":…}`.
- **Profile:** `perf/threads-load.js` (k6 `constant-vus`), 200 concurrent users for 60 s with no think time. The same unchanged file was used for every run.
- **Unmeasured setup:** one `POST /payments` seeds `PAY-90312` (128 450 minor units, GBP; a fixed id, so the table never grows). Then 20 sequential requests for a merchant with no payments load the web and JPA classes before the 200 users start. They return 404 before `MerchantLookup` is reached, so the defect is still hit by the first real request.
- **Correctness:** every load response is checked for HTTP 200 and a whole, positive `amountOwedMinor`, and every amount returned is recorded (k6 Trend `settlement_amount_minor`). Its lowest and highest value per stage are in section 5.
- **Discipline:** a cold start before every run of every stage, with Flight Recorder (`settings=profile`) on in every run so its overhead is equal. Three runs per stage, interleaved (baseline 1, migrated 1, pinned 1, fixed 1, baseline 2 …) so drift hits every stage alike. One commit between neighbouring stages. k6 and the service shared one machine; nothing else heavy ran. The runner keeps Windows awake, and any run where k6 needs over 100 s for the 60 s profile (a stalled machine) is set aside, kept in the raw zip and repeated.
- **Launch:** each stage's jar is extracted with `java -Djarmode=tools -jar app.jar extract` and run as `java -jar …/extracted/ledger-settlement.jar`, the layout Spring Boot recommends for production. The first full run of this resubmission launched the fat jar directly. Under load, lazily loaded classes then queued on Spring Boot's nested-jar class loader (`NestedJarFile`, `LaunchedClassLoader`) inside a native class-loading frame, which pins (`Freeze or preempt failed`, 1–3 events per fixed run, none involving MerchantLookup). That run also took 13–70 s to start and varied by up to 103 % in throughput between runs, so its figures are not used.
- **Merchant directory:** `perf/MerchantDirectoryStub.java` on port 9090 answers `GET /merchants` after 3 s, its slow cold response.
- **Reproduce:** with Postgres on `localhost:5433` and k6 on the PATH, run `java perf/ThreadsLab.java` from the repository root (about 20 min).

## 3. Request pool before the migration
No executor of the service's own served the endpoint. It ran on Tomcat's platform pool with Spring Boot's defaults, written out in `src/main/resources/application.yaml` on `master`:

| Pool | Setting (file:line on `master`) | Value |
| :--- | :--- | ---: |
| Tomcat request threads, max (Tomcat's core = max) / kept idle | `server.tomcat.threads.max` (:10) / `min-spare` (:11) | 200 / 10 |
| Queue once every thread is busy | `server.tomcat.accept-count` (:12) | 100 |
| Database connections (HikariCP) | `spring.datasource.hikari.maximum-pool-size` (:20) | 10 |
| `applicationTaskExecutor` (`@Async`, not on this path) | Boot's default `ThreadPoolTaskExecutor` | core 8, max and queue unbounded |

**How it bounded the baseline.** 200 users with no think time never put more than 200 requests in flight, so every request got a Tomcat thread and the accept queue stayed empty. The tighter limit sat behind the threads: each query needs one of 10 Hikari connections, so up to 190 platform threads at a time were blocked waiting for one. Virtual threads remove the 200-thread ceiling and make that waiting cheap, but they add no database connections.

## 4. Migration to virtual threads
`spring.threads.virtual.enabled: true` makes Tomcat run each request on its own virtual thread. `ThreadConfig.applicationTaskExecutor()` is rebuilt as `new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())`. The old `server.tomcat.threads.max` and `min-spare` settings are removed, not left alongside. As proof, the first settlement request logs `Settlement requests run on thread … (isVirtual=…)`; the last row of the table below comes from that line in every run's log.

## 5. Results
<!-- BEGIN:results-table -->
<!-- END:results-table -->

<!-- BEGIN:verdict -->
<!-- END:verdict -->

## 6. The planted defect
Commit `lab1-pinned` moved the table into a static initializer, `static { TABLE = new HttpMerchantDirectoryClient(URI.create(url)).fetchTable(); }`, so a real HTTP call to the merchant directory runs inside `<clinit>`. The first settlement request runs `<clinit>` and blocks in `HttpClient.send` until the directory answers. The service was restarted cold before every run, so every pinned run starts with the class uninitialised.

**Why a class initializer pins while `synchronized` no longer does.** JEP 491 (Java 24) lets a virtual thread unmount while it waits for, or holds, a monitor, because the JVM now tracks monitor owners per virtual thread. `<clinit>`, however, is invoked from native VM code, and a virtual thread cannot unmount with a native VM frame on its stack. Threads waiting for the class to be initialised also wait inside the VM, so the initializer and every waiter keep their carriers until the HTTP call returns.

## 7. Pinning proved with Flight Recorder
`pinned.jfr` and `fixed.jfr` were produced by these commands (every other run's commands are in `commands.txt` in the raw zip):

<!-- BEGIN:commands -->
<!-- END:commands -->

To check a file, use `jfr summary pinned.jfr`, `jfr print --events jdk.VirtualThreadPinned pinned.jfr`, or JDK Mission Control → Event Browser → `jdk.VirtualThreadPinned`. The old `-Djdk.tracePinnedThreads` property no longer exists (JDK 24 removed it with JEP 491), so this event is the way to see pinning.

<!-- BEGIN:jfr-counts -->
<!-- END:jfr-counts -->

## 8. One pinned event, analysed
<!-- BEGIN:pinned-event -->
<!-- END:pinned-event -->

- **Pinning reason:** `VM call to javatesa6.ledgersettlement.MerchantLookup.<clinit> on stack`.
- **Frame that cannot unmount:** `javatesa6.ledgersettlement.MerchantLookup.<clinit>`, the class initializer of `MerchantLookup`, entered from `SettlementService.calculateSettlement`.
- **Blocking call:** `HttpMerchantDirectoryClient.fetchTable()` → `HttpClient.send`, waiting in `CompletableFuture.get` → `LockSupport.park` for the directory's answer (about 3 s).
- **Waiters:** events with reason `Waited for initialization of javatesa6.ledgersettlement.MerchantLookup by another thread` are requests queued behind that initializer, each holding a carrier.

**Pre-Java 24 advice.** Before Java 24 a virtual thread blocking while holding a `synchronized` monitor pinned its carrier, so the advice was to replace `synchronized` with `ReentrantLock`. JEP 491 removed that cause in Java 24; this pin comes from a class initializer, not a monitor, so `ReentrantLock` would change nothing here.

## 9. The fix and its test
- **`MerchantLookup`** is a plain object with no I/O in any class initializer. `table()` fetches at most once: the first caller makes the downstream call and publishes the result through a `CompletableFuture`. Concurrent callers park on that future, which unmounts a virtual thread, so nothing pins. A failed fetch is not cached, so the next call retries.
- **`MerchantConfig`** calls `table()` during context start-up, on the main platform thread, before Tomcat accepts connections. The load is always eager; the once-only path is a safety net.
- **Results unchanged:** `gross - gross × 310 / 10 000` on `long` minor units. The `amountOwedMinor` row in section 5 shows the value returned in every response of every stage.
- **`MerchantLookupConcurrencyTest`** releases 500 virtual threads together with a `CountDownLatch` against a directory that takes 300 ms. It asserts that all of them received the same complete table instance, that the directory was called exactly once, and that a `RecordingStream` with a 0 ms threshold saw no `jdk.VirtualThreadPinned` event. A second test covers retry after a failed load. Both run in `./mvnw verify` (`verify.log` in the raw zip).

## 10. Verified fix, and a correction
`fixed.jfr` is run 1 of the fixed stage: the same cold start, `jcmd … JFR.start name=pin settings=profile` command and load as `pinned.jfr`; its counts are in section 7.

**Correction to the first submission.** Its `fixed.jfr` lasted 38 s and held 218 pinned events, more than its `pinned.jfr`, and the document did not say so. Re-reading it with `jfr print --events jdk.VirtualThreadPinned` shows that none involved `MerchantLookup`, so that fix had worked. All 218 were Tomcat, Hibernate and JDK classes being initialised by many threads at once in the first 4 s after a cold start (for example `Waited for initialization of org.hibernate.grammars.hql.HqlParser by another thread`). The unmeasured setup requests in section 2 now load those classes first.

## 11. Risk and rollback
- **Risk 1 — start-up depends on the merchant directory.** The table loads during start-up (about 3 s; "Start-up until serving" above). If the directory is down, the context fails and the new pod never turns ready rather than serving failing requests. It shows as a deploy whose pods stay unready, with `Merchant directory unreachable at …/merchants` in the start-up log.
- **Risk 2 — more concurrency against 10 database connections.** Without the 200-thread cap, a traffic spike queues on the Hikari pool instead of in Tomcat. It shows as rising `hikaricp_connections_pending` and connection-timeout errors before CPU rises.
- **Rollback, configuration only:** set `spring.threads.virtual.enabled: false` and restore `server.tomcat.threads.max: 200` and `min-spare: 10`. **Rollback, everything:** `git revert -m 1 <merge commit of PR #1>`.

## 12. Evidence index
- **Recordings:** `pinned.jfr`, `fixed.jfr` (repository root). **Load script, directory stub, runner:** `perf/threads-load.js`, `perf/MerchantDirectoryStub.java`, `perf/ThreadsLab.java`. **Test:** `src/test/java/javatesa6/ledgersettlement/MerchantLookupConcurrencyTest.java`.
- **Raw outputs:** `perf/results/sjv-l1-raw.zip`: `toolchain.txt`, `verify.log`, `results.csv`, `per-run.md`, `commands.txt`, plus every run's k6 console output and `--summary-export` JSON, service log, `jfr summary` and `jfr print` of each pinned event.
