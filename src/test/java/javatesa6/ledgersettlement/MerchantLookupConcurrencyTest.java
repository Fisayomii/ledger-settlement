package javatesa6.ledgersettlement;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantLookupConcurrencyTest {

    private static final Map<String, String> TABLE = Map.of("MR-4471", "ACTIVE", "MR-9001", "SUSPENDED");

    @Test
    void concurrentFirstUseLoadsTheTableExactlyOnceAndNeverPins() throws Exception {
        // Unrecorded warm-up of the same scenario, so JDK class loading cannot appear as a pinned event below.
        startTogether(new MerchantLookup(slowDirectory(new AtomicInteger(), 50)), 50);

        AtomicInteger directoryCalls = new AtomicInteger();
        MerchantLookup lookup = new MerchantLookup(slowDirectory(directoryCalls, 300));
        List<RecordedEvent> pinned = new CopyOnWriteArrayList<>();
        List<Map<String, String>> seen;
        try (RecordingStream recording = new RecordingStream()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
            recording.onEvent("jdk.VirtualThreadPinned", pinned::add);
            recording.startAsync();
            seen = startTogether(lookup, 500);
            recording.stop(); // flushes the events recorded so far
        }

        assertThat(directoryCalls).as("downstream calls").hasValue(1);
        assertThat(seen).hasSize(500).allSatisfy(t -> assertThat(t).isSameAs(seen.getFirst()).isEqualTo(TABLE));
        assertThat(pinned).as("jdk.VirtualThreadPinned events: %s", pinned).isEmpty();
    }

    @Test
    void failedLoadIsRetriedOnTheNextCall() {
        AtomicInteger attempts = new AtomicInteger();
        MerchantLookup lookup = new MerchantLookup(() -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("directory down");
            return TABLE;
        });

        assertThatThrownBy(lookup::table).hasMessage("directory down");
        assertThat(lookup.statusOf("MR-4471")).isEqualTo("ACTIVE");
        assertThat(attempts).hasValue(2);
    }

    /** Starts the threads as virtual threads, releases them together with a latch, returns what each saw. */
    private static List<Map<String, String>> startTogether(MerchantLookup lookup, int threads) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Map<String, String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return lookup.table();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Map<String, String>> results = new ArrayList<>();
            for (Future<Map<String, String>> f : futures) results.add(f.get(10, TimeUnit.SECONDS));
            return results;
        }
    }

    /** Stands in for the HTTP merchant directory: answers after a delay and counts its calls. */
    private static MerchantDirectoryClient slowDirectory(AtomicInteger calls, long delayMillis) {
        return () -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return TABLE;
        };
    }
}
