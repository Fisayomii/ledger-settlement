package javatesa6.ledgersettlement;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The two methods extracted for the JMH benchmarks behave exactly as the code they came from. */
class SettlementArithmeticTest {

    @Test
    void owedIsGrossMinusTheTruncatedFee() {
        assertThat(SettlementService.owedMinor(128_450L, 310)).isEqualTo(124_469L); // fee 3 981.95 -> 3 981
        assertThat(SettlementService.owedMinor(0L, 310)).isZero();
        assertThat(SettlementService.owedMinor(99L, 310)).isEqualTo(96L);           // fee 3.069 -> 3
    }

    @Test
    void directoryBodyIsParsedIntoMerchantStatuses() {
        String body = """
                MR-4471=ACTIVE
                  MR-1002=ACTIVE \s

                MR-9001=SUSPENDED
                """;
        assertThat(HttpMerchantDirectoryClient.parseTable(body))
                .containsExactlyInAnyOrderEntriesOf(Map.of("MR-4471", "ACTIVE", "MR-1002", "ACTIVE", "MR-9001", "SUSPENDED"));
    }
}
