import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SJV-L1 measurement runner. From the repository root, with Postgres on localhost:5433 and k6 on the PATH:
 *
 *     java perf/ThreadsLab.java            (3 runs per stage, about 20 minutes)
 *     java perf/ThreadsLab.java --runs 2
 *
 * What it does, in order:
 *  1. Records the toolchain (java -version, mvn -v, mvnw -v, k6 version, CPU count, memory).
 *  2. Runs ./mvnw -B verify on the current checkout and keeps the log.
 *  3. Builds one jar per stage from its git tag (lab1-baseline, lab1-migrated, lab1-pinned, lab1-fixed)
 *     in a separate git worktree under target/, so the working copy is never switched.
 *  4. Starts the merchant directory stub (perf/MerchantDirectoryStub.java, 3 s delay) on port 9090.
 *  5. For every run: cold-starts the stage jar with plain java -jar, waits until it answers, starts
 *     Flight Recorder with jcmd (settings=profile), runs perf/threads-load.js unchanged, stops the
 *     recording, then kills the JVM. Stages are interleaved (baseline 1, migrated 1, pinned 1,
 *     fixed 1, baseline 2, ...) so slow drift on the machine affects every stage equally.
 *  6. Writes the tables into docs/threads.md between the BEGIN/END markers, keeps pinned.jfr and
 *     fixed.jfr (run 1 of those stages) at the repository root, and zips every raw output into
 *     perf/results/sjv-l1-raw.zip.
 */
public class ThreadsLab {

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path WORK = ROOT.resolve("target/sjv-l1");
    static final Path RUNS = WORK.resolve("runs");
    static final Path DOC = ROOT.resolve("docs/threads.md");
    static final Path RAW_ZIP = ROOT.resolve("perf/results/sjv-l1-raw.zip");
    static final String LOAD_SCRIPT = "perf/threads-load.js";
    static final int APP_PORT = 8080;
    static final int DIRECTORY_PORT = 9090;
    static final Duration DIRECTORY_DELAY = Duration.ofSeconds(3);

    static final LinkedHashMap<String, String> STAGES = new LinkedHashMap<>();
    static final Map<String, String> STAGE_TITLES = Map.of(
            "baseline", "Baseline (platform threads)",
            "migrated", "Migrated (virtual threads)",
            "pinned", "Pinned (defect planted)",
            "fixed", "Fixed");

    static {
        STAGES.put("baseline", "lab1-baseline");
        STAGES.put("migrated", "lab1-migrated");
        STAGES.put("pinned", "lab1-pinned");
        STAGES.put("fixed", "lab1-fixed");
    }

    record Run(String stage, int n, double rps, long requests, double p50, double p95, double p99,
               double checksPassed, long amountMin, long amountMax, double startupSeconds, int pinnedEvents, int lookupPinnedEvents,
               String threadLogLine, Path jfrFile, String jfrText) {}

    static final List<String> commandLog = new ArrayList<>();
    static final List<String> discarded = new ArrayList<>();
    /** k6 runs a 60 s profile; anything far longer means the machine stalled (sleep, swapping) mid-run. */
    static final double STALL_SECONDS = 100;

    static final class MachineStall extends RuntimeException {
        final double k6Seconds;

        MachineStall(double k6Seconds) {
            super("k6 took " + Math.round(k6Seconds) + " s for the 60 s profile");
            this.k6Seconds = k6Seconds;
        }
    }

    static final class LabFailure extends RuntimeException {
        LabFailure(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            runLab(args);
        } catch (LabFailure e) {
            System.err.println("\nERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    static void runLab(String[] args) throws Exception {
        int runs = 3;
        boolean skipVerify = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--skip-verify" -> skipVerify = true;
                default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
            }
        }
        if (!Files.exists(ROOT.resolve("pom.xml")) || !Files.exists(ROOT.resolve(LOAD_SCRIPT))) {
            fail("Run this from the repository root: java perf/ThreadsLab.java");
        }
        if (Runtime.version().feature() < 25) {
            fail("Run with JDK 25 (found " + Runtime.version() + "). The same JDK starts the service.");
        }
        keepWindowsAwake();
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        String java = exe(javaBin, "java"), jcmd = exe(javaBin, "jcmd"), jfr = exe(javaBin, "jfr");

