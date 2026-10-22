import com.sun.net.httpserver.HttpServer;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
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
 * SJV-L2 measurement runner (garbage collection under steady load). From the repository root, with Postgres on
 * localhost:5433 and k6 on the PATH:
 *
 *     java perf/GcLab.java baseline     phase 1: one baseline run (about 15 min)
 *     java perf/GcLab.java compare      phase 2: tuned 1, baseline 2, tuned 2 (about 40 min)
 *
 * Every run: build the stage's jar from its git tag in a worktree (target/ledger-settlement.jar), start it with
 * java [stage flags] -jar target/ledger-settlement.jar, run perf/steady-load.js once as a 2-minute warm-up at the
 * same rate, then start Flight Recorder with jcmd (duration=10m, settings=profile) exactly as the measured 10-minute
 * k6 run starts. All GC figures are read from the recording with the JDK's own jdk.jfr.consumer API; the tables are
 * written into docs/gc.md between BEGIN/END markers, every run is appended to perf/results/sjv-l2-runs.csv, and the
 * raw outputs are zipped into perf/results/sjv-l2-raw.zip.
 *
 * Stages: baseline = tag gc-baseline, no extra flags. tuned = perf/gc-stages.properties (tuned.ref, tuned.jvmFlags).
 */
public class GcLab {

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path WORK = ROOT.resolve("target/sjv-l2");
    static final Path RUNS = WORK.resolve("runs");
    static final Path DOC = ROOT.resolve("docs/gc.md");
    static final Path CSV = ROOT.resolve("perf/results/sjv-l2-runs.csv");
    static final Path RAW_ZIP = ROOT.resolve("perf/results/sjv-l2-raw.zip");
    static final String LOAD_SCRIPT = "perf/steady-load.js";
    static final int APP_PORT = 8080;
    static final int DIRECTORY_PORT = 9090;
    static final double WARMUP_STALL_SECONDS = 180;
    static final double STEADY_STALL_SECONDS = 690;

    record Stage(String name, String ref, List<String> jvmFlags) {}

    /** One measured run. Units: MB = 10^6 bytes, ms, s, req/s. */
    record Run(String stage, int n, double recordingSeconds, double allocatedMB, double allocMBps, String topClasses,
               int gcCount, int youngCount, int otherCount, double longestPauseMs, double totalPauseMs,
               double phasePauseMaxMs, double heapAfterAvgMB, double heapAfterMaxMB, double committedMaxMB,
               double rssMaxMB, double cpuJvmPct, double rps, double p50, double p95, double p99, double failedPct,
               long dropped, double startupSeconds, long amountMin, long amountMax, String collector) {

        static final String HEADER = "stage,run,recording_s,allocated_MB,alloc_MB_per_s,top_classes,gc_count,young_count,"
                + "other_count,longest_pause_ms,total_pause_ms,phase_pause_max_ms,heap_after_gc_avg_MB,heap_after_gc_max_MB,"
                + "heap_committed_max_MB,rss_max_MB,cpu_jvm_pct,throughput_req_per_s,p50_ms,p95_ms,p99_ms,failed_pct,"
                + "dropped_iterations,startup_s,amount_min,amount_max,collector";

        String csv() {
            return String.format(Locale.ROOT, "%s,%d,%.1f,%.1f,%.2f,%s,%d,%d,%d,%.2f,%.1f,%.2f,%.1f,%.1f,%.1f,%.1f,%.1f,"
                            + "%.2f,%.2f,%.2f,%.2f,%.3f,%d,%.1f,%d,%d,%s",
                    stage, n, recordingSeconds, allocatedMB, allocMBps, topClasses, gcCount, youngCount, otherCount,
                    longestPauseMs, totalPauseMs, phasePauseMaxMs, heapAfterAvgMB, heapAfterMaxMB, committedMaxMB, rssMaxMB,
                    cpuJvmPct, rps, p50, p95, p99, failedPct, dropped, startupSeconds, amountMin, amountMax, collector);
        }

        static Run parse(String line) {
            String[] f = line.split(",", -1);
            return new Run(f[0], Integer.parseInt(f[1]), d(f[2]), d(f[3]), d(f[4]), f[5], Integer.parseInt(f[6]),
                    Integer.parseInt(f[7]), Integer.parseInt(f[8]), d(f[9]), d(f[10]), d(f[11]), d(f[12]), d(f[13]),
                    d(f[14]), d(f[15]), d(f[16]), d(f[17]), d(f[18]), d(f[19]), d(f[20]), d(f[21]), Long.parseLong(f[22]),
                    d(f[23]), Long.parseLong(f[24]), Long.parseLong(f[25]), f[26]);
        }

        private static double d(String s) {
            return Double.parseDouble(s);
        }
    }

