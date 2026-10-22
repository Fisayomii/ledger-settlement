# SJV-L1 — Virtual threads and the MerchantLookup pinning incident

Ledger settlement service · branch `feature/virtual-threads` · PR #1 into `master`. Every figure was written by `perf/ThreadsLab.java` from the raw k6 and JFR output in `perf/results/sjv-l1-raw.zip`.

**Summary.** `GET /payments/settlement` moved from Tomcat's 200-thread platform pool to one virtual thread per request. A planted defect then loaded the merchant table over HTTP inside a class initializer. Flight Recorder showed the first request blocked in `MerchantLookup.<clinit>`, pinning its carrier for about 3 s while every request needing the class waited, also pinned. The fix loads the table once, at start-up and outside any class initializer; a concurrency test proves it loads exactly once and never pins.

## 1. Stages and toolchain
Four stages, each one commit apart and tagged: `lab1-baseline` (`master`, platform threads) → `lab1-migrated` (virtual threads) → `lab1-pinned` (defect planted) → `lab1-fixed`.
Every measurement used this JDK, launched with `java -jar` on the extracted application jar (section 2) and no extra JVM flags. The first submission ran from the IDE, which adds `-XX:TieredStopAtLevel=1`, so none of its figures are reused.

<!-- BEGIN:toolchain -->
```text
$ java -version
java version "25.0.4" 2026-07-21 LTS
Java(TM) SE Runtime Environment (build 25.0.4+7-LTS-189)
$ mvn -v
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
$ mvnw -v (the wrapper used for every build)
Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5)
$ k6 version
k6.exe v2.2.0 (commit/00a9a1b7f5, go1.26.5, windows/amd64)
Machine: Windows 11 10.0 (amd64), 8 logical CPUs, 8006 MiB RAM
Service launched with: java -jar on the extracted application jar (java -Djarmode=tools -jar app.jar extract), no IDE, no extra JVM flags; k6 on the same machine.
```
<!-- END:toolchain -->

## 2. Load profile and measurement discipline
- **Endpoint:** `GET /payments/settlement?merchantId=MR-4471`, the main read path. It runs one Postgres query plus a merchant-status check and returns `{"merchantId":"MR-4471","amountOwedMinor":…}`.
- **Profile:** `perf/threads-load.js` (k6 `constant-vus`), 200 concurrent users for 60 s with no think time. The same unchanged file was used for every run.
- **Unmeasured setup:** one `POST /payments` seeds `PAY-90312` (128 450 minor units, GBP; a fixed id, so the table never grows). Then 20 sequential requests for a merchant with no payments load the web and JPA classes before the 200 users start. They return 404 before `MerchantLookup` is reached, so the defect is still hit by the first real request.
- **Correctness:** every load response is checked for HTTP 200 and a whole, positive `amountOwedMinor`, and every amount returned is recorded (k6 Trend `settlement_amount_minor`). Its lowest and highest value per stage are in section 5. Every failed check in every run was a refused TCP connection (k6: `connectex: No connection could be made because the target machine actively refused it`): 9 103 of 1 126 820 requests, none with a wrong amount. See section 5.
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
Mean of 3 runs per stage, lowest and highest run in brackets; every run is in `per-run.md` in the raw zip. No run stalled (a run is set aside and repeated if k6 needs over 100 s for the 60 s profile).

| Metric | Baseline (platform threads) | Migrated (virtual threads) | Pinned (defect planted) | Fixed |
| :--- | ---: | ---: | ---: | ---: |
| Throughput (req/s) | 1374.7 (1027.9–1644.3) | 1419.1 (1039.5–1835.9) | 1560.6 (1017.3–1944.2) | 1831.1 (1780.2–1896.7) |
| p50 latency (ms) | 112.70 (82.59–147.03) | 122.14 (81.05–161.39) | 112.20 (78.84–174.26) | 83.48 (80.31–88.28) |
| p95 latency (ms) | 379.10 (310.49–510.49) | 294.94 (245.30–355.86) | 267.77 (195.13–375.05) | 215.14 (196.00–235.41) |
| p99 latency (ms) | 616.99 (510.82–815.50) | 481.73 (425.02–556.94) | 487.48 (375.09–614.17) | 356.64 (309.92–423.01) |
| Correct responses (% of checks) | 99.95 (99.89–100.00) | 99.05 (98.02–99.93) | 99.28 (98.96–99.67) | 98.52 (98.37–98.68) |
| amountOwedMinor returned for MR-4471 (lowest–highest over every response) | 125438–125438 | 125438–125438 | 125438–125438 | 125438–125438 |
| Start-up until serving (s) | 14.9 (13.5–17.6) | 17.8 (14.2–21.5) | 13.2 (11.1–15.1) | 15.7 (13.8–17.3) |
| jdk.VirtualThreadPinned events (count) | 0 (0–0) | 0 (0–0) | 8 (8–8) | 0 (0–0) |
| Run-to-run spread: throughput / p95 / p99 (% of mean) | 44.8 % / 52.8 % / 49.4 % | 56.1 % / 37.5 % / 27.4 % | 59.4 % / 67.2 % / 49.0 % | 6.4 % / 18.3 % / 31.7 % |
| Request thread, from the service log of every run | platform | virtual | virtual | virtual |

