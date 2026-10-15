package dev.yu.mwi.service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded background persistence queue. Batches for one player are strictly ordered; independent
 * players may use different workers. Only detached snapshots may cross this boundary.
 */
final class ProfilePersistenceQueue implements AutoCloseable {
    private static final int DEFAULT_CAPACITY = 256;
    private static final Duration DEFAULT_FLUSH_TIMEOUT = Duration.ofSeconds(30);

    private final ExecutorService executor;
    private final int capacity;
    private final Duration flushTimeout;
    private final Map<UUID, CompletableFuture<Void>> tails = new LinkedHashMap<>();
    private final Map<UUID, CoalescingIoAction> pendingCoalescing = new LinkedHashMap<>();
    private final Map<UUID, IOException> failures = new LinkedHashMap<>();
    private final Set<UUID> unhealthyPlayers = new HashSet<>();
    private final List<Long> completedLatenciesNanos = new ArrayList<>();
    private final List<Long> completedExecutionLatenciesNanos = new ArrayList<>();
    private final List<Long> activeEnqueuedNanos = new ArrayList<>();
    private int queuedTasks;
    private int coalescedTasks;
    private long failedTasks;
    private long queuedSubmissions;
    private long rejectedSubmissions;
    private int activeWorkers;
    private long oldestEnqueuedNanos;
    private boolean accepting = true;

    ProfilePersistenceQueue() {
        this(1, DEFAULT_CAPACITY, DEFAULT_FLUSH_TIMEOUT);
    }

    ProfilePersistenceQueue(int concurrency, int capacity, Duration flushTimeout) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        if (capacity < concurrency) {
            throw new IllegalArgumentException("capacity must be at least concurrency");
        }
        this.capacity = capacity;
        this.flushTimeout = java.util.Objects.requireNonNull(flushTimeout, "flushTimeout");
        if (flushTimeout.isNegative() || flushTimeout.isZero()) {
            throw new IllegalArgumentException("flushTimeout must be positive");
        }
        this.executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "mwi-profile-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized void submit(UUID playerId, IoAction action) {
        java.util.Objects.requireNonNull(playerId, "playerId");
        java.util.Objects.requireNonNull(action, "action");
        if (!accepting) {
            rejectedSubmissions++;
            throw new RejectedExecutionException("Profile persistence queue is stopping");
        }
        IOException priorFailure = failures.get(playerId);
        if (unhealthyPlayers.contains(playerId)) {
            rejectedSubmissions++;
            throw new RejectedExecutionException("Profile persistence is unhealthy for " + playerId, priorFailure);
        }
        if (action instanceof CoalescingIoAction newer) {
            CoalescingIoAction pending = pendingCoalescing.get(playerId);
            if (pending != null && pending.merge(newer)) {
                queuedSubmissions++;
                coalescedTasks++;
                return;
            }
        }
        if (queuedTasks >= capacity) {
            rejectedSubmissions++;
            throw new RejectedExecutionException(
                    "Profile persistence queue is full (capacity=" + capacity + ")");
        }
        queuedSubmissions++;

