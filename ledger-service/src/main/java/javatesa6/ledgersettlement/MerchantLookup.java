package javatesa6.ledgersettlement;

import java.util.Map;

/**
 * Merchant status table from the merchant directory, loaded once when the bean is created.
 */
public final class MerchantLookup {

    private final Map<String, String> table;

    public MerchantLookup(MerchantDirectoryClient directory) {
        this.table = Map.copyOf(directory.fetchTable());
    }

    public Map<String, String> table() {
        return table;
    }

    public String statusOf(String merchantId) {
        return table.get(merchantId);
    }
}
