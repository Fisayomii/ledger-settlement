package javatesa6.ledgersettlement;


import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

@Entity
public class PaymentEntity {
    @Id
    private String id;
    private String merchantId;
    private long amountMinor;
    private String currency;
    private LocalDateTime recordedAt;

    // JPA requires a no-argument constructor and a mutable class structure
    // to dynamically instantiate and populate the object from database rows.
    protected PaymentEntity() {}

    public PaymentEntity(String id, String merchantId, long amountMinor, String currency, LocalDateTime recordedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.recordedAt = recordedAt;
    }

    public String getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public long getAmountMinor() { return amountMinor; }
}