Example log lines, run 1: baseline `Settlement requests run on thread Thread[#48,http-nio-8080-exec-1,5,main] (isVirtual=false)`; migrated `Settlement requests run on thread VirtualThread[#53,tomcat-handler-0]/runnable@ForkJoinPool-1-worker-1 (isVirtual=true)`.
<!-- END:results-table -->

<!-- BEGIN:verdict -->
Rule: a difference counts as improved or worse only when the gap between the two means is larger than the run-to-run spread (highest minus lowest run) of either stage; otherwise it is flat.

| Comparison | Throughput | p50 | p95 | p99 |
| :--- | :--- | :--- | :--- | :--- |
| baseline → migrated | +3.2 % (flat) | +8.4 % (flat) | -22.2 % (flat) | -21.9 % (flat) |
| pinned → fixed | +17.3 % (flat) | -25.6 % (flat) | -19.7 % (flat) | -26.8 % (flat) |

**Verdict for the migration (baseline → migrated), judged on throughput: flat.**
<!-- END:verdict -->

**Reading the results.**
- **Migration flat.** That is what section 3 predicts: in both stages every query still needs one of 10 Hikari connections, so removing the 200-thread ceiling adds no database capacity. p95 and p99 fell about 22 %, but within the run-to-run spread, so no latency gain is claimed.
- **What the defect costs.** In all three pinned runs the 8 pinned events sat on 8 different carriers: one per logical CPU, i.e. every carrier the virtual-thread scheduler has. Each was pinned for 3.6–3.9 s. While the first request ran `MerchantLookup.<clinit>`, every virtual thread in the service was stalled, not just that request. Spread over about 100 000 requests in 60 s, one cold-start freeze barely moves the averages, so the throughput and latency tables do not show it; the JFR events do.
- **Fixed is the steadiest stage.** Its runs are within 6.4 % on throughput, against 45–59 % for the other stages; on a shared laptop the larger spreads are machine noise, and every pinned→fixed difference is within them, so none is claimed.
- **Refused connections.** Failed requests were 0.05 % in baseline and 0.65–1.48 % in the virtual-thread stages, all refused connections; the service logged no error. This is consistent with Tomcat's defaults (each keep-alive connection closed after 100 requests, accept queue of 100) and Windows refusing a connection outright when that queue is full. It was not investigated further here; see risk 3.

## 6. The planted defect
Commit `lab1-pinned` moved the table into a static initializer, `static { TABLE = new HttpMerchantDirectoryClient(URI.create(url)).fetchTable(); }`, so a real HTTP call to the merchant directory runs inside `<clinit>`. The first settlement request runs `<clinit>` and blocks in `HttpClient.send` until the directory answers. The service was restarted cold before every run, so every pinned run starts with the class uninitialised.

**Why a class initializer pins while `synchronized` no longer does.** JEP 491 (Java 24) lets a virtual thread unmount while it waits for, or holds, a monitor, because the JVM now tracks monitor owners per virtual thread. `<clinit>`, however, is invoked from native VM code, and a virtual thread cannot unmount with a native VM frame on its stack. Threads waiting for the class to be initialised also wait inside the VM, so the initializer and every waiter keep their carriers until the HTTP call returns.

