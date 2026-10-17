package dev.yu.mwi.service;

import dev.yu.mwi.storage.RecoveryInspection;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticRecoveryConcurrencyTest {
    @Test void failedQueueIsReplayedInOrderBeforeItsFenceCanBeReleased() throws Exception {
        UUID owner = UUID.randomUUID();
        AtomicBoolean diskHealthy = new AtomicBoolean();
        List<Integer> committed = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        try (var queue = new ProfilePersistenceQueue(1, 8, Duration.ofSeconds(2))) {
            queue.submit(owner, () -> {
                entered.countDown(); await(proceed);
                if (!diskHealthy.get()) throw new IOException("disk full");
                committed.add(1);
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            queue.submit(owner, () -> committed.add(2));
            long fence = queue.fence(owner); proceed.countDown();
            queue.drainForRecovery(owner, fence);
            assertThrows(IOException.class, () -> queue.releaseRecovery(owner, fence));
            assertThrows(RejectedExecutionException.class, () -> queue.submit(owner, () -> committed.add(3)));
            assertThrows(IOException.class, () -> queue.retryFailedWrites(owner, fence));
            diskHealthy.set(true);
            assertTrue(queue.retryFailedWrites(owner, fence));
            assertEquals(List.of(1, 2), committed);
            queue.releaseRecovery(owner, fence);
            assertThrows(IOException.class, () -> queue.releaseRecovery(owner, fence));
            queue.submit(owner, () -> committed.add(3)); queue.flush(owner);
            assertEquals(List.of(1, 2, 3), committed);
        }
    }

    @Test void duplicateRetrySharesOneTaskButNewConnectionWaitsForItsOwnPreservation() throws Exception {
        UUID owner = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        try (var executor = new RecoveryExecutor(2, 4, Duration.ofSeconds(2))) {
            var first = executor.submit(owner, "session-one", () -> {
                executed.incrementAndGet(); entered.countDown(); await(proceed); return result(1);
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var duplicate = executor.submit(owner, "session-one", () -> { fail("Duplicate recovery executed"); return result(99); });
            assertSame(first, duplicate);
            var reconnected = executor.submit(owner, "session-two", () -> { executed.incrementAndGet(); return result(2); });
            assertFalse(reconnected.isDone());
            assertEquals(3, executor.submit(UUID.randomUUID(), () -> result(3)).get(1, TimeUnit.SECONDS).generation());
            proceed.countDown();
            assertEquals(1, first.get(1, TimeUnit.SECONDS).generation());
            assertEquals(2, reconnected.get(1, TimeUnit.SECONDS).generation());
            assertEquals(2, executed.get());
        }
    }

    @Test void boundedRecoveryQueueRejectsWithoutExecutingIoOnCaller() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        try (var executor = new RecoveryExecutor(1, 1, Duration.ofSeconds(2))) {
            var first = executor.submit(UUID.randomUUID(), () -> { entered.countDown(); await(proceed); return result(1); });
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var queued = executor.submit(UUID.randomUUID(), () -> result(2));
            var rejected = executor.submit(UUID.randomUUID(), () -> { fail("Rejected I/O ran"); return result(3); });
            assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS));
            proceed.countDown(); first.get(1, TimeUnit.SECONDS); queued.get(1, TimeUnit.SECONDS);
        }
    }
    private static RecoveryInspection result(long generation) { return new RecoveryInspection(generation, Map.of(), Map.of(), List.of(), 0, "RESOLVED"); }
    private static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IOException("Test deadline exceeded"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
    }
}
