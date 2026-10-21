package javatesa6.ledgersettlement;

import java.net.URI;
import java.util.Map;

/**
 * SJV-L1 scenario 6 - planted defect. The merchant table is now fetched from the merchant directory
 * inside the class initializer, so the first request that touches this class makes the downstream
 * HTTP call while running <clinit>, and every other request needing the class waits for it.
 */
public final class MerchantLookup {

    static final Map<String, String> TABLE;

    static {
        String url = System.getProperty(HttpMerchantDirectoryClient.URL_PROPERTY, HttpMerchantDirectoryClient.DEFAULT_URL);
        TABLE = new HttpMerchantDirectoryClient(URI.create(url)).fetchTable();
    }

    private MerchantLookup() {
    }

    public static String statusOf(String merchantId) {
        return TABLE.get(merchantId);
    }
}
