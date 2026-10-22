import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SJV-L3: builds the project and runs the JMH suite once, then writes the tables in docs/benchmarks.md.
 * From the repository root, with JDK 25:
 *
 *     java perf/BenchLab.java            build, then run the full suite (about 65 minutes)
 *     java perf/BenchLab.java --report   only rewrite docs/benchmarks.md from benchmarks/results.json
 *
 * The suite itself runs exactly as the lab states, from the benchmarks directory:
 *     java -jar target/benchmarks.jar -prof gc -rf json -rff results.json -p machine=...
 * -prof gc adds bytes allocated per operation; -p machine records the machine in results.json next to the JDK,
 * which JMH records itself. The console output is kept in benchmarks/results-console.txt.
 */
public class BenchLab {

    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path BENCH = ROOT.resolve("benchmarks");
    static final Path RESULTS = BENCH.resolve("results.json");
    static final Path CONSOLE = BENCH.resolve("results-console.txt");
    static final Path TOOLCHAIN = BENCH.resolve("toolchain.txt");
    static final Path DOC = ROOT.resolve("docs/benchmarks.md");
    static final String PREFIX = "javatesa6.ledgersettlement.";

    public static void main(String[] args) throws Exception {
        if (!Files.exists(BENCH.resolve("pom.xml"))) fail("Run this from the repository root: java perf/BenchLab.java");
        boolean reportOnly = args.length > 0 && args[0].equals("--report");
        if (!reportOnly) {
            if (Runtime.version().feature() < 25) fail("Run with JDK 25 (found " + Runtime.version() + ").");
            keepWindowsAwake();
            String machine = machine();
            Files.writeString(TOOLCHAIN, toolchain(machine));
            System.out.println(Files.readString(TOOLCHAIN));

            step("Building both modules: ./mvnw -B package");
            Path buildLog = BENCH.resolve("build.log");
            int code = new ProcessBuilder(mvnw("-B", "package")).directory(ROOT.toFile()).redirectErrorStream(true)
                    .redirectOutput(buildLog.toFile()).start().waitFor();
            if (code != 0) fail("The build failed. Last lines of benchmarks/build.log:\n" + tail(buildLog, 40));
            if (!Files.exists(BENCH.resolve("target/benchmarks.jar"))) fail("benchmarks/target/benchmarks.jar was not built.");

            step("Running the suite (8 benchmarks x 3 forks x 15 iterations of 10 s: about 65 minutes)");
            List<String> cmd = List.of(exe("java"), "-jar", "target/benchmarks.jar", "-prof", "gc", "-rf", "json",
                    "-rff", "results.json", "-p", "machine=" + machine);
            System.out.println("benchmarks> java -jar target/benchmarks.jar -prof gc -rf json -rff results.json -p machine=\"" + machine + "\"");
            Files.deleteIfExists(RESULTS);
            long started = System.nanoTime();
            Process p = new ProcessBuilder(cmd).directory(BENCH.toFile()).redirectErrorStream(true).start();
            try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 var out = new PrintWriter(Files.newBufferedWriter(CONSOLE, StandardCharsets.UTF_8))) {
                out.println("benchmarks> java -jar target/benchmarks.jar -prof gc -rf json -rff results.json -p machine=\"" + machine + "\"");
                for (String line; (line = in.readLine()) != null; ) {
                    out.println(line);
                    if (line.startsWith("# Benchmark:") || line.startsWith("# Fork:") || line.startsWith("# Parameters:")
                            || line.startsWith("# Run progress") || line.startsWith("Result ") || line.startsWith("# Run complete")) {
                        System.out.println(line);
                    }
                }
            }
            if (p.waitFor() != 0) fail("The JMH run failed; see benchmarks/results-console.txt.");
            long minutes = Duration.ofNanos(System.nanoTime() - started).toMinutes();
            System.out.println("Suite finished in " + minutes + " min.");
            if (minutes > 100) {
                System.out.println("WARNING: far longer than the expected 65 minutes. The machine may have slept or been busy; "
                        + "check the error bars before using these results.");
            }
            Files.writeString(TOOLCHAIN, Files.readString(TOOLCHAIN) + "Suite wall-clock time: " + minutes + " min\n");
        }
        if (!Files.exists(RESULTS)) fail("benchmarks/results.json is missing.");
        report();
        System.out.println("""

                Done. Next:
                  git add docs/benchmarks.md benchmarks/results.json benchmarks/results-console.txt benchmarks/toolchain.txt
                  git commit -m "Record JMH results"
                Then send docs/benchmarks.md and benchmarks/results.json back for the final write-up.
                """);
    }

    // ------------------------------------------------------------------ results.json -> docs

    record Result(String name, String params, double score, double error, double lo, double hi, String unit,
                  double alloc, double allocError, List<List<Double>> raw, Map<String, Object> json) {
        String label() {
            return name + (params.isEmpty() ? "" : " (" + params + ")");
        }
    }

    static void report() throws IOException {
        List<Object> runs = asList(Json.parse(Files.readString(RESULTS)));
        List<Result> results = new ArrayList<>();
        for (Object o : runs) {
            Map<String, Object> b = asMap(o);
            String name = str(b.get("benchmark")).replace(PREFIX, "");
            Map<String, Object> params = b.containsKey("params") ? asMap(b.get("params")) : Map.of();
            String p = params.entrySet().stream().filter(e -> !e.getKey().equals("machine"))
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
            Map<String, Object> pm = asMap(b.get("primaryMetric"));
            List<Object> ci = asList(pm.get("scoreConfidence"));
            Map<String, Object> sec = b.containsKey("secondaryMetrics") ? asMap(b.get("secondaryMetrics")) : Map.of();
            Map<String, Object> norm = sec.containsKey("gc.alloc.rate.norm") ? asMap(sec.get("gc.alloc.rate.norm")) : Map.of();
            List<List<Double>> raw = new ArrayList<>();
            for (Object fork : asList(pm.get("rawData"))) raw.add(asList(fork).stream().map(BenchLab::d).toList());
            results.add(new Result(name, p, d(pm.get("score")), d(pm.get("scoreError")), d(ci.get(0)), d(ci.get(1)),
                    str(pm.get("scoreUnit")), norm.isEmpty() ? Double.NaN : d(norm.get("score")),
                    norm.isEmpty() ? Double.NaN : d(norm.get("scoreError")), raw, b));
        }
        if (results.isEmpty()) fail("results.json holds no benchmarks.");
        Map<String, Object> first = results.getFirst().json;

        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("environment", environment(first));
        sections.put("results", resultsTable(results));
        sections.put("dce", dce(results));
        sections.put("comparisons", comparisons(results));
        sections.put("steadiness", steadiness(results));

        String doc = Files.readString(DOC);
        for (var e : sections.entrySet()) {
            Matcher m = Pattern.compile("(<!-- BEGIN:" + e.getKey() + " -->)(.*?)(<!-- END:" + e.getKey() + " -->)", Pattern.DOTALL)
                    .matcher(doc);
            if (m.find()) doc = m.replaceFirst(Matcher.quoteReplacement(m.group(1) + "\n" + e.getValue().strip() + "\n" + m.group(3)));
        }
        Files.writeString(DOC, doc);
        System.out.println("Updated docs/benchmarks.md from benchmarks/results.json (" + results.size() + " results).");
    }

    static String environment(Map<String, Object> b) {
        Map<String, Object> params = b.containsKey("params") ? asMap(b.get("params")) : Map.of();
        String toolchain = "";
        try {
            toolchain = Files.exists(TOOLCHAIN) ? Files.readString(TOOLCHAIN).strip() : "";
        } catch (IOException ignored) {
            // optional
        }
        return "From `results.json`: JMH " + str(b.get("jmhVersion")) + ", JDK " + str(b.get("jdkVersion")) + " (" + str(b.get("vmName"))
                + " " + str(b.get("vmVersion")) + "), machine `" + params.getOrDefault("machine", "not recorded") + "`, "
                + num(b.get("forks")) + " forks × " + num(b.get("warmupIterations")) + " warm-up iterations of "
                + str(b.get("warmupTime")) + " + " + num(b.get("measurementIterations")) + " measured iterations of "
                + str(b.get("measurementTime")) + ", " + num(b.get("threads")) + " thread.\n\n```text\n" + toolchain + "\n```";
    }

    static String resultsTable(List<Result> rs) {
        StringBuilder sb = new StringBuilder("Score ± error is JMH's 99.9 % confidence interval over 3 forks × 10 measured "
                + "iterations (30 samples). Allocation is `gc.alloc.rate.norm` from `-prof gc`, in the same run.\n\n"
                + "| Benchmark | Time per operation | 99.9 % interval | Allocated per operation |\n"
                + "| :--- | ---: | :--- | ---: |\n");
        for (Result r : rs) {
            sb.append("| `").append(r.label()).append("` | ").append(pm(r.score, r.error)).append(' ').append(r.unit)
                    .append(" | [").append(sig(r.lo)).append(", ").append(sig(r.hi)).append("] | ")
                    .append(Double.isNaN(r.alloc) ? "n/a" : pm(r.alloc, r.allocError) + " B/op").append(" |\n");
        }
        return sb.toString();
    }

    static String dce(List<Result> rs) {
        Result base = find(rs, "SettlementArithmeticBenchmark.baseline", ""), broken = find(rs, "SettlementArithmeticBenchmark.brokenDiscardsResult", ""),
                ret = find(rs, "SettlementArithmeticBenchmark.fixedReturnsResult", ""), bh = find(rs, "SettlementArithmeticBenchmark.fixedConsumesWithBlackhole", "");
        if (base == null || broken == null || ret == null || bh == null) return "_The dead-code-elimination benchmarks are missing from results.json._";
        return "| Benchmark | Time per operation | Allocated |\n| :--- | ---: | ---: |\n"
                + row(base, "empty method (JMH's loop only)") + row(broken, "**broken**: result discarded")
                + row(ret, "fixed: result returned") + row(bh, "fixed: result consumed by a Blackhole")
                + "\nThe broken benchmark's interval " + verdictPhrase(broken, base) + " the empty method's. "
                + "Returning the result makes the measured time " + sig(ret.score / broken.score) + "× the broken figure ("
                + sig(ret.score) + " against " + sig(broken.score) + " " + ret.unit + "), so the broken number understated "
                + "the real cost by " + sig(100 * (ret.score - broken.score) / ret.score) + " %. The two fixes are "
                + (overlap(ret, bh) ? "inconclusive against each other (their intervals overlap)." : "distinguishable (their intervals do not overlap).");
    }

    static String row(Result r, String what) {
        return "| `" + r.name.substring(r.name.indexOf('.') + 1) + "`, " + what + " | " + pm(r.score, r.error) + " " + r.unit + " | "
                + (Double.isNaN(r.alloc) ? "n/a" : pm(r.alloc, r.allocError) + " B/op") + " |\n";
    }

    static String comparisons(List<Result> rs) {
        String[][] pairs = {
                {"SettlementArithmeticBenchmark.brokenDiscardsResult", "", "SettlementArithmeticBenchmark.baseline", ""},
                {"SettlementArithmeticBenchmark.brokenDiscardsResult", "", "SettlementArithmeticBenchmark.fixedReturnsResult", ""},
                {"SettlementArithmeticBenchmark.fixedReturnsResult", "", "SettlementArithmeticBenchmark.fixedConsumesWithBlackhole", ""},
                {"LedgerHotPathBenchmark.calculateSettlement", "payments=2", "LedgerHotPathBenchmark.calculateSettlement", "payments=200"},
                {"LedgerHotPathBenchmark.serialiseSettlementResponse", "", "LedgerHotPathBenchmark.calculateSettlement", "payments=2"},
        };
        StringBuilder sb = new StringBuilder("A comparison counts only when the two 99.9 % intervals do not overlap; "
                + "otherwise it is marked **inconclusive**.\n\n| A | B | Intervals | Verdict |\n| :--- | :--- | :--- | :--- |\n");
        for (String[] p : pairs) {
            Result a = find(rs, p[0], p[1]), b = find(rs, p[2], p[3]);
            if (a == null || b == null) continue;
            sb.append("| `").append(short_(a)).append("` | `").append(short_(b)).append("` | [").append(sig(a.lo)).append(", ")
                    .append(sig(a.hi)).append("] vs [").append(sig(b.lo)).append(", ").append(sig(b.hi)).append("] ").append(a.unit)
                    .append(" | ").append(overlap(a, b) ? "**inconclusive** (intervals overlap)"
                            : a.score >= b.score ? "different: A takes " + sig(a.score / b.score) + "× as long as B"
                            : "different: B takes " + sig(b.score / a.score) + "× as long as A").append(" |\n");
        }
        return sb.toString();
    }

    /** Fork-to-fork spread (why forks matter) and drift inside each fork (whether warm-up was long enough). */
    static String steadiness(List<Result> rs) {
        StringBuilder sb = new StringBuilder("Per benchmark: the mean of each fork, and the largest drift inside any fork "
                + "between its first three and last three measured iterations. Drift smaller than the error means the "
                + "iterations had settled when measurement began.\n\n"
                + "| Benchmark | Fork means | Fork spread | Largest drift in a fork | Error | Settled? |\n| :--- | :--- | ---: | ---: | ---: | :--- |\n");
        for (Result r : rs) {
            List<Double> means = r.raw.stream().map(f -> f.stream().mapToDouble(x -> x).average().orElse(Double.NaN)).toList();
            double spread = means.stream().mapToDouble(x -> x).max().orElse(0) - means.stream().mapToDouble(x -> x).min().orElse(0);
            double drift = 0;
            for (List<Double> f : r.raw) {
                if (f.size() < 6) continue;
                double early = f.subList(0, 3).stream().mapToDouble(x -> x).average().orElse(0);
                double late = f.subList(f.size() - 3, f.size()).stream().mapToDouble(x -> x).average().orElse(0);
                drift = Math.max(drift, Math.abs(late - early));
            }
            sb.append("| `").append(r.label()).append("` | ").append(means.stream().map(BenchLab::sig).collect(Collectors.joining(", ")))
                    .append(" | ").append(pct(spread, r.score)).append(" | ").append(pct(drift, r.score)).append(" | ")
                    .append(pct(r.error, r.score)).append(" | ").append(drift <= r.error ? "yes" : "**no: drift exceeds the error**")
                    .append(" |\n");
        }
        return sb.toString();
    }

    static String verdictPhrase(Result a, Result b) {
        return overlap(a, b) ? "overlaps" : "does not overlap";
    }

    static boolean overlap(Result a, Result b) {
        return a.lo <= b.hi && b.lo <= a.hi;
    }

    static Result find(List<Result> rs, String name, String params) {
        return rs.stream().filter(r -> r.name.equals(name) && r.params.equals(params)).findFirst().orElse(null);
    }

    static String short_(Result r) {
        return r.name.substring(r.name.indexOf('.') + 1) + (r.params.isEmpty() ? "" : " (" + r.params + ")");
    }

    static String pm(double v, double e) {
        return sig(v) + " ± " + (Double.isNaN(e) ? "n/a" : sig(e));
    }

    static String pct(double part, double whole) {
        return whole == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f %%", 100 * part / whole);
    }

    /** Four significant figures, never in exponent form (results go from about 0.0003 to over 1000 µs/op). */
    static String sig(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "n/a";
        if (v == 0) return "0";
        return new BigDecimal(v).round(new MathContext(4)).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ helpers

    static String machine() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        String cpu = Optional.ofNullable(System.getenv("PROCESSOR_IDENTIFIER")).orElse(System.getProperty("os.arch"));
        return (System.getProperty("os.name") + " " + System.getProperty("os.version") + " / " + cpu + " / "
                + Runtime.getRuntime().availableProcessors() + " logical CPUs / " + (os.getTotalMemorySize() >> 20) + " MiB RAM")
                .replace(",", ";");
    }

    static String toolchain(String machine) throws Exception {
        return "Recorded " + ZonedDateTime.now() + "\n$ java -version\n" + capture(exe("java"), "-version")
                + "$ mvnw -v\n" + capture(mvnw("-v").toArray(String[]::new)) + "Machine: " + machine + "\n";
    }

    static void keepWindowsAwake() {
        if (!WINDOWS) return;
        try {
            var linker = java.lang.foreign.Linker.nativeLinker();
            var kernel32 = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", java.lang.foreign.Arena.global());
            var setState = linker.downcallHandle(kernel32.find("SetThreadExecutionState").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT));
            // ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED: keep the machine and the screen on.
            int previous = (int) setState.invokeExact(0x80000000 | 0x00000001 | 0x00000002);
            System.out.println(previous != 0 ? "Windows will keep the machine and the screen awake while the suite runs (keep the lid open)."
                    : "WARNING: Windows did not accept the keep-awake request; set screen and sleep to Never while this runs.");
        } catch (Throwable t) {
            System.out.println("WARNING: could not keep Windows awake (" + t + "); set screen and sleep to Never while this runs.");
        }
    }

    static List<String> mvnw(String... args) {
        List<String> cmd = new ArrayList<>(WINDOWS ? List.of("cmd.exe", "/c", ROOT.resolve("mvnw.cmd").toString())
                : List.of("sh", ROOT.resolve("mvnw").toString()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    static String exe(String name) {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? name + ".exe" : name).toString();
    }

    static String capture(String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines()
                .filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS")).collect(Collectors.joining("\n")) + "\n";
        p.waitFor();
        return out;
    }

    static String tail(Path file, int n) throws IOException {
        List<String> lines = Files.readAllLines(file);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    static void step(String s) {
        System.out.println("\n=== " + s);
    }

    static void fail(String message) {
        System.err.println("\nERROR: " + message);
        System.exit(1);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    static double d(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) return Double.parseDouble(s);   // JMH writes NaN and Infinity as strings
        return Double.NaN;
    }

    static String num(Object o) {
        double v = d(o);
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static String str(Object o) {
        return o == null ? "?" : o.toString();
    }

    /** Just enough JSON for JMH's results file. */
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
