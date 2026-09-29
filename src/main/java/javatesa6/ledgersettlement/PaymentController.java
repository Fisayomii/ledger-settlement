package javatesa6.ledgersettlement;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private final SettlementService service;

    public PaymentController(SettlementService service) {
        this.service = service;
    }

    record RecordPaymentRequest(
            @NotBlank String id,
            @NotBlank String merchantId,
            @Positive long amountMinor,
            @NotBlank String currency) {}

    record SettlementResponse(String merchantId, long amountOwedMinor) {}

    @PostMapping
    public ResponseEntity<Void> recordPayment(@Valid @RequestBody RecordPaymentRequest request) {
        PaymentEntity entity = new PaymentEntity(
                request.id(), request.merchantId(), request.amountMinor(), request.currency(), LocalDateTime.now()
        );
        service.recordPayment(entity);
        return ResponseEntity.created(URI.create("/payments/" + entity.getId())).build();
    }

    @GetMapping("/settlement")
    public SettlementResponse getSettlement(@RequestParam String merchantId) {
        long owed = service.calculateSettlement(merchantId);
        return new SettlementResponse(merchantId, owed);
    }
}
