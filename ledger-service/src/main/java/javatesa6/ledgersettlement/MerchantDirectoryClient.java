package javatesa6.ledgersettlement;

import java.util.Map;

/**
 * Downstream merchant directory: returns every merchant id with its status (for example ACTIVE).
 */
public interface MerchantDirectoryClient {

    Map<String, String> fetchTable();
}
