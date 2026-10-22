package javatesa6.ledgersettlement;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The settlement result is unchanged by asking the database for count and sum instead of the rows. */
class SettlementServiceTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final MerchantLookup lookup = new MerchantLookup(() -> Map.of("MR-4471", "ACTIVE", "MR-9001", "SUSPENDED"));
    private final SettlementService service = new SettlementService(repository, lookup, 310);

    @Test
    void owedIsGrossMinusTheFeeOnTheDatabaseTotal() {
        when(repository.totalsForMerchant("MR-4471")).thenReturn(new MerchantTotals(1L, 128_450L));
        assertThat(service.calculateSettlement("MR-4471")).isEqualTo(124_469L); // 128 450 - 3 981 (3.10 %, truncated)
    }

    @Test
    void severalPaymentsAreSettledOnTheirSum() {
        when(repository.totalsForMerchant("MR-4471")).thenReturn(new MerchantTotals(2L, 128_450L + 1_000L));
        assertThat(service.calculateSettlement("MR-4471")).isEqualTo(129_450L - 129_450L * 310 / 10_000);
    }

    @Test
    void merchantWithoutPaymentsIsNotFound() {
        when(repository.totalsForMerchant("MR-0000")).thenReturn(new MerchantTotals(0L, 0L));
        assertThatThrownBy(() -> service.calculateSettlement("MR-0000")).isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void merchantThatIsNotActiveIsNotFound() {
        when(repository.totalsForMerchant("MR-9001")).thenReturn(new MerchantTotals(3L, 5_000L));
        assertThatThrownBy(() -> service.calculateSettlement("MR-9001")).isInstanceOf(MerchantNotFoundException.class);
    }
}