        long enqueuedNanos = System.nanoTime();
        if (queuedTasks == 0) {
            oldestEnqueuedNanos = enqueuedNanos;
        }
        queuedTasks++;
        activeEnqueuedNanos.add(enqueuedNanos);
        CompletableFuture<Void> previous = tails.getOrDefault(playerId, CompletableFuture.completedFuture(null));
        if (action instanceof CoalescingIoAction coalescing) {
            pendingCoalescing.put(playerId, coalescing);
        }
        CompletableFuture<Void> next = previous.thenRunAsync(() -> {
            long executionStarted = System.nanoTime();
            try {
                synchronized (ProfilePersistenceQueue.this) {
                    pendingCoalescing.remove(playerId, action);
                    activeWorkers++;
                }
                executionStarted = System.nanoTime();
                action.run();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            } finally {
                synchronized (ProfilePersistenceQueue.this) {
                    activeWorkers--;
                    completedExecutionLatenciesNanos.add(System.nanoTime() - executionStarted);
                    trimSamples(completedExecutionLatenciesNanos);
                }
            }
        }, executor);
        tails.put(playerId, next);
        next.whenComplete((ignored, failure) -> taskCompleted(playerId, next, enqueuedNanos, failure));
    }

    private synchronized void taskCompleted(
            UUID playerId, CompletableFuture<Void> task, long enqueuedNanos, Throwable failure) {
        queuedTasks--;
        activeEnqueuedNanos.remove(Long.valueOf(enqueuedNanos));
        if (failure != null) {
            if (unhealthyPlayers.add(playerId)) {
                failedTasks++;
                failures.put(playerId, asIOException(unwrap(failure)));
            }
        }
        completedLatenciesNanos.add(System.nanoTime() - enqueuedNanos);
        trimSamples(completedLatenciesNanos);
        tails.remove(playerId, task);
        oldestEnqueuedNanos = activeEnqueuedNanos.stream().mapToLong(Long::longValue).min().orElse(0L);
        notifyAll();
    }

    void throwIfFailed(UUID playerId) throws IOException {
        IOException failure;
        synchronized (this) {
            failure = failures.remove(playerId);
        }
        if (failure != null) {
            throw failure;
        }
    }

    void flush(UUID playerId) throws IOException {
        CompletableFuture<Void> tail;
        synchronized (this) {
            tail = tails.get(playerId);
        }
        IOException observed = null;
        try {
            await(tail, flushTimeout, "player " + playerId);
        } catch (IOException failure) {
            observed = failure;
        }
        IOException recorded;
        synchronized (this) {
            recorded = failures.remove(playerId);
            if (recorded == null && unhealthyPlayers.contains(playerId)) {
                recorded = new IOException("Profile persistence remains unhealthy for " + playerId
                        + "; restart and reconcile before another synchronous write");
            }
        }
        if (observed != null) {
            throw observed;
        }
        if (recorded != null) {
            throw recorded;
        }
    }

    void flushAll() throws IOException {
        flushAll(flushTimeout);
    }

    void flushAll(Duration timeout) throws IOException {
        CompletableFuture<?>[] snapshot;
        synchronized (this) {
            snapshot = tails.values().toArray(CompletableFuture[]::new);
        }
        await(CompletableFuture.allOf(snapshot), timeout, "all players");
        IOException aggregate = null;
        synchronized (this) {
            for (IOException failure : failures.values()) {
                if (aggregate == null) {
                    aggregate = new IOException("One or more queued profile writes failed");
                }
                aggregate.addSuppressed(failure);
            }
            failures.clear();
        }
        if (aggregate != null) {
            throw aggregate;
        }
    }

    synchronized Metrics metrics() {
        List<Long> latencies = completedLatenciesNanos.stream().sorted().toList();
        List<Long> executionLatencies = completedExecutionLatenciesNanos.stream().sorted().toList();
        long now = System.nanoTime();
        return new Metrics(
                queuedTasks,
                oldestEnqueuedNanos == 0L ? Duration.ZERO : Duration.ofNanos(now - oldestEnqueuedNanos),
                percentile(latencies, 0.95),
                percentile(latencies, 0.99),
                failedTasks,
                coalescedTasks,
                queuedSubmissions,
                rejectedSubmissions,
                activeWorkers,
                percentile(executionLatencies, 0.95),
                percentile(executionLatencies, 0.99));
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            accepting = false;
        }
        IOException failure = null;
        try {
            flushAll();
        } catch (IOException flushFailure) {
            failure = flushFailure;
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(flushTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                    IOException timeout = new IOException("Profile I/O workers did not stop within " + flushTimeout);
                    if (failure == null) {
                        failure = timeout;
                    } else {
                        failure.addSuppressed(timeout);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                IOException interruptedFailure = new IOException("Interrupted while stopping profile I/O", interrupted);
                if (failure == null) {
                    failure = interruptedFailure;
                } else {
                    failure.addSuppressed(interruptedFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void await(CompletableFuture<?> future, Duration timeout, String description) throws IOException {
        if (future == null) {
            return;
        }
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (CompletionException failure) {
            throw asIOException(unwrap(failure));
        } catch (java.util.concurrent.ExecutionException failure) {
            throw asIOException(unwrap(failure));
        } catch (TimeoutException failure) {
            throw new IOException("Timed out flushing profile persistence for " + description, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted flushing profile persistence for " + description, failure);
        }
    }

    private static Duration percentile(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return Duration.ZERO;
        }
        int index = Math.min(sortedNanos.size() - 1, (int) Math.ceil(sortedNanos.size() * percentile) - 1);
        return Duration.ofNanos(sortedNanos.get(index));
    }

    private static void trimSamples(List<Long> samples) {
        if (samples.size() > 2_048) {
            samples.subList(0, 1_024).clear();
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static IOException asIOException(Throwable failure) {
        return failure instanceof IOException io ? io : new IOException("Asynchronous profile write failed", failure);
    }

    record Metrics(
            int queueDepth,
            Duration oldestTaskAge,
            Duration p95WriteLatency,
            Duration p99WriteLatency,
            long failures,
            long coalesced,
            long queued,
            long rejected,
            int activeWorkers,
            Duration p95CommitLatency,
            Duration p99CommitLatency) {
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    interface CoalescingIoAction extends IoAction {
        /** Merges a newer not-yet-started snapshot into this action. */
        boolean merge(CoalescingIoAction newer);
    }
}