        deleteRecursively(RUNS);
        Files.createDirectories(RUNS);

        step("1/6 Toolchain");
        String toolchain = recordToolchain(java);
        Files.writeString(WORK.resolve("toolchain.txt"), toolchain);
        System.out.println(toolchain);

        if (!skipVerify) {
            step("2/6 ./mvnw -B verify on the current checkout");
            int code = run(ROOT, WORK.resolve("verify.log"), mvnw(ROOT, "-B", "verify"));
            if (code != 0) fail("mvnw verify failed - see " + WORK.resolve("verify.log"));
            System.out.println("verify passed, log kept in target/sjv-l1/verify.log");
        }

        step("3/6 Building one jar per stage from its tag");
        Map<String, Path> jars = new LinkedHashMap<>();
        for (var stage : STAGES.entrySet()) {
            jars.put(stage.getKey(), buildStageJar(stage.getKey(), stage.getValue()));
        }

        step("4/6 Merchant directory stub on port " + DIRECTORY_PORT);
        ensurePortFree(DIRECTORY_PORT);
        HttpServer stub = MerchantDirectoryStub.start(DIRECTORY_PORT, DIRECTORY_DELAY);

        step("5/6 Load runs (" + runs + " per stage, cold start before every run)");
        List<Run> results = new ArrayList<>();
        try {
            for (int n = 1; n <= runs; n++) {
                for (String stage : STAGES.keySet()) {
                    for (int attempt = 1; ; attempt++) {
                        try {
                            results.add(measure(stage, n, jars.get(stage), java, jcmd, jfr));
                            break;
                        } catch (MachineStall stall) {
                            String id = stage + "-" + n;
                            System.out.printf("WARNING: %s - %s. The machine stalled (sleep or swapping?); "
                                    + "this run is set aside and repeated.%n", id, stall.getMessage());
                            discarded.add(id + " attempt " + attempt + " (" + stall.getMessage() + ")");
                            setAside(id, attempt, stage);
                            if (attempt == 3) fail(id + " stalled three times. Stop the machine from sleeping and re-run.");
                        }
                    }
                }
            }
        } finally {
            stub.stop(0);
        }

        step("6/6 Writing results");
        writeOutputs(results, toolchain);
        System.out.println("""

                Done. Next:
                  1. Read docs/threads.md (tables filled in); every run is in target/sjv-l1/per-run.md.
                  2. git add docs/threads.md pinned.jfr fixed.jfr perf/results/sjv-l1-raw.zip
                  3. Send docs/threads.md and perf/results/sjv-l1-raw.zip back for the final write-up.
                """);
    }

    // ---------------------------------------------------------------- one run

    static Run measure(String stage, int n, Path jar, String java, String jcmd, String jfr) throws Exception {
        String id = stage + "-" + n;
        System.out.printf("%n--- %s (%s) ---%n", id, STAGES.get(stage));
        ensurePortFree(APP_PORT);

        Path appLog = RUNS.resolve(id + ".app.log");
        List<String> appCommand = List.of(java,
                "-D" + "ledger.merchant-directory.url=http://localhost:" + DIRECTORY_PORT,
                "-jar", ROOT.relativize(jar).toString());
        commandLog.add(id + ": " + String.join(" ", appCommand));
        long started = System.nanoTime();
        Process app = new ProcessBuilder(appCommand).directory(ROOT.toFile())
                .redirectErrorStream(true).redirectOutput(appLog.toFile()).start();
        try {
            waitUntilServing(app, appLog);
            double startupSeconds = (System.nanoTime() - started) / 1e9;
            System.out.printf("serving after %.1f s (pid %d)%n", startupSeconds, app.pid());

            // Run 1 of the pinned and fixed stages writes the deliverables at the repository root.
            String jfrName = (n == 1 && (stage.equals("pinned") || stage.equals("fixed")))
                    ? stage + ".jfr"
                    : ROOT.relativize(RUNS.resolve(id + ".jfr")).toString().replace('\\', '/');
            Files.deleteIfExists(ROOT.resolve(jfrName));
            List<String> jfrStart = List.of(jcmd, String.valueOf(app.pid()), "JFR.start", "name=pin",
                    "settings=profile", "filename=" + jfrName);
            commandLog.add(id + ": jcmd " + app.pid() + " JFR.start name=pin settings=profile filename=" + jfrName);
            check(run(ROOT, RUNS.resolve(id + ".jcmd.txt"), jfrStart), "jcmd JFR.start");

            Path summary = RUNS.resolve(id + ".k6-summary.json");
            List<String> k6 = List.of("k6", "run", "--summary-export",
                    ROOT.relativize(summary).toString(), LOAD_SCRIPT);
            commandLog.add(id + ": " + String.join(" ", k6).replace('\\', '/'));
            long k6Started = System.nanoTime();
            check(run(ROOT, RUNS.resolve(id + ".k6.txt"), k6), "k6 run");
            double k6Seconds = (System.nanoTime() - k6Started) / 1e9;

            commandLog.add(id + ": jcmd " + app.pid() + " JFR.stop name=pin");
            check(run(ROOT, RUNS.resolve(id + ".jcmd-stop.txt"),
                    List.of(jcmd, String.valueOf(app.pid()), "JFR.stop", "name=pin")), "jcmd JFR.stop");

            if (k6Seconds > STALL_SECONDS) throw new MachineStall(k6Seconds);
            return analyse(stage, n, startupSeconds, summary, appLog, ROOT.resolve(jfrName), jfr);
        } finally {
            app.destroy();
            if (!app.waitFor(20, TimeUnit.SECONDS)) app.destroyForcibly().waitFor();
        }
    }

