package javatesa6.ledgersettlement;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<PaymentEntity, String> {

    /** One row, even for a merchant with no payments: count 0 and gross 0. No PaymentEntity is loaded. */
    @Query("select new javatesa6.ledgersettlement.MerchantTotals(count(p), coalesce(sum(p.amountMinor), 0L)) "
            + "from PaymentEntity p where p.merchantId = :merchantId")
    MerchantTotals totalsForMerchant(@Param("merchantId") String merchantId);
}
