package dev.yu.mwi.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeightedLruCacheTest {
    @Test
    void accessOrderAndEntryLimitEvictTheLeastRecentlyUsedEntry() {
        WeightedLruCache<String, Long> cache = new WeightedLruCache<>(2, 100);
        cache.put("a", 1L, 10, (current, candidate) -> false);
        cache.put("b", 2L, 10, (current, candidate) -> false);
        assertEquals(1L, cache.get("a"));

        cache.put("c", 3L, 10, (current, candidate) -> false);

        assertNull(cache.get("b"));
        assertEquals(1L, cache.get("a"));
        assertEquals(3L, cache.get("c"));
        assertEquals(1L, cache.stats().evictions());
        assertTrue(cache.invariant().valid(), cache.invariant()::description);
    }

    @Test
    void replacementAndOversizedEntriesKeepExactNonNegativeWeight() {
        WeightedLruCache<String, Long> cache = new WeightedLruCache<>(4, 100);
        cache.put("a", 1L, 70, (current, candidate) -> false);
        cache.put("a", 2L, 20, (current, candidate) -> false);
        assertEquals(20L, cache.stats().bytes());

        cache.put("a", 3L, 101, (current, candidate) -> false);

        assertNull(cache.get("a"));
        assertEquals(0L, cache.stats().bytes());
        assertEquals(1L, cache.stats().oversizedRejected());
        assertTrue(cache.invariant().valid(), cache.invariant()::description);
    }

    @Test
    void concurrentGetPutInvalidateAndStatsPreserveTheCompositeInvariant() throws Exception {
        WeightedLruCache<Integer, Long> cache = new WeightedLruCache<>(16, 512);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int workerId = worker;
                tasks.add(workers.submit(() -> {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    for (int index = 0; index < 2_000; index++) {
                        int key = (workerId * 31 + index) & 31;
                        cache.put(key, (long) index, 8L + (index & 7), (current, candidate) -> false);
                        cache.get(key);
                        if ((index & 7) == 0) {
                            cache.remove((key + 1) & 31);
                        }
                        cache.stats();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }

        assertTrue(cache.invariant().valid(), cache.invariant()::description);
    }
}
