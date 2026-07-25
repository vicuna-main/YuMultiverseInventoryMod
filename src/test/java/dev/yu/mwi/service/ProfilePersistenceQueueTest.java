package dev.yu.mwi.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilePersistenceQueueTest {
    @Test
    void preservesWriteOrderForEachPlayer() throws Exception {
        UUID playerId = UUID.randomUUID();
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());

        try (ProfilePersistenceQueue queue = new ProfilePersistenceQueue()) {
            queue.submit(playerId, () -> writes.add(1));
            queue.submit(playerId, () -> writes.add(2));
            queue.submit(playerId, () -> writes.add(3));
            queue.flush(playerId);
        }

        assertEquals(List.of(1, 2, 3), writes);
    }

    @Test
    void surfacesBackgroundWriteFailures() throws Exception {
        UUID playerId = UUID.randomUUID();
        ProfilePersistenceQueue queue = new ProfilePersistenceQueue();
        queue.submit(playerId, () -> {
            throw new IOException("disk unavailable");
        });

        IOException failure = assertThrows(IOException.class, () -> queue.flush(playerId));
        assertEquals("disk unavailable", failure.getMessage());
        queue.close();
    }

    @Test
    void delayedIoDoesNotBlockSubmissionAndPreservesPlayerOrder() throws Exception {
        UUID playerId = UUID.randomUUID();
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch release = new CountDownLatch(1);
        try (ProfilePersistenceQueue queue = new ProfilePersistenceQueue(2, 8, Duration.ofSeconds(2))) {
            long started = System.nanoTime();
            queue.submit(playerId, () -> {
                await(release);
                writes.add(1);
            });
            queue.submit(playerId, () -> writes.add(2));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 50);
            release.countDown();
            queue.flush(playerId);
            assertEquals(List.of(1, 2), writes);
        }
    }

    @Test
    void independentPlayersCanProgressConcurrently() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        try (ProfilePersistenceQueue queue = new ProfilePersistenceQueue(2, 8, Duration.ofSeconds(2))) {
            queue.submit(UUID.randomUUID(), () -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            queue.submit(UUID.randomUUID(), secondFinished::countDown);
            assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
            releaseFirst.countDown();
        }
    }

    @Test
    void fullQueueRejectsWithoutRunningIoOnCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ProfilePersistenceQueue queue = new ProfilePersistenceQueue(1, 1, Duration.ofSeconds(2));
        queue.submit(UUID.randomUUID(), () -> await(release));
        assertThrows(RejectedExecutionException.class,
                () -> queue.submit(UUID.randomUUID(), () -> { }));
        assertEquals(1, queue.metrics().queueDepth());
        release.countDown();
        queue.close();
    }

    @Test
    void pendingCoalescibleActionsMergeToNewestValue() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch blocker = new CountDownLatch(1);
        AtomicInteger persisted = new AtomicInteger();
        try (ProfilePersistenceQueue queue = new ProfilePersistenceQueue(1, 8, Duration.ofSeconds(2))) {
            queue.submit(UUID.randomUUID(), () -> await(blocker));
            queue.submit(playerId, new MergingAction(1, persisted));
            queue.submit(playerId, new MergingAction(2, persisted));
            blocker.countDown();
            queue.flush(playerId);
            assertEquals(2, persisted.get());
            assertEquals(1, queue.metrics().coalesced());
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IOException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static final class MergingAction implements ProfilePersistenceQueue.CoalescingIoAction {
        private int value;
        private final AtomicInteger persisted;

        private MergingAction(int value, AtomicInteger persisted) {
            this.value = value;
            this.persisted = persisted;
        }

        @Override
        public boolean merge(ProfilePersistenceQueue.CoalescingIoAction newer) {
            value = ((MergingAction) newer).value;
            return true;
        }

        @Override
        public void run() {
            persisted.set(value);
        }
    }
}
