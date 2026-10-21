import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Local stand-in for the downstream merchant directory used by the Ledger settlement service.
 * GET /merchants answers with one "id=status" line per merchant after a fixed delay, which models
 * the directory's slow cold response (3 s by default).
 *
 * Run on its own:  java perf/MerchantDirectoryStub.java [port] [delayMillis]
 */
public class MerchantDirectoryStub {

    static final String TABLE = """
            MR-4471=ACTIVE
            MR-1002=ACTIVE
            MR-9001=SUSPENDED
            """;

    public static HttpServer start(int port, Duration delay) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/merchants", exchange -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = TABLE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        Duration delay = Duration.ofMillis(args.length > 1 ? Long.parseLong(args[1]) : 3000);
        start(port, delay);
        System.out.printf("Merchant directory stub on http://localhost:%d/merchants (delay %d ms). Ctrl+C to stop.%n",
                port, delay.toMillis());
    }
}
