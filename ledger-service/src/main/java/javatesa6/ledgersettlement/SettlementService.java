package javatesa6.ledgersettlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SettlementService {
    private final PaymentRepository repository;
    private final MerchantLookup merchantLookup;
    private final int feeRateBasisPoints;

    public SettlementService(PaymentRepository repository,
                             MerchantLookup merchantLookup,
                             @Value("${ledger.fee-rate:310}") int feeRateBasisPoints) {
        this.repository = repository;
        this.merchantLookup = merchantLookup;
        this.feeRateBasisPoints = feeRateBasisPoints;
    }

    @Transactional
    public void recordPayment(PaymentEntity payment) {
        repository.save(payment);
    }

    public long calculateSettlement(String merchantId) {
        List<PaymentEntity> payments = repository.findByMerchantId(merchantId);
        if (payments.isEmpty()) {
            throw new MerchantNotFoundException(merchantId);
        }
        if (!"ACTIVE".equals(merchantLookup.statusOf(merchantId))) {
            throw new MerchantNotFoundException(merchantId);
        }

        long gross = payments.stream().mapToLong(PaymentEntity::getAmountMinor).sum();
        return owedMinor(gross, feeRateBasisPoints);
    }

    /**
     * What the merchant is owed: the gross minus the fee, all in long minor units (no double, no rounding mode).
     * The fee is truncated towards zero, so the merchant is never charged a fraction of a minor unit.
     */
    public static long owedMinor(long grossMinor, int feeRateBasisPoints) {
        long fee = grossMinor * feeRateBasisPoints / 10000;
        return grossMinor - fee;
    }
}
