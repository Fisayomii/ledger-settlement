## Primary Documentation Research
"The `@ServiceConnection` annotation instructs Spring Boot to extract the connection details from the container and use them to configure the underlying application property."
- *Spring Boot Reference Guide: Testcontainers Service Connections*, read on September 20, 2026.

## Agent Review
The AI agent was asked to generate a `GET /payments/{id}` endpoint. Here are the defects found in its output and the corresponding fixes:

1. **Defect:** Field injection with `@Autowired` instead of a constructor parameter.
   **Fix:** Removed the `@Autowired` field and ensured the `PaymentRepository` or `SettlementService` was injected via the class constructor, making the class easier to unit test without firing up the Spring context.
2. **Defect:** The JPA entity (`PaymentEntity`) was returned directly as the API type.
   **Fix:** Mapped the `PaymentEntity` to a dedicated `PaymentResponse` record before returning it. Returning database entities directly exposes database schema choices (like internal IDs or timestamps) to API consumers.
3. **Defect:** Missing constraint annotations (like `@PathVariable` missing `@NotBlank` or length restrictions).
   **Fix:** Added `@Pattern(regexp = "^PAY-.*")` and `@NotBlank` to the `@PathVariable String id` to reject malformed IDs immediately at the web layer before they hit the database.
4. **Defect:** An error body that is not a problem detail (e.g., throwing a generic `RuntimeException` resulting in a standard 500 HTML/JSON response).
   **Fix:** Created a custom `PaymentNotFoundException` and added an `@ExceptionHandler` in `ProblemHandler.java` to format the 404 response as a strict RFC 9457 `ProblemDetail`.