    static final class LabFailure extends RuntimeException {
        LabFailure(String message) {
            super(message);
        }
    }

    static final class MachineStall extends RuntimeException {
        MachineStall(String message) {
            super(message);
        }
    }

    static final List<String> commandLog = new ArrayList<>();
    static final List<String> discarded = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 1 || !List.of("baseline", "compare").contains(args[0])) {
                fail("Usage: java perf/GcLab.java baseline   (phase 1)   |   java perf/GcLab.java compare   (phase 2)");
            }
            runLab(args[0]);
        } catch (LabFailure e) {
            System.err.println("\nERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    static void runLab(String mode) throws Exception {
        if (!Files.exists(ROOT.resolve("pom.xml")) || !Files.exists(ROOT.resolve(LOAD_SCRIPT))) {
            fail("Run this from the repository root on branch feature/gc-tuning: java perf/GcLab.java " + mode);
        }
        if (Runtime.version().feature() < 25) fail("Run with JDK 25 (found " + Runtime.version() + ").");
        keepWindowsAwake();
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        String java = exe(javaBin, "java"), jcmd = exe(javaBin, "jcmd"), jfr = exe(javaBin, "jfr");

        Files.createDirectories(RUNS);
        Stage baseline = new Stage("baseline", "gc-baseline", List.of());
        List<Stage> plan = new ArrayList<>();
        List<Integer> runNumbers = new ArrayList<>();
        if (mode.equals("baseline")) {
            plan.add(baseline);
            runNumbers.add(1);
        } else {
            Stage tuned = tunedStage();
            plan.addAll(List.of(tuned, baseline, tuned));
            runNumbers.addAll(List.of(1, 2, 2));
        }

        step("1/5 Starting point and toolchain");
        String toolchain = recordToolchain(java);
        Files.writeString(WORK.resolve("toolchain-" + mode + ".txt"), toolchain);
        System.out.println(toolchain);

        step("2/5 Building target/ledger-settlement.jar for each stage from its tag");
        Map<String, Path> trees = new LinkedHashMap<>();
        for (Stage s : new LinkedHashSet<>(plan)) trees.put(s.name, buildStage(s));

        step("3/5 Merchant directory stub on port " + DIRECTORY_PORT);
        ensurePortFree(DIRECTORY_PORT);
        HttpServer stub = MerchantDirectoryStub.start(DIRECTORY_PORT, Duration.ofSeconds(3));

        step("4/5 Runs: 2-minute warm-up, then the measured 10 minutes under Flight Recorder");
        List<Run> runs = new ArrayList<>();
        try {
            for (int i = 0; i < plan.size(); i++) {
                Stage s = plan.get(i);
                int n = runNumbers.get(i);
                for (int attempt = 1; ; attempt++) {
                    try {
                        runs.add(measure(s, n, trees.get(s.name), java, jcmd, jfr));
                        break;
                    } catch (MachineStall stall) {
                        String id = s.name + "-" + n;
                        System.out.printf("WARNING: %s - %s. The machine stalled; this run is set aside and repeated.%n",
                                id, stall.getMessage());
                        discarded.add(id + " attempt " + attempt + " (" + stall.getMessage() + ")");
                        setAside(id, attempt);
                        if (attempt == 2) fail(id + " stalled twice. Stop the machine from sleeping and re-run.");
                    }
                }
            }
        } finally {
            stub.stop(0);
        }

        step("5/5 Writing results");
        writeOutputs(mode, runs, toolchain);
        System.out.println(mode.equals("baseline")
                ? """

                  Done. Next:
                    git add docs/gc.md gc-baseline.jfr perf/results
                    git commit -m "Record GC baseline"
                  Then send docs/gc.md and perf/results/sjv-l2-raw.zip back for the classification.
                  """
                : """

                  Done. Next:
                    git add docs/gc.md gc-tuned.jfr perf/results
                    git commit -m "Record GC tuned runs and comparison"
                  Then send docs/gc.md and perf/results/sjv-l2-raw.zip back for the final write-up.
                  """);
    }

    static Stage tunedStage() throws IOException {
        Path props = ROOT.resolve("perf/gc-stages.properties");
        if (!Files.exists(props)) fail("perf/gc-stages.properties is missing: phase 2 needs the committed remedy.");
        Properties p = new Properties();
        try (var in = Files.newInputStream(props)) {
            p.load(in);
        }
        String flags = p.getProperty("tuned.jvmFlags", "").strip();
        return new Stage("tuned", p.getProperty("tuned.ref", "gc-tuned").strip(),
                flags.isEmpty() ? List.of() : List.of(flags.split("\\s+")));
    }

    // ---------------------------------------------------------------- one run

    static Run measure(Stage stage, int n, Path tree, String java, String jcmd, String jfr) throws Exception {
        String id = stage.name + "-" + n;
        System.out.printf("%n--- %s (%s%s) ---%n", id, stage.ref, stage.jvmFlags.isEmpty() ? "" : ", " + String.join(" ", stage.jvmFlags));
        ensurePortFree(APP_PORT);
        List<String> appCommand = new ArrayList<>(List.of(java));
        appCommand.addAll(stage.jvmFlags);
        appCommand.addAll(List.of("-jar", "target/ledger-settlement.jar"));
        commandLog.add(id + " (in the " + stage.ref + " build): " + String.join(" ", displayCommand(appCommand)));
        Path appLog = RUNS.resolve(id + ".app.log");
        long started = System.nanoTime();
        Process app = new ProcessBuilder(appCommand).directory(tree.toFile()).redirectErrorStream(true)
                .redirectOutput(appLog.toFile()).start();
        try {
            waitUntilServing(app, appLog);
            double startup = (System.nanoTime() - started) / 1e9;
            System.out.printf("serving after %.1f s (pid %d); 2-minute warm-up at the same rate%n", startup, app.pid());

            List<String> warm = List.of("k6", "run", "-e", "PHASE=warmup", LOAD_SCRIPT);
            commandLog.add(id + ": " + String.join(" ", warm));
            long t0 = System.nanoTime();
            check(run(ROOT, RUNS.resolve(id + ".k6-warmup.txt"), warm), "k6 warm-up");
            if ((System.nanoTime() - t0) / 1e9 > WARMUP_STALL_SECONDS) throw new MachineStall("warm-up overran");

            String jfrName = stage.name.equals("baseline") ? "gc-baseline.jfr" : "gc-tuned.jfr";
            if (n > 1) jfrName = stage.name.equals("baseline") ? "gc-baseline-2.jfr" : "gc-tuned-2.jfr";
            Path jfrInTree = tree.resolve(jfrName);
            Files.deleteIfExists(jfrInTree);
            List<String> start = List.of(jcmd, String.valueOf(app.pid()), "JFR.start", "name=gc", "duration=10m",
                    "filename=" + jfrName, "settings=profile");
            commandLog.add(id + ": jcmd " + app.pid() + " JFR.start name=gc duration=10m filename=" + jfrName + " settings=profile");
            check(run(ROOT, RUNS.resolve(id + ".jcmd.txt"), start), "jcmd JFR.start");
            long recordingStarted = System.nanoTime();

            Path summary = RUNS.resolve(id + ".k6-summary.json");
            List<String> k6 = List.of("k6", "run", "--summary-export", ROOT.relativize(summary).toString().replace('\\', '/'),
                    LOAD_SCRIPT);
            commandLog.add(id + ": " + String.join(" ", k6));
            long k0 = System.nanoTime();
            check(run(ROOT, RUNS.resolve(id + ".k6.txt"), k6), "k6 run");
            double k6Seconds = (System.nanoTime() - k0) / 1e9;

            // duration=10m ends the recording by itself and only then writes the file: wait for that moment.
            while (System.nanoTime() - recordingStarted < Duration.ofSeconds(603).toNanos()) Thread.sleep(500);
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!Files.exists(jfrInTree) && System.nanoTime() < deadline) Thread.sleep(500);
            if (!Files.exists(jfrInTree)) fail("Flight Recorder did not write " + jfrName + " - see " + id + ".jcmd.txt");
            Thread.sleep(3000);
            if (k6Seconds > STEADY_STALL_SECONDS) throw new MachineStall("k6 took " + Math.round(k6Seconds) + " s for the 10-minute profile");

            Path jfrFile = ROOT.resolve(jfrName);
            Files.copy(jfrInTree, jfrFile, StandardCopyOption.REPLACE_EXISTING);
            Run r = analyse(stage, n, startup, summary, jfrFile, jfr);
            if (n > 1) {
                Files.move(jfrFile, RUNS.resolve(jfrName), StandardCopyOption.REPLACE_EXISTING); // only run 1 is a deliverable
            }
            return r;
        } finally {
            app.destroy();
            if (!app.waitFor(20, TimeUnit.SECONDS)) app.destroyForcibly().waitFor();
        }
    }

    static List<String> displayCommand(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd);
        out.set(0, "java");
        return out;
    }

    static Run analyse(Stage stage, int n, double startup, Path k6Summary, Path jfrFile, String jfr) throws Exception {
        String id = stage.name + "-" + n;
        capture(RUNS.resolve(id + ".jfr-summary.txt"), jfr, "summary", jfrFile.toString());
        String gcPrint = capture(null, jfr, "print", "--events", "jdk.GarbageCollection", jfrFile.toString());
        Files.writeString(RUNS.resolve(id + ".gc-events.txt"), gcPrint);
        int printedCollections = gcPrint.split("jdk\\.GarbageCollection \\{", -1).length - 1;

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfrFile);
        Instant first = events.stream().map(RecordedEvent::getStartTime).min(Comparator.naturalOrder()).orElseThrow();
        Instant last = events.stream().map(RecordedEvent::getEndTime).max(Comparator.naturalOrder()).orElseThrow();
        double seconds = Duration.between(first, last).toMillis() / 1000.0;

        Map<String, Long> byClass = new HashMap<>();
        Map<String, Long> byTopFrame = new HashMap<>();
        Map<String, Long> byAppFrame = new HashMap<>();
        long[] perMinuteAlloc = new long[12];
        int[] perMinuteGc = new int[12];
        double[] perMinuteMaxPause = new double[12];
        List<List<Double>> perMinuteHeap = new ArrayList<>();
        for (int i = 0; i < 12; i++) perMinuteHeap.add(new ArrayList<>());
        long allocated = 0;
        int gcCount = 0, young = 0, other = 0;
        double longest = 0, totalPause = 0, phaseMax = 0, cpuSum = 0, rssMax = 0, committedMax = 0;
        int cpuSamples = 0;
        List<Double> heapAfter = new ArrayList<>();
        Set<String> collectors = new TreeSet<>();

        for (RecordedEvent e : events) {
            int minute = (int) Math.min(11, Duration.between(first, e.getStartTime()).toSeconds() / 60);
            switch (e.getEventType().getName()) {
                case "jdk.ObjectAllocationSample" -> {
                    long w = e.getLong("weight");
                    allocated += w;
                    perMinuteAlloc[minute] += w;
                    byClass.merge(prettyClass(e.getClass("objectClass").getName()), w, Long::sum);
                    if (e.getStackTrace() != null && !e.getStackTrace().getFrames().isEmpty()) {
                        List<RecordedFrame> frames = e.getStackTrace().getFrames();
                        byTopFrame.merge(frameName(frames.getFirst()), w, Long::sum);
                        frames.stream().filter(f -> f.getMethod().getType().getName().startsWith("javatesa6."))
                                .findFirst().ifPresentOrElse(f -> byAppFrame.merge(frameName(f), w, Long::sum),
                                        () -> byAppFrame.merge("(no application frame on the stack)", w, Long::sum));
                    }
                }
                case "jdk.GarbageCollection" -> {
                    gcCount++;
                    String name = e.getString("name");
                    if (name.contains("New") || name.contains("Minor") || name.contains("Young")) young++; else other++;
                    double p = e.getDuration("longestPause").toNanos() / 1e6;
                    longest = Math.max(longest, p);
                    totalPause += e.getDuration("sumOfPauses").toNanos() / 1e6;
                    perMinuteGc[minute]++;
                    perMinuteMaxPause[minute] = Math.max(perMinuteMaxPause[minute], p);
                }
                case "jdk.GCPhasePause" -> phaseMax = Math.max(phaseMax, e.getDuration().toNanos() / 1e6);
                case "jdk.GCHeapSummary" -> {
                    if ("After GC".equals(e.getString("when"))) {
                        double mb = e.getLong("heapUsed") / 1e6;
                        heapAfter.add(mb);
                        perMinuteHeap.get(minute).add(mb);
                    }
                    RecordedObject space = e.getValue("heapSpace");
                    if (space != null) committedMax = Math.max(committedMax, space.getLong("committedSize") / 1e6);
                }
                case "jdk.CPULoad" -> {
                    cpuSum += e.getFloat("jvmUser") + e.getFloat("jvmSystem");
                    cpuSamples++;
                }
                case "jdk.ResidentSetSize" -> rssMax = Math.max(rssMax, e.getLong("size") / 1e6);
                case "jdk.GCConfiguration" -> collectors.add(e.getString("youngCollector") + "/" + e.getString("oldCollector"));
                default -> { }
            }
        }
        if (printedCollections != gcCount) {
            System.out.printf("NOTE: jfr print shows %d collections, the event API %d%n", printedCollections, gcCount);
        }

        String topClasses = top(byClass, allocated, 5);
        StringBuilder detail = new StringBuilder();
        detail.append("Recording ").append(fmt(seconds, 1)).append(" s, ").append(fmt(allocated / 1e6, 1))
                .append(" MB sampled allocation (jdk.ObjectAllocationSample weights)\n\nTop allocating classes:\n")
                .append(topList(byClass, allocated, 10)).append("\nTop allocation sites (frame where the object was created):\n")
                .append(topList(byTopFrame, allocated, 15)).append("\nFirst application frame on the allocating stack:\n")
                .append(topList(byAppFrame, allocated, 10)).append("\nPer minute (minute: alloc MB/s, collections, longest pause ms, avg heap after GC MB):\n");
        for (int m = 0; m < 11; m++) {
            if (perMinuteAlloc[m] == 0 && perMinuteGc[m] == 0) continue;
            detail.append(String.format(Locale.ROOT, "%2d: %.2f MB/s, %d GCs, %.2f ms, %.1f MB%n", m + 1,
                    perMinuteAlloc[m] / 1e6 / 60, perMinuteGc[m], perMinuteMaxPause[m],
                    perMinuteHeap.get(m).stream().mapToDouble(x -> x).average().orElse(0)));
        }
        Files.writeString(RUNS.resolve(id + ".gc-analysis.txt"), detail.toString());

        Map<String, Object> metrics = asMap(asMap(Json.parse(Files.readString(k6Summary))).get("metrics"));
        Map<String, Object> reqs = metric(metrics, "http_reqs{phase:steady}");
        Map<String, Object> dur = metric(metrics, "http_req_duration{phase:steady}");
        Map<String, Object> failed = metric(metrics, "http_req_failed{phase:steady}");
        Map<String, Object> amount = metric(metrics, "settlement_amount_minor");
        double failedPct = 100.0 * (failed.containsKey("passes") ? num(failed, "passes")
                / Math.max(1, num(failed, "passes") + num(failed, "fails")) : num(failed, "value"));
        long dropped = metrics.containsKey("dropped_iterations") ? (long) num(metric(metrics, "dropped_iterations"), "count") : 0;

        Run r = new Run(stage.name, n, seconds, allocated / 1e6, allocated / 1e6 / seconds, topClasses, gcCount, young, other,
                longest, totalPause, phaseMax, avg(heapAfter), heapAfter.stream().mapToDouble(x -> x).max().orElse(0),
                committedMax, rssMax, cpuSamples == 0 ? 0 : 100 * cpuSum / cpuSamples, num(reqs, "rate"),
                dur.containsKey("p(50)") ? num(dur, "p(50)") : num(dur, "med"), num(dur, "p(95)"), num(dur, "p(99)"),
                failedPct, dropped, startup, Math.round(num(amount, "min")), Math.round(num(amount, "max")),
                String.join(" ", collectors));
        System.out.printf("recording %.0f s | alloc %.2f MB/s | %d GCs (%d young) | longest pause %.2f ms | "
                        + "%.1f req/s, p99 %.2f ms, failed %.3f %%, dropped %d%n", r.recordingSeconds, r.allocMBps, r.gcCount,
                r.youngCount, r.longestPauseMs, r.rps, r.p99, r.failedPct, r.dropped);
        System.out.print(detail.substring(detail.indexOf("Top allocating classes:"), detail.indexOf("Top allocation sites")));
        return r;
    }

    // ---------------------------------------------------------------- outputs

    static void writeOutputs(String mode, List<Run> newRuns, String toolchain) throws IOException {
        Files.createDirectories(CSV.getParent());
        List<Run> all = new ArrayList<>();
        if (Files.exists(CSV)) {
            Files.readAllLines(CSV).stream().skip(1).filter(l -> !l.isBlank()).map(Run::parse)
                    .filter(r -> newRuns.stream().noneMatch(x -> x.stage.equals(r.stage) && x.n == r.n)).forEach(all::add);
        }
        all.addAll(newRuns);
        all.sort(Comparator.comparing(Run::stage).thenComparingInt(Run::n));
        Files.writeString(CSV, Run.HEADER + "\n" + all.stream().map(Run::csv).collect(Collectors.joining("\n")) + "\n");
        Files.writeString(WORK.resolve("commands-" + mode + ".txt"), String.join("\n", commandLog) + "\n");

        Map<String, String> sections = new LinkedHashMap<>();
        Run b1 = all.stream().filter(r -> r.stage.equals("baseline") && r.n == 1).findFirst().orElseThrow();
        if (mode.equals("baseline")) {
            sections.put("starting-point", "```text\n" + toolchain.strip() + "\n```");
            sections.put("baseline-commands", "```text\n" + String.join("\n", commandLog) + "\n```");
            sections.put("baseline-numbers", fourNumbers(b1, "gc-baseline.jfr"));
            sections.put("baseline-detail", "```text\n" + Files.readString(RUNS.resolve("baseline-1.gc-analysis.txt")).strip() + "\n```");
        } else {
            Run t1 = all.stream().filter(r -> r.stage.equals("tuned") && r.n == 1).findFirst().orElseThrow();
            sections.put("tuned-commands", "```text\n" + String.join("\n", commandLog) + "\n```");
            sections.put("tuned-numbers", fourNumbers(t1, "gc-tuned.jfr"));
            sections.put("tuned-detail", "```text\n" + Files.readString(RUNS.resolve("tuned-1.gc-analysis.txt")).strip() + "\n```");
            sections.put("comparison", comparison(all));
        }
        if (Files.exists(DOC)) {
            String doc = Files.readString(DOC);
            for (var e : sections.entrySet()) {
                Matcher m = Pattern.compile("(<!-- BEGIN:" + e.getKey() + " -->)(.*?)(<!-- END:" + e.getKey() + " -->)",
                        Pattern.DOTALL).matcher(doc);
                if (m.find()) doc = m.replaceFirst(Matcher.quoteReplacement(m.group(1) + "\n" + e.getValue() + "\n" + m.group(3)));
            }
            Files.writeString(DOC, doc);
            System.out.println("Updated " + ROOT.relativize(DOC));
        }

        try (var zip = new ZipOutputStream(Files.newOutputStream(RAW_ZIP));
             Stream<Path> files = Files.walk(WORK).filter(Files::isRegularFile)
                     .filter(f -> !f.startsWith(WORK.resolve("worktrees")))) {
            for (Path f : Stream.concat(files.sorted(), Stream.of(CSV)).toList()) {
                if (f.toString().endsWith(".jfr")) continue;
                zip.putNextEntry(new ZipEntry("sjv-l2-raw/" + (f.equals(CSV) ? "sjv-l2-runs.csv"
                        : WORK.relativize(f).toString().replace('\\', '/'))));
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
        System.out.println("Raw outputs zipped to " + ROOT.relativize(RAW_ZIP));
    }

    static String fourNumbers(Run r, String file) {
        return "From `" + file + "` (" + fmt(r.recordingSeconds, 0) + " s recording, collector " + r.collector + "):\n\n"
                + "| Measure | Value | JDK Mission Control view |\n| :--- | ---: | :--- |\n"
                + "| Total allocation rate | " + fmt(r.allocMBps, 2) + " MB/s (" + fmt(r.allocatedMB, 0) + " MB in "
                + fmt(r.recordingSeconds, 0) + " s) | Memory → Allocation (total of `jdk.ObjectAllocationSample` weights) |\n"
                + "| Top three allocating classes | " + String.join("; ", Arrays.asList(r.topClasses.split(";")).subList(0,
                Math.min(3, r.topClasses.split(";").length))) + " | Memory → Allocation → by class |\n"
                + "| Collection count | " + r.gcCount + " (" + r.youngCount + " young, " + r.otherCount + " mixed/old) "
                + "| Garbage Collections (one row per collection) |\n"
                + "| Longest pause | " + fmt(r.longestPauseMs, 2) + " ms | Garbage Collections → Longest Pause column |\n\n"
                + "Cross-check with the JDK's `jfr` tool: `jfr print --events jdk.GarbageCollection " + file + "` lists "
                + r.gcCount + " collections, and the longest single `jdk.GCPhasePause` is " + fmt(r.phasePauseMaxMs, 2)
                + " ms. Total pause time " + fmt(r.totalPauseMs, 1) + " ms (" + fmt(100 * r.totalPauseMs / 1000 / r.recordingSeconds, 3)
                + " % of the run). Load during the same window (k6): " + fmt(r.rps, 1) + " req/s, p99 " + fmt(r.p99, 2)
                + " ms, failed " + fmt(r.failedPct, 3) + " %, dropped iterations " + r.dropped + ".";
    }

    static String comparison(List<Run> all) {
        List<Run> b = all.stream().filter(r -> r.stage.equals("baseline")).toList();
        List<Run> t = all.stream().filter(r -> r.stage.equals("tuned")).toList();
        StringBuilder sb = new StringBuilder("Each cell: mean of the runs, every run in brackets. Change counts only when it is "
                + "larger than the run-to-run spread (highest minus lowest run) inside either side; otherwise it is "
                + "marked *within noise*.\n\n| Measure | Baseline | Tuned | Change |\n| :--- | :--- | :--- | :--- |\n");
        row(sb, "Allocation rate (MB/s)", b, t, Run::allocMBps, 2, false);
        row(sb, "Collection count (10 min)", b, t, r -> r.gcCount, 0, false);
        row(sb, "Longest pause (ms)", b, t, Run::longestPauseMs, 2, false);
        row(sb, "p99 request latency (ms)", b, t, Run::p99, 2, false);
        row(sb, "Throughput (req/s)", b, t, Run::rps, 1, true);
        row(sb, "Total GC pause time (ms)", b, t, Run::totalPauseMs, 1, false);
        row(sb, "p50 / p95 latency (ms)", b, t, Run::p95, 2, false);
        row(sb, "Heap after GC, average (MB)", b, t, Run::heapAfterAvgMB, 1, false);
        row(sb, "Heap committed, max (MB)", b, t, Run::committedMaxMB, 1, false);
        row(sb, "Resident set size, max (MB)", b, t, Run::rssMaxMB, 1, false);
        row(sb, "JVM CPU (% of machine)", b, t, Run::cpuJvmPct, 1, false);
        row(sb, "Start-up until serving (s)", b, t, Run::startupSeconds, 1, false);
        row(sb, "Failed requests (%)", b, t, Run::failedPct, 3, false);
        sb.append("\n`amountOwedMinor` returned: baseline ").append(b.stream().mapToLong(Run::amountMin).min().orElse(-1))
                .append("–").append(b.stream().mapToLong(Run::amountMax).max().orElse(-1)).append(", tuned ")
                .append(t.stream().mapToLong(Run::amountMin).min().orElse(-1)).append("–")
                .append(t.stream().mapToLong(Run::amountMax).max().orElse(-1)).append(". ")
                .append(discarded.isEmpty() ? "No run stalled." : "Set aside and repeated after a machine stall: "
                        + String.join("; ", discarded) + " (raw output under runs/discarded/).");
        return sb.toString();
    }

    static void row(StringBuilder sb, String label, List<Run> b, List<Run> t, ToDoubleFunction<Run> f, int dp, boolean higherBetter) {
        if (label.startsWith("p50 / p95")) {
            sb.append("| p50 / p95 latency (ms) | ").append(fmt(mean(b, Run::p50), 2)).append(" / ").append(fmt(mean(b, Run::p95), 2))
                    .append(" | ").append(fmt(mean(t, Run::p50), 2)).append(" / ").append(fmt(mean(t, Run::p95), 2)).append(" | ")
                    .append(change(b, t, Run::p50, false)).append(" / ").append(change(b, t, Run::p95, false)).append(" |\n");
            return;
        }
        sb.append("| ").append(label).append(" | ").append(cell(b, f, dp)).append(" | ").append(cell(t, f, dp)).append(" | ")
                .append(change(b, t, f, higherBetter)).append(" |\n");
    }

    static String cell(List<Run> rs, ToDoubleFunction<Run> f, int dp) {
        return fmt(mean(rs, f), dp) + " (" + rs.stream().map(r -> fmt(f.applyAsDouble(r), dp)).collect(Collectors.joining(", ")) + ")";
    }

    static String change(List<Run> b, List<Run> t, ToDoubleFunction<Run> f, boolean higherBetter) {
        double mb = mean(b, f), mt = mean(t, f);
        if (mb == 0) return "n/a";
        double pct = 100 * (mt - mb) / mb;
        double noise = Math.max(spread(b, f), spread(t, f));
        String word = Math.abs(mt - mb) <= noise ? "within noise" : ((mt > mb) == higherBetter ? "better" : "worse");
        return String.format(Locale.ROOT, "%+.1f %% (%s)", pct, word);
    }

    // ---------------------------------------------------------------- helpers

    static String recordToolchain(String java) throws Exception {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        String flags = capture(null, java, "-XX:+PrintFlagsFinal", "-version");
        String filtered = flags.lines().filter(l -> l.matches(".*\\b(MaxHeapSize|UseG1GC)\\b.*")).collect(Collectors.joining("\n"));
        String version = capture(null, java, "-version");
        return "Recorded " + ZonedDateTime.now() + "\n"
                + "$ java -XX:+PrintFlagsFinal -version | " + (WINDOWS ? "findstr \"MaxHeapSize UseG1GC\"" : "grep -E 'MaxHeapSize|UseG1GC'")
                + "\n" + filtered + "\n" + version.strip() + "\n"
                + "Machine: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + ", "
                + Runtime.getRuntime().availableProcessors() + " logical CPUs, " + (os.getTotalMemorySize() >> 20) + " MiB RAM\n"
                + "$ k6 version\n" + capture(null, "k6", "version").strip() + "\n";
    }

    static Path buildStage(Stage s) throws Exception {
        Path tree = WORK.resolve("worktrees").resolve(s.name);
        if (Files.exists(tree)) {
            run(ROOT, WORK.resolve("git-worktree.log"), List.of("git", "worktree", "remove", "--force", tree.toString()));
            deleteRecursively(tree);
        }
        run(ROOT, WORK.resolve("git-worktree.log"), List.of("git", "worktree", "prune"));
        check(run(ROOT, WORK.resolve("git-worktree-" + s.name + ".log"),
                List.of("git", "worktree", "add", "--detach", tree.toString(), s.ref)), "git worktree add " + s.ref);
        String commit = capture(null, "git", "-C", tree.toString(), "rev-parse", "--short", "HEAD").strip();
        System.out.printf("%-9s %s (%s): mvnw -q -B -DskipTests package%n", s.name, s.ref, commit);
        check(run(tree, WORK.resolve("build-" + s.name + ".log"), mvnw(tree, "-q", "-B", "-DskipTests", "package")),
                "build of " + s.ref + " (see target/sjv-l2/build-" + s.name + ".log)");
        if (!Files.exists(tree.resolve("target/ledger-settlement.jar"))) fail("Build of " + s.ref + " did not produce target/ledger-settlement.jar");
        return tree;
    }

    static void waitUntilServing(Process app, Path appLog) throws Exception {
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest probe = HttpRequest.newBuilder(URI.create("http://localhost:" + APP_PORT
                + "/payments/settlement?merchantId=READINESS-PROBE")).timeout(Duration.ofSeconds(5)).build();
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (!app.isAlive()) fail("The service exited during start-up. Last lines of its log:\n" + tail(appLog));
            try {
                if (client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 404) return;
            } catch (IOException notYet) {
                // not listening yet
            }
            Thread.sleep(250);
        }
        fail("The service did not start within 3 minutes:\n" + tail(appLog));
    }

    static void keepWindowsAwake() {
        if (!WINDOWS) return;
        try {
            var linker = java.lang.foreign.Linker.nativeLinker();
            var kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", java.lang.foreign.Arena.global());
            var setState = linker.downcallHandle(kernel32.find("SetThreadExecutionState").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT));
            int previous = (int) setState.invokeExact(0x80000000 | 0x00000001);
            System.out.println(previous != 0 ? "Windows will not sleep while the lab runs (keep the lid open)."
                    : "WARNING: Windows did not accept the keep-awake request; set Sleep to Never while this runs.");
        } catch (Throwable t) {
            System.out.println("WARNING: could not keep Windows awake (" + t + "); set Sleep to Never while this runs.");
        }
    }

    static void setAside(String id, int attempt) throws IOException {
        Path dir = RUNS.resolve("discarded");
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(RUNS)) {
            for (Path f : files.filter(f -> f.getFileName().toString().startsWith(id + ".")).toList()) {
                Files.move(f, dir.resolve(id + "-attempt" + attempt + f.getFileName().toString().substring(id.length())),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
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

    static String prettyClass(String jvmName) {
        return switch (jvmName) {
            case "[B" -> "byte[]";
            case "[C" -> "char[]";
            case "[I" -> "int[]";
            case "[J" -> "long[]";
            case "[Ljava.lang.Object;" -> "Object[]";
            default -> jvmName.startsWith("[L") ? jvmName.substring(2, jvmName.length() - 1) + "[]" : jvmName;
        };
    }

    static String frameName(RecordedFrame f) {
        return f.getMethod().getType().getName() + "." + f.getMethod().getName() + (f.getLineNumber() > 0 ? ":" + f.getLineNumber() : "");
    }

    static String top(Map<String, Long> m, long total, int k) {
        return m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(k)
                .map(e -> e.getKey().replace(",", " ") + " (" + fmt(100.0 * e.getValue() / Math.max(1, total), 1) + " %)")
                .collect(Collectors.joining(";"));
    }

    static String topList(Map<String, Long> m, long total, int k) {
        return m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(k)
                .map(e -> String.format(Locale.ROOT, "  %5.1f %%  %s", 100.0 * e.getValue() / Math.max(1, total), e.getKey()))
                .collect(Collectors.joining("\n")) + "\n";
    }

    static List<String> mvnw(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(WINDOWS ? List.of("cmd.exe", "/c", dir.resolve("mvnw.cmd").toString())
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
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS")).collect(Collectors.joining("\n")) + "\n";
        p.waitFor();
        if (saveTo != null) Files.writeString(saveTo, out);
        return out;
    }

    static void check(int exitCode, String what) {
        if (exitCode != 0) fail(what + " failed (exit " + exitCode + "). Logs are in target/sjv-l2/.");
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

    static double avg(List<Double> xs) {
        return xs.stream().mapToDouble(x -> x).average().orElse(0);
    }

    static double mean(List<Run> rs, ToDoubleFunction<Run> f) {
        return rs.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    static double spread(List<Run> rs, ToDoubleFunction<Run> f) {
        DoubleSummaryStatistics s = rs.stream().mapToDouble(f).summaryStatistics();
        return s.getMax() - s.getMin();
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
            return new Json(text).value();
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
                    i++;
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
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
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
