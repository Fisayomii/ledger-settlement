package javatesa6.ledgersettlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        MerchantTotals totals = repository.totalsForMerchant(merchantId);
        if (totals.paymentCount() == 0) {
            throw new MerchantNotFoundException(merchantId);
        }
        if (!"ACTIVE".equals(merchantLookup.statusOf(merchantId))) {
            throw new MerchantNotFoundException(merchantId);
        }

        long gross = totals.grossMinor();
        long fee = gross * feeRateBasisPoints / 10000;
        return gross - fee;
    }
}
