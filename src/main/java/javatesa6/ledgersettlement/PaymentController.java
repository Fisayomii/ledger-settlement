package javatesa6.ledgersettlement;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/payments")
public class PaymentController {
    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final SettlementService service;
    private final AtomicBoolean requestThreadLogged = new AtomicBoolean();

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
        if (requestThreadLogged.compareAndSet(false, true)) {
            Thread current = Thread.currentThread();
            log.info("Settlement requests run on thread {} (isVirtual={})", current, current.isVirtual());
        }
        long owed = service.calculateSettlement(merchantId);
        return new SettlementResponse(merchantId, owed);
    }
}
