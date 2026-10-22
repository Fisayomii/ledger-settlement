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
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Three real methods of the service:
 * - serialisation: the SettlementResponse every GET /payments/settlement returns, written with Jackson 3 as Spring does;
 * - string and collection work: HttpMerchantDirectoryClient.parseTable, which turns the merchant directory into a map;
 * - the settlement calculation: SettlementService.calculateSettlement.
 * Every benchmark returns its result, so JMH consumes it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(3)
public class LedgerHotPathBenchmark {

    /** Describes the machine in results.json; perf/BenchLab.java passes it with -p machine=... */
    @Param("unrecorded")
    public String machine;

    private JsonMapper json;
    private PaymentController.SettlementResponse response;
    private String directoryBody;

    @Setup
    public void setUp() {
        json = JsonMapper.builder().build();
        response = new PaymentController.SettlementResponse("MR-4471", 125_438L);
        directoryBody = merchantDirectory(5_000);
    }

    @Benchmark
    public String serialiseSettlementResponse() {
        return json.writeValueAsString(response);
    }

    @Benchmark
    public Map<String, String> parseMerchantDirectory() {
        return HttpMerchantDirectoryClient.parseTable(directoryBody);
    }

    @Benchmark
    public long calculateSettlement(Settlement in) {
        return in.service.calculateSettlement(in.merchantId);
    }

    /** A merchant with 2 payments (MR-4471 as it is in the load-test database) and a busy one with 200. */
    @State(Scope.Benchmark)
    public static class Settlement {

        @Param({"2", "200"})
        public int payments;

        SettlementService service;
        String merchantId;

        @Setup
        public void setUp() {
            merchantId = "MR-4471";
            List<PaymentEntity> rows = new ArrayList<>();
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 9, 0);
            if (payments == 2) {
                rows.add(new PaymentEntity("PAY-90312", merchantId, 128_450L, "GBP", start));
                rows.add(new PaymentEntity("PAY-90313", merchantId, 1_000L, "GBP", start.plusHours(3)));
            } else {
                Random random = new Random(4471);
                for (int i = 0; i < payments; i++) {
                    rows.add(new PaymentEntity("PAY-" + (100_000 + i), merchantId, 1_000L + random.nextInt(499_001),
                            "GBP", start.plusMinutes(7L * i)));
                }
            }
            List<PaymentEntity> loaded = List.copyOf(rows);
            // The repository answers from memory, as if Postgres had returned these rows; the database is not measured.
            PaymentRepository repository = (PaymentRepository) Proxy.newProxyInstance(
                    PaymentRepository.class.getClassLoader(), new Class<?>[]{PaymentRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("findByMerchantId")) return loaded;
                        throw new UnsupportedOperationException(method.getName());
                    });
            MerchantLookup lookup = new MerchantLookup(() -> Map.of(merchantId, "ACTIVE"));
            service = new SettlementService(repository, lookup, 310);
        }
    }

    /** The merchant directory's body in the stub's format: "MR-00001=ACTIVE", every 20th merchant SUSPENDED. */
    static String merchantDirectory(int merchants) {
        StringBuilder body = new StringBuilder(merchants * 16);
        for (int i = 1; i <= merchants; i++) {
            body.append(String.format("MR-%05d=%s", i, i % 20 == 0 ? "SUSPENDED" : "ACTIVE")).append('\n');
        }
        return body.toString();
    }
}
