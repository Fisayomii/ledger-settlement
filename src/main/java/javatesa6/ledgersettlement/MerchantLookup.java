package javatesa6.ledgersettlement;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Merchant status table, fetched from the merchant directory at most once and never in a class initializer.
 * Concurrent first callers wait on a CompletableFuture, so a waiting virtual thread unmounts instead of pinning.
 * A failed fetch is not cached; the next call retries. MerchantConfig loads it eagerly at start-up.
 */
public final class MerchantLookup {

    private final MerchantDirectoryClient directory;
    private final AtomicReference<CompletableFuture<Map<String, String>>> table = new AtomicReference<>();

    public MerchantLookup(MerchantDirectoryClient directory) {
        this.directory = directory;
    }

    public Map<String, String> table() {
        while (true) {
            CompletableFuture<Map<String, String>> current = table.get();
            if (current != null) {
                return await(current);
            }
            CompletableFuture<Map<String, String>> mine = new CompletableFuture<>();
            if (table.compareAndSet(null, mine)) {
                try {
                    Map<String, String> loaded = Map.copyOf(directory.fetchTable());
                    mine.complete(loaded);
                    return loaded;
                } catch (RuntimeException | Error e) {
                    table.compareAndSet(mine, null);
                    mine.completeExceptionally(e);
                    throw e;
                }
            }
        }
    }

    public String statusOf(String merchantId) {
        return table().get(merchantId);
    }

    private static Map<String, String> await(CompletableFuture<Map<String, String>> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