    /** Moves a stalled run's raw files to runs/discarded so they stay in the evidence but out of the results. */
    static void setAside(String id, int attempt, String stage) throws IOException {
        Path dir = RUNS.resolve("discarded");
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(RUNS)) {
            for (Path f : files.filter(f -> f.getFileName().toString().startsWith(id + ".")).toList()) {
                Files.move(f, dir.resolve(id + "-attempt" + attempt + f.getFileName().toString().substring(id.length())),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path rootJfr = ROOT.resolve(stage + ".jfr");
        if (id.endsWith("-1") && Files.exists(rootJfr)) {
            Files.move(rootJfr, dir.resolve(id + "-attempt" + attempt + ".jfr"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Run analyse(String stage, int n, double startup, Path summary, Path appLog, Path jfrFile, String jfr)
            throws Exception {
        String id = stage + "-" + n;
        Map<String, Object> metrics = asMap(asMap(Json.parse(Files.readString(summary))).get("metrics"));
        Map<String, Object> reqs = metric(metrics, "http_reqs{phase:load}");
        Map<String, Object> dur = metric(metrics, "http_req_duration{phase:load}");
        Map<String, Object> checks = metric(metrics, "checks{phase:load}");
        double rps = num(reqs, "rate");
        long count = (long) num(reqs, "count");
        double p50 = dur.containsKey("p(50)") ? num(dur, "p(50)") : num(dur, "med");
        double checksPassed = checks.containsKey("passes")
                ? 100.0 * num(checks, "passes") / Math.max(1, num(checks, "passes") + num(checks, "fails"))
                : 100.0 * num(checks, "value");

        capture(RUNS.resolve(id + ".jfr-summary.txt"), jfr, "summary", jfrFile.toString());
        String events = capture(RUNS.resolve(id + ".pinned-events.txt"),
                jfr, "print", "--stack-depth", "64", "--events", "jdk.VirtualThreadPinned", jfrFile.toString());
        List<String> blocks = eventBlocks(events);
        int lookup = (int) blocks.stream().filter(b -> b.contains("MerchantLookup")).count();

        String threadLine = Files.readAllLines(appLog).stream()
                .filter(l -> l.contains("isVirtual=")).findFirst()
                .map(l -> l.substring(l.indexOf("Settlement requests"))).orElse("(no request-thread log line)");

        Map<String, Object> amount = metric(metrics, "settlement_amount_minor");
        Run r = new Run(stage, n, rps, count, p50, num(dur, "p(95)"), num(dur, "p(99)"), checksPassed,
                Math.round(num(amount, "min")), Math.round(num(amount, "max")), startup,
                blocks.size(), lookup, threadLine, jfrFile, events);
        System.out.printf("%.1f req/s, p50 %.2f ms, p95 %.2f ms, p99 %.2f ms, checks %.2f %%, amount %d..%d, "
                        + "%d pinned events (%d MerchantLookup)%n%s%n",
                r.rps, r.p50, r.p95, r.p99, r.checksPassed, r.amountMin, r.amountMax, r.pinnedEvents,
                r.lookupPinnedEvents, threadLine);
        return r;
    }

    // ---------------------------------------------------------------- outputs

    static void writeOutputs(List<Run> runs, String toolchain) throws IOException {
        Map<String, List<Run>> byStage = new LinkedHashMap<>();
        STAGES.keySet().forEach(s -> byStage.put(s, new ArrayList<>()));
        runs.forEach(r -> byStage.get(r.stage).add(r));

        try (var csv = Files.newBufferedWriter(WORK.resolve("results.csv"))) {
            csv.write("stage,run,throughput_req_per_s,requests,p50_ms,p95_ms,p99_ms,checks_passed_pct,amount_min,amount_max,"
                    + "startup_s,pinned_events,merchantlookup_pinned_events\n");
            for (Run r : runs) {
                csv.write(String.format(Locale.ROOT, "%s,%d,%.2f,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%.1f,%d,%d%n", r.stage, r.n,
                        r.rps, r.requests, r.p50, r.p95, r.p99, r.checksPassed, r.amountMin, r.amountMax, r.startupSeconds, r.pinnedEvents,
                        r.lookupPinnedEvents));
            }
        }

        Files.writeString(WORK.resolve("per-run.md"), perRunTable(runs));
        Files.writeString(WORK.resolve("commands.txt"), String.join("\n", commandLog) + "\n");

        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("toolchain", "```text\n" + toolchain.lines()
                .filter(l -> l.startsWith("$ ") || l.startsWith("openjdk") || l.startsWith("java ") || l.contains("Runtime Environment")
                        || l.startsWith("Apache Maven") || l.startsWith("k6") || l.startsWith("Machine:") || l.startsWith("Service launched"))
                .collect(Collectors.joining("\n")) + "\n```");
        sections.put("results-table", resultsTable(byStage));
        sections.put("verdict", verdict(byStage));
        sections.put("pinned-event", pinnedEvent(byStage.get("pinned")));
        sections.put("jfr-counts", jfrCounts(byStage));
        sections.put("commands", "```text\n" + commandLog.stream()
                .filter(c -> c.startsWith("pinned-1:") || c.startsWith("fixed-1:"))
                .collect(Collectors.joining("\n")) + "\n```");

        if (Files.exists(DOC)) {
            String doc = Files.readString(DOC);
            for (var e : sections.entrySet()) {
                Pattern p = Pattern.compile("(<!-- BEGIN:" + e.getKey() + " -->)(.*?)(<!-- END:" + e.getKey() + " -->)",
                        Pattern.DOTALL);
                Matcher m = p.matcher(doc);
                if (m.find()) {
                    doc = m.replaceFirst(Matcher.quoteReplacement(m.group(1) + "\n" + e.getValue() + "\n" + m.group(3)));
                }
            }
            Files.writeString(DOC, doc);
            System.out.println("Updated " + ROOT.relativize(DOC));
        }
        Files.writeString(WORK.resolve("generated-sections.md"), sections.entrySet().stream()
                .map(e -> "## " + e.getKey() + "\n\n" + e.getValue()).collect(Collectors.joining("\n\n")));

        Files.createDirectories(RAW_ZIP.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(RAW_ZIP));
             Stream<Path> files = Stream.concat(Stream.of(WORK.resolve("toolchain.txt"), WORK.resolve("verify.log"),
                     WORK.resolve("results.csv"), WORK.resolve("per-run.md"), WORK.resolve("commands.txt")),
                     Files.walk(RUNS).filter(Files::isRegularFile).sorted())) {
            for (Path f : files.filter(Files::exists).filter(f -> !f.toString().endsWith(".jfr")).toList()) {
                zip.putNextEntry(new ZipEntry("sjv-l1-raw/" + WORK.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
        System.out.println("Raw outputs zipped to " + ROOT.relativize(RAW_ZIP));
    }

    static String resultsTable(Map<String, List<Run>> byStage) {
        StringBuilder sb = new StringBuilder();
        int n = byStage.values().iterator().next().size();
        sb.append("Mean of ").append(n).append(" runs per stage, lowest and highest run in brackets; every run is in ")
                .append("`per-run.md` in the raw zip. ")
                .append(discarded.isEmpty()
                        ? "No run stalled (a run is set aside and repeated if k6 needs over " + (int) STALL_SECONDS + " s for the 60 s profile).\n\n"
                        : "Set aside and repeated because the machine stalled mid-run (k6 needed over " + (int) STALL_SECONDS
                                + " s for the 60 s profile): " + String.join("; ", discarded)
                                + ". Their raw output is under `runs/discarded/` in the raw zip.\n\n");
        sb.append("| Metric |");
        byStage.keySet().forEach(s -> sb.append(' ').append(STAGE_TITLES.get(s)).append(" |"));
        sb.append("\n| :--- |").append(" ---: |".repeat(byStage.size())).append('\n');
        row(sb, "Throughput (req/s)", byStage, Run::rps, 1);
        row(sb, "p50 latency (ms)", byStage, Run::p50, 2);
        row(sb, "p95 latency (ms)", byStage, Run::p95, 2);
        row(sb, "p99 latency (ms)", byStage, Run::p99, 2);
        row(sb, "Correct responses (% of checks)", byStage, Run::checksPassed, 2);
        sb.append("| amountOwedMinor returned for MR-4471 (lowest–highest over every response) |");
        byStage.values().forEach(rs -> sb.append(' ').append(rs.stream().mapToLong(Run::amountMin).min().orElse(-1))
                .append("–").append(rs.stream().mapToLong(Run::amountMax).max().orElse(-1)).append(" |"));
        sb.append('\n');
        row(sb, "Start-up until serving (s)", byStage, Run::startupSeconds, 1);
        row(sb, "jdk.VirtualThreadPinned events (count)", byStage, r -> r.pinnedEvents, 0);
        sb.append("| Run-to-run spread: throughput / p95 / p99 (% of mean) |");
        byStage.values().forEach(rs -> sb.append(' ').append(spreadPct(rs, Run::rps)).append(" / ")
                .append(spreadPct(rs, Run::p95)).append(" / ").append(spreadPct(rs, Run::p99)).append(" |"));
        sb.append("\n| Request thread, from the service log of every run |");
        byStage.values().forEach(rs -> sb.append(' ').append(rs.stream()
                .map(r -> r.threadLogLine.contains("isVirtual=true") ? "virtual" : r.threadLogLine.contains("isVirtual=false") ? "platform" : "?")
                .distinct().collect(Collectors.joining(", "))).append(" |"));
        sb.append("\n\nExample log lines, run 1: baseline `").append(byStage.get("baseline").getFirst().threadLogLine)
                .append("`; migrated `").append(byStage.get("migrated").getFirst().threadLogLine).append("`.");
        return sb.toString().strip();
    }

    static void row(StringBuilder sb, String label, Map<String, List<Run>> byStage, ToDoubleFunction<Run> f, int dp) {
        sb.append("| ").append(label).append(" |");
        for (List<Run> rs : byStage.values()) {
            DoubleSummaryStatistics s = rs.stream().mapToDouble(f).summaryStatistics();
            sb.append(' ').append(fmt(s.getAverage(), dp)).append(" (").append(fmt(s.getMin(), dp)).append("–")
                    .append(fmt(s.getMax(), dp)).append(") |");
        }
        sb.append('\n');
    }

    static String perRunTable(List<Run> runs) {
        StringBuilder sb = new StringBuilder("| Stage | Run | Throughput (req/s) | p50 (ms) | p95 (ms) | p99 (ms) "
                + "| Correct (%) | Start-up (s) | Pinned events | of which MerchantLookup | Request thread (service log) |\n"
                + "| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :--- |\n");
        runs.stream().sorted(Comparator.comparing((Run r) -> List.copyOf(STAGES.keySet()).indexOf(r.stage))
                .thenComparingInt(Run::n)).forEach(r -> sb.append(String.format(Locale.ROOT,
                "| %s | %d | %.1f | %.2f | %.2f | %.2f | %.2f | %.1f | %d | %d | `%s` |%n", r.stage, r.n, r.rps, r.p50,
                r.p95, r.p99, r.checksPassed, r.startupSeconds, r.pinnedEvents, r.lookupPinnedEvents, r.threadLogLine)));
        return sb.toString();
    }

    /** A change only counts when it is larger than the spread of runs inside either stage. */
    static String verdict(Map<String, List<Run>> byStage) {
        StringBuilder sb = new StringBuilder("Rule: a difference counts as improved or worse only when the gap between "
                + "the two means is larger than the run-to-run spread (highest minus lowest run) of either stage; "
                + "otherwise it is flat.\n\n| Comparison | Throughput | p50 | p95 | p99 |\n"
                + "| :--- | :--- | :--- | :--- | :--- |\n");
        String[][] pairs = {{"baseline", "migrated"}, {"pinned", "fixed"}};
        for (String[] p : pairs) {
            List<Run> a = byStage.get(p[0]), b = byStage.get(p[1]);
            sb.append("| ").append(p[0]).append(" → ").append(p[1]).append(" | ")
                    .append(compare(a, b, Run::rps, true)).append(" | ")
                    .append(compare(a, b, Run::p50, false)).append(" | ")
                    .append(compare(a, b, Run::p95, false)).append(" | ")
                    .append(compare(a, b, Run::p99, false)).append(" |\n");
        }
        String overall = compareWord(byStage.get("baseline"), byStage.get("migrated"), Run::rps, true);
        sb.append("\n**Verdict for the migration (baseline → migrated), judged on throughput: ")
                .append(overall).append(".**");
        return sb.toString();
    }

    static String compare(List<Run> a, List<Run> b, ToDoubleFunction<Run> f, boolean higherIsBetter) {
        double ma = mean(a, f), mb = mean(b, f);
        return String.format(Locale.ROOT, "%+.1f %% (%s)", 100 * (mb - ma) / ma, compareWord(a, b, f, higherIsBetter));
    }

    static String compareWord(List<Run> a, List<Run> b, ToDoubleFunction<Run> f, boolean higherIsBetter) {
        double diff = mean(b, f) - mean(a, f);
        double noise = Math.max(spread(a, f), spread(b, f));
        if (Math.abs(diff) <= noise) return "flat";
        return (diff > 0) == higherIsBetter ? "improved" : "worse";
    }

    static String pinnedEvent(List<Run> pinnedRuns) {
        Run first = pinnedRuns.getFirst();
        List<String> blocks = eventBlocks(first.jfrText);
        String chosen = blocks.stream().filter(b -> b.contains("VM call to") && b.contains("MerchantLookup.<clinit>"))
                .findFirst()
                .or(() -> blocks.stream().filter(b -> b.contains("MerchantLookup")).findFirst())
                .orElse(null);
        if (chosen == null) {
            return "**No MerchantLookup pinned event was found in pinned.jfr - check the pinned stage before submitting.**";
        }
        return "From `jfr print --stack-depth 64 --events jdk.VirtualThreadPinned pinned.jfr` (pinned run 1); "
                + "the recording holds " + blocks.size() + " pinned events, " + first.lookupPinnedEvents
                + " of them involving MerchantLookup.\n\n```text\n"
                + chosen.strip().lines().filter(l -> !l.isBlank()).collect(Collectors.joining("\n")) + "\n```";
    }

    static String jfrCounts(Map<String, List<Run>> byStage) {
        StringBuilder sb = new StringBuilder();
        for (String stage : List.of("pinned", "fixed")) {
            Run r = byStage.get(stage).getFirst();
            sb.append("**").append(stage).append(".jfr**: ").append(r.pinnedEvents)
                    .append(" `jdk.VirtualThreadPinned` events, ").append(r.lookupPinnedEvents)
                    .append(" involving MerchantLookup.");
            Map<String, Long> reasons = eventBlocks(r.jfrText).stream()
                    .map(ThreadsLab::reasonOf)
                    .collect(Collectors.groupingBy(x -> x, TreeMap::new, Collectors.counting()));
            if (!reasons.isEmpty()) {
                sb.append("\n\n| Pinning reason | Events |\n| :--- | ---: |\n");
                reasons.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                        .limit(4).forEach(e -> sb.append("| ").append(e.getKey().replace("|", "\\|")).append(" | ")
                                .append(e.getValue()).append(" |\n"));
            }
            if (reasons.size() > 4) sb.append("| (").append(reasons.size() - 4).append(" more reasons: see the raw zip) | |\n");
            sb.append("\n\n");
        }
        return sb.toString().strip();
    }

    // ---------------------------------------------------------------- helpers

    static String recordToolchain(String java) throws Exception {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return "Recorded " + ZonedDateTime.now() + "\n\n$ java -version\n" + capture(null, java, "-version")
                + "\n$ mvn -v\n" + mavenOnPath()
                + "\n$ mvnw -v (the wrapper used for every build)\n" + capture(null, mvnw(ROOT, "-v").toArray(String[]::new))
                + "\n$ k6 version\n" + capture(null, "k6", "version")
                + "\nMachine: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + "), " + Runtime.getRuntime().availableProcessors()
                + " logical CPUs, " + (os.getTotalMemorySize() >> 20) + " MiB RAM\n"
                + "Service launched with: java -jar on the extracted application jar (java -Djarmode=tools -jar app.jar extract), "
                + "no IDE, no extra JVM flags; k6 on the same machine.\n";
    }

    /**
     * A sleeping laptop freezes a run mid-measurement. On Windows, ask the OS not to sleep while this process runs
     * (SetThreadExecutionState via the Java 25 foreign function API; released automatically when the runner exits).
     */
    static void keepWindowsAwake() {
        if (!WINDOWS) return;
        try {
            var linker = java.lang.foreign.Linker.nativeLinker();
            var kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", java.lang.foreign.Arena.global());
            var setState = linker.downcallHandle(kernel32.find("SetThreadExecutionState").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT,
                            java.lang.foreign.ValueLayout.JAVA_INT));
            int esContinuous = 0x80000000, esSystemRequired = 0x00000001;
            int previous = (int) setState.invokeExact(esContinuous | esSystemRequired);
            System.out.println(previous != 0
                    ? "Windows will not sleep while the lab runs (keep the lid open)."
                    : "WARNING: Windows did not accept the keep-awake request; set Sleep to Never while this runs.");
        } catch (Throwable t) {
            System.out.println("WARNING: could not keep Windows awake (" + t + "); set Sleep to Never while this runs.");
        }
    }

    static String mavenOnPath() {
        try {
            return capture(null, (WINDOWS ? List.of("cmd.exe", "/c", "mvn", "-v") : List.of("mvn", "-v")).toArray(String[]::new));
        } catch (Exception e) {
            return "(mvn is not on the PATH; the Maven wrapper below was used)\n";
        }
    }

    static Path buildStageJar(String stage, String tag) throws Exception {
        Path tree = WORK.resolve("worktrees").resolve(stage);
        if (Files.exists(tree)) {
            run(ROOT, WORK.resolve("git-worktree.log"), List.of("git", "worktree", "remove", "--force", tree.toString()));
            deleteRecursively(tree);
        }
        run(ROOT, WORK.resolve("git-worktree.log"), List.of("git", "worktree", "prune"));
        check(run(ROOT, WORK.resolve("git-worktree-" + stage + ".log"),
                List.of("git", "worktree", "add", "--detach", tree.toString(), tag)), "git worktree add " + tag);
        String commit = capture(null, "git", "-C", tree.toString(), "rev-parse", "--short", "HEAD").strip();
        System.out.printf("%-9s %s (%s): packaging%n", stage, tag, commit);
        // Tests ran once in step 2 on the branch head; these historical builds are only packaged.
        check(run(tree, WORK.resolve("build-" + stage + ".log"), mvnw(tree, "-q", "-B", "-DskipTests", "package")),
                "build of " + tag + " (see target/sjv-l1/build-" + stage + ".log)");
        Path bootJar;
        try (Stream<Path> s = Files.list(tree.resolve("target"))) {
            bootJar = s.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-plain.jar"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No jar built for " + tag));
        }
        boolean springBootJar;
        try (var zip = new java.util.zip.ZipFile(bootJar.toFile())) {
            springBootJar = zip.getEntry("BOOT-INF/") != null || zip.getEntry("BOOT-INF/classes/") != null;
        }
        if (!springBootJar) return bootJar;
        // Every stage runs from the extracted layout Spring Boot recommends for production
        // (java -Djarmode=tools -jar app.jar extract): plain jars on the class path, no nested-jar loader.
        Path extracted = tree.resolve("target/extracted");
        check(run(tree, WORK.resolve("extract-" + stage + ".log"), List.of(exe(Path.of(System.getProperty("java.home"), "bin"), "java"),
                "-Djarmode=tools", "-jar", bootJar.toString(), "extract", "--destination", extracted.toString(),
                "--application-filename", "ledger-settlement.jar", "--force")), "jar extract of " + tag);
        return extracted.resolve("ledger-settlement.jar");
    }

    static void waitUntilServing(Process app, Path appLog) throws Exception {
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest probe = HttpRequest.newBuilder(URI.create("http://localhost:" + APP_PORT
                + "/payments/settlement?merchantId=READINESS-PROBE")).timeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) {
                fail("The service exited during start-up. Last lines of " + ROOT.relativize(appLog) + ":\n" + tail(appLog));
            }
            try {
                if (client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 404) return;
            } catch (IOException notYet) {
                // not listening yet
            }
            Thread.sleep(250);
        }
        fail("The service did not start within 2 minutes:\n" + tail(appLog));
    }

    static void ensurePortFree(int port) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            try (Socket ignored = new Socket("localhost", port)) {
                Thread.sleep(500);
            } catch (IOException free) {
                return;
            }
        }
        fail("Port " + port + " is in use. Stop anything on it (for example the service running in the IDE).");
    }

    static List<String> eventBlocks(String jfrPrint) {
        String[] parts = jfrPrint.split("jdk\\.VirtualThreadPinned \\{");
        List<String> blocks = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) blocks.add("jdk.VirtualThreadPinned {" + parts[i]);
        return blocks;
    }

