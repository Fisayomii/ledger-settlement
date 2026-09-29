package javatesa6.ledgersettlement;

import java.util.Map;

public class MerchantLookup {
    // The static table required by the lab
    public static final Map<String, String> TABLE;

    static {
        // Blocks inside the class initializer on the first request
        TABLE = fetchTable();
    }

    private static Map<String, String> fetchTable() {
        try {
            // Simulate a slow 3-second network call to a downstream client
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Map.of("MR-4471", "Active");
    }
}