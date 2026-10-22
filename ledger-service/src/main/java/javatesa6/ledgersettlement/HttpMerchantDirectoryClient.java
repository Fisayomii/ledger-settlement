package javatesa6.ledgersettlement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls the merchant directory over HTTP: GET {baseUrl}/merchants returns one "id=status" pair per line.
 * Locally the directory is perf/MerchantDirectoryStub.java.
 */
public final class HttpMerchantDirectoryClient implements MerchantDirectoryClient {

    public static final String URL_PROPERTY = "ledger.merchant-directory.url";
    public static final String DEFAULT_URL = "http://localhost:9090";

    private final URI merchantsUri;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public HttpMerchantDirectoryClient(URI baseUrl) {
        this.merchantsUri = baseUrl.resolve("/merchants");
    }

    @Override
    public Map<String, String> fetchTable() {
        HttpRequest request = HttpRequest.newBuilder(merchantsUri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Merchant directory returned HTTP " + response.statusCode());
            }
            Map<String, String> table = new LinkedHashMap<>();
            response.body().lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .forEach(line -> {
                        int eq = line.indexOf('=');
                        table.put(line.substring(0, eq), line.substring(eq + 1));
                    });
            return Map.copyOf(table);
        } catch (IOException e) {
            throw new UncheckedIOException("Merchant directory unreachable at " + merchantsUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the merchant directory", e);
        }
    }
}