    static String reasonOf(String block) {
        Matcher m = Pattern.compile("pinnedReason = \"(.*?)\"").matcher(block);
        return m.find() ? m.group(1) : "(no reason recorded)";
    }

    static List<String> mvnw(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(WINDOWS
                ? List.of("cmd.exe", "/c", dir.resolve("mvnw.cmd").toString())
                : List.of("sh", dir.resolve("mvnw").toString()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    static int run(Path dir, Path output, List<String> command) throws Exception {
        return new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start().waitFor();
    }

    static String capture(Path saveTo, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS")).collect(Collectors.joining("\n")) + "\n";
        p.waitFor();
        if (saveTo != null) Files.writeString(saveTo, out);
        return out;
    }

    static void check(int exitCode, String what) {
        if (exitCode != 0) fail(what + " failed (exit " + exitCode + "). Logs are in target/sjv-l1/.");
    }

    static String tail(Path file) throws IOException {
        List<String> lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    static String exe(Path bin, String name) {
        return bin.resolve(WINDOWS ? name + ".exe" : name).toString();
    }

    static Map<String, Object> metric(Map<String, Object> metrics, String name) {
        Object m = metrics.get(name);
        if (m == null) fail("k6 summary has no metric " + name);
        Map<String, Object> map = asMap(m);
        return map.containsKey("values") ? asMap(map.get("values")) : map;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    static double num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) fail("k6 summary is missing " + key + " in " + m.keySet());
        return v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).doubleValue();
    }

    static double mean(List<Run> rs, ToDoubleFunction<Run> f) {
        return rs.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    static double spread(List<Run> rs, ToDoubleFunction<Run> f) {
        DoubleSummaryStatistics s = rs.stream().mapToDouble(f).summaryStatistics();
        return s.getMax() - s.getMin();
    }

    static String spreadPct(List<Run> rs, ToDoubleFunction<Run> f) {
        return String.format(Locale.ROOT, "%.1f %%", 100 * spread(rs, f) / mean(rs, f));
    }

    static String fmt(double v, int dp) {
        return String.format(Locale.ROOT, "%." + dp + "f", v);
    }

    static void step(String s) {
        System.out.println("\n=== " + s);
    }

    static void fail(String message) {
        throw new LabFailure(message);
    }

    /** Just enough JSON for the k6 summary file. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            Object v = j.value();
            j.ws();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    i++; // ':'
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(start, i));
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }
    }
}
