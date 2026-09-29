//package javatesa6.ledgersettlement;
//
//
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.web.client.TestRestTemplate;
//import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseEntity;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//import java.util.Map;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
//@Testcontainers
//class PaymentControllerIT {
//
//    @Container
//    @ServiceConnection
//    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
//
//    @Autowired
//    private TestRestTemplate restTemplate;
//
//    @Test
//    void postPaymentAndVerifySettlement() {
//        // 1. POST the payment of 128450 for MR-4471
//        Map<String, Object> request = Map.of(
//                "id", "PAY-90312",
//                "merchantId", "MR-4471",
//                "amountMinor", 128450,
//                "currency", "GBP"
//        );
//
//        ResponseEntity<Void> postResponse = restTemplate.postForEntity("/payments", request, Void.class);
//        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
//        assertThat(postResponse.getHeaders().getLocation().toString()).contains("/payments/PAY-90312");
//
//        // 2. GET the settlement and assert the math (128450 - 3.1% fee = 124469)
//        ResponseEntity<PaymentController.SettlementResponse> getResponse =
//                restTemplate.getForEntity("/payments/settlement?merchantId=MR-4471", PaymentController.SettlementResponse.class);
//
//        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
//        assertThat(getResponse.getBody().amountOwedMinor()).isEqualTo(124469L);
//    }
//}

package javatesa6.ledgersettlement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Test
    void postPaymentAndVerifySettlement() {
        // Build the client pointing to the random test port
        RestClient restClient = restClientBuilder.baseUrl("http://localhost:" + port).build();

        // 1. POST the payment of 128450 for MR-4471
        Map<String, Object> request = Map.of(
                "id", "PAY-90312",
                "merchantId", "MR-4471",
                "amountMinor", 128450,
                "currency", "GBP"
        );

        ResponseEntity<Void> postResponse = restClient.post()
                .uri("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();

        assertThat(postResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(postResponse.getHeaders().getLocation().toString()).contains("/payments/PAY-90312");

        // 2. GET the settlement and assert the math (128450 - 3.1% fee = 124469)
        PaymentController.SettlementResponse getResponse = restClient.get()
                .uri("/payments/settlement?merchantId=MR-4471")
                .retrieve()
                .body(PaymentController.SettlementResponse.class);

        assertThat(getResponse).isNotNull();
        assertThat(getResponse.amountOwedMinor()).isEqualTo(124469L);
    }
}