package javatesa6.ledgersettlement;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Dead-code elimination, shown on the service's own fee arithmetic, {@link SettlementService#owedMinor(long, int)}.
 * The broken benchmark is kept on purpose, clearly named, so the error can be reproduced.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(3)
public class SettlementArithmeticBenchmark {

    /** Describes the machine in results.json; perf/BenchLab.java passes it with -p machine=... */
    @Param("unrecorded")
    public String machine;

    // Inputs live in this @State object and are set in @Setup, so the JIT cannot fold them into the measured code.
    private long grossMinor;
    private int feeRateBasisPoints;

    @Setup
    public void setUp() {
        grossMinor = 129_450L;     // MR-4471's gross in the load-test database (it settles to 125 438)
        feeRateBasisPoints = 310;  // ledger.fee-rate in application.yaml
    }

    /** Reference point: an empty method, i.e. only JMH's own loop. */
    @Benchmark
    public void baseline() {
    }

    /** BROKEN ON PURPOSE: the result is computed and thrown away, so the JIT may remove the computation. */
    @Benchmark
    public void brokenDiscardsResult() {
        SettlementService.owedMinor(grossMinor, feeRateBasisPoints);
    }

    /** Fix 1: return the result; JMH consumes whatever a benchmark method returns. */
    @Benchmark
    public long fixedReturnsResult() {
        return SettlementService.owedMinor(grossMinor, feeRateBasisPoints);
    }

    /** Fix 2: hand the result to a Blackhole, for methods with several results or none to return. */
    @Benchmark
    public void fixedConsumesWithBlackhole(Blackhole blackhole) {
        blackhole.consume(SettlementService.owedMinor(grossMinor, feeRateBasisPoints));
    }
}