## 7. Pinning proved with Flight Recorder
`pinned.jfr` and `fixed.jfr` were produced by these commands (every other run's commands are in `commands.txt` in the raw zip):

<!-- BEGIN:commands -->
```text
pinned-1: C:\Program Files\Java\jdk-25.0.4\bin\java.exe -Dledger.merchant-directory.url=http://localhost:9090 -jar target\sjv-l1\worktrees\pinned\target\extracted\ledger-settlement.jar
pinned-1: jcmd 27784 JFR.start name=pin settings=profile filename=pinned.jfr
pinned-1: k6 run --summary-export target/sjv-l1/runs/pinned-1.k6-summary.json perf/threads-load.js
pinned-1: jcmd 27784 JFR.stop name=pin
fixed-1: C:\Program Files\Java\jdk-25.0.4\bin\java.exe -Dledger.merchant-directory.url=http://localhost:9090 -jar target\sjv-l1\worktrees\fixed\target\extracted\ledger-settlement.jar
fixed-1: jcmd 25188 JFR.start name=pin settings=profile filename=fixed.jfr
fixed-1: k6 run --summary-export target/sjv-l1/runs/fixed-1.k6-summary.json perf/threads-load.js
fixed-1: jcmd 25188 JFR.stop name=pin
```
<!-- END:commands -->

To check a file, use `jfr summary pinned.jfr`, `jfr print --events jdk.VirtualThreadPinned pinned.jfr`, or JDK Mission Control → Event Browser → `jdk.VirtualThreadPinned`. The old `-Djdk.tracePinnedThreads` property no longer exists (JDK 24 removed it with JEP 491), so this event is the way to see pinning.

<!-- BEGIN:jfr-counts -->
**pinned.jfr**: 8 `jdk.VirtualThreadPinned` events, 8 involving MerchantLookup.

| Pinning reason | Events |
| :--- | ---: |
| Waited for initialization of javatesa6.ledgersettlement.MerchantLookup by another thread | 7 |
| VM call to javatesa6.ledgersettlement.MerchantLookup.<clinit> on stack | 1 |


**fixed.jfr**: 0 `jdk.VirtualThreadPinned` events, 0 involving MerchantLookup.
<!-- END:jfr-counts -->

## 8. One pinned event, analysed
<!-- BEGIN:pinned-event -->
From `jfr print --stack-depth 64 --events jdk.VirtualThreadPinned pinned.jfr` (pinned run 1); the recording holds 8 pinned events, 8 of them involving MerchantLookup.

```text
jdk.VirtualThreadPinned {
  startTime = 18:47:56.695 (2026-10-21)
  duration = 3.64 s
  blockingOperation = "LockSupport.park"
  pinnedReason = "VM call to javatesa6.ledgersettlement.MerchantLookup.<clinit> on stack"
  carrierThread = "ForkJoinPool-1-worker-3" (javaThreadId = 84)
  eventThread = "tomcat-handler-22" (javaThreadId = 81, virtual)
  stackTrace = [
    java.lang.VirtualThread.parkOnCarrierThread(boolean, long) line: 830
    java.lang.VirtualThread.park() line: 755
    java.lang.System$1.parkVirtualThread() line: 2285
    java.util.concurrent.locks.LockSupport.park(Object) line: 221
    java.util.concurrent.CompletableFuture$Signaller.block() line: 1885
    java.util.concurrent.ForkJoinPool.unmanagedBlock(ForkJoinPool$ManagedBlocker) line: 4364
    java.util.concurrent.ForkJoinPool.managedBlock(ForkJoinPool$ManagedBlocker) line: 4310
    java.util.concurrent.CompletableFuture.waitingGet(boolean) line: 1919
    java.util.concurrent.CompletableFuture.get() line: 2093
    jdk.internal.net.http.HttpClientImpl.send(HttpRequest, HttpResponse$BodyHandler) line: 902
    jdk.internal.net.http.HttpClientFacade.send(HttpRequest, HttpResponse$BodyHandler) line: 133
    javatesa6.ledgersettlement.HttpMerchantDirectoryClient.fetchTable() line: 38
    javatesa6.ledgersettlement.MerchantLookup.<clinit>() line: 17
    javatesa6.ledgersettlement.SettlementService.calculateSettlement(String) line: 30
    jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Object, Object[]) line: 104
    java.lang.reflect.Method.invoke(Object, Object[]) line: 565
    org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(Object, Method, Object[]) line: 359
    org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(Object, Method, Object[], MethodProxy) line: 715
    javatesa6.ledgersettlement.SettlementService$$SpringCGLIB$$0.calculateSettlement(String)
    javatesa6.ledgersettlement.PaymentController.getSettlement(String) line: 50
    jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Object, Object[]) line: 104
    java.lang.reflect.Method.invoke(Object, Object[]) line: 565
    org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(Object[]) line: 252
    org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(NativeWebRequest, ModelAndViewContainer, Object[]) line: 184
    org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletWebRequest, ModelAndViewContainer, Object[]) line: 117
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(HttpServletRequest, HttpServletResponse, HandlerMethod) line: 934
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(HttpServletRequest, HttpServletResponse, HandlerMethod) line: 853
    org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(HttpServletRequest, HttpServletResponse, Object) line: 86
    org.springframework.web.servlet.DispatcherServlet.doDispatch(HttpServletRequest, HttpServletResponse) line: 963
    org.springframework.web.servlet.DispatcherServlet.doService(HttpServletRequest, HttpServletResponse) line: 866
    org.springframework.web.servlet.FrameworkServlet.processRequest(HttpServletRequest, HttpServletResponse) line: 1000
    org.springframework.web.servlet.FrameworkServlet.doGet(HttpServletRequest, HttpServletResponse) line: 892
    jakarta.servlet.http.HttpServlet.service(HttpServletRequest, HttpServletResponse) line: 622
    org.springframework.web.servlet.FrameworkServlet.service(HttpServletRequest, HttpServletResponse) line: 874
    jakarta.servlet.http.HttpServlet.service(ServletRequest, ServletResponse) line: 710
    org.apache.catalina.core.ApplicationFilterChain.doFilter(ServletRequest, ServletResponse) line: 132
    org.apache.tomcat.websocket.server.WsFilter.doFilter(ServletRequest, ServletResponse, FilterChain) line: 59
    org.apache.catalina.core.ApplicationFilterChain.doFilter(ServletRequest, ServletResponse) line: 111
    org.springframework.web.filter.RequestContextFilter.doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain) line: 100
    org.springframework.web.filter.OncePerRequestFilter.doFilter(ServletRequest, ServletResponse, FilterChain) line: 116
    org.apache.catalina.core.ApplicationFilterChain.doFilter(ServletRequest, ServletResponse) line: 111
    org.springframework.web.filter.FormContentFilter.doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain) line: 93
    org.springframework.web.filter.OncePerRequestFilter.doFilter(ServletRequest, ServletResponse, FilterChain) line: 116
    org.apache.catalina.core.ApplicationFilterChain.doFilter(ServletRequest, ServletResponse) line: 111
    org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain) line: 199
    org.springframework.web.filter.OncePerRequestFilter.doFilter(ServletRequest, ServletResponse, FilterChain) line: 116
    org.apache.catalina.core.ApplicationFilterChain.doFilter(ServletRequest, ServletResponse) line: 111
    org.apache.catalina.core.StandardWrapperValve.invoke(Request, Response) line: 165
    org.apache.catalina.core.StandardContextValve.invoke(Request, Response) line: 77
    org.apache.catalina.authenticator.AuthenticatorBase.invoke(Request, Response) line: 535
    org.apache.catalina.core.StandardHostValve.invoke(Request, Response) line: 115
    org.apache.catalina.valves.ErrorReportValve.invoke(Request, Response) line: 86
    org.apache.catalina.core.StandardEngineValve.invoke(Request, Response) line: 71
    org.apache.catalina.connector.CoyoteAdapter.service(Request, Response) line: 347
    org.apache.coyote.http11.Http11Processor.service(SocketWrapperBase) line: 406
    ...
  ]
}
```
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
`fixed.jfr` is run 1 of the fixed stage: the same cold start, `jcmd … JFR.start name=pin settings=profile` command and load as `pinned.jfr`. It holds no `jdk.VirtualThreadPinned` event, and neither did fixed runs 2 and 3, while every pinned run held 8 (section 5).

**Correction to the first submission.** Its `fixed.jfr` lasted 38 s and held 218 pinned events, more than its `pinned.jfr`, and the document did not say so. Re-reading it with `jfr print --events jdk.VirtualThreadPinned` shows that none involved `MerchantLookup`, so that fix had worked. All 218 were Tomcat, Hibernate and JDK classes being initialised by many threads at once in the first 4 s after a cold start (for example `Waited for initialization of org.hibernate.grammars.hql.HqlParser by another thread`). The unmeasured setup requests in section 2 now load those classes first.

## 11. Risk and rollback
- **Risk 1 — start-up depends on the merchant directory.** The table loads during start-up (about 3 s; "Start-up until serving" above). If the directory is down, the context fails and the new pod never turns ready rather than serving failing requests. It shows as a deploy whose pods stay unready, with `Merchant directory unreachable at …/merchants` in the start-up log.
- **Risk 2 — more concurrency against 10 database connections.** Without the 200-thread cap, a traffic spike queues on the Hikari pool instead of in Tomcat. It shows as rising `hikaricp_connections_pending` and connection-timeout errors before CPU rises.
- **Risk 3 — refused connections under connection churn.** In this test, 0.65–1.48 % of requests were refused at the TCP level with virtual threads, against 0.05 % before (section 5). Before production, re-run with `server.tomcat.max-keep-alive-requests` and `accept-count` raised. It shows as client-side connection-refused errors with nothing in the service log.
- **Rollback, configuration only:** set `spring.threads.virtual.enabled: false` and restore `server.tomcat.threads.max: 200` and `min-spare: 10`. **Rollback, everything:** `git revert -m 1 <merge commit of PR #1>`.

## 12. Evidence index
- **Recordings:** `pinned.jfr`, `fixed.jfr` (repository root). **Load script, directory stub, runner:** `perf/threads-load.js`, `perf/MerchantDirectoryStub.java`, `perf/ThreadsLab.java`. **Test:** `src/test/java/javatesa6/ledgersettlement/MerchantLookupConcurrencyTest.java`.
- **Raw outputs:** `perf/results/sjv-l1-raw.zip`: `toolchain.txt`, `verify.log`, `results.csv`, `per-run.md`, `commands.txt`, plus every run's k6 console output and `--summary-export` JSON, service log, `jfr summary` and `jfr print` of each pinned event.
