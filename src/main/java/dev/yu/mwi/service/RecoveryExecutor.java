package dev.yu.mwi.service;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Bounded single-flight execution. Futures contain detached data, never a Minecraft entity. */
final class RecoveryExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Map<UUID, Flight> running = new HashMap<>();
    private final Duration timeout;

    RecoveryExecutor(int concurrency, int capacity, Duration timeout) {
        this.timeout = timeout;
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), action -> {
                    Thread worker = new Thread(action, "mwi-auto-recovery"); worker.setDaemon(true); return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    CompletableFuture<dev.yu.mwi.storage.RecoveryInspection> submit(UUID owner,
            IoSupplier<dev.yu.mwi.storage.RecoveryInspection> action) {
        return submit(owner, "inspection", action);
    }

    synchronized CompletableFuture<dev.yu.mwi.storage.RecoveryInspection> submit(UUID owner, Object operation,
            IoSupplier<dev.yu.mwi.storage.RecoveryInspection> action) {
        Flight existing = running.get(owner);
        if (existing != null) {
            if (existing.operation().equals(operation)) return existing.result();
            return existing.result().handle((ignored, failure) -> null).thenCompose(ignored -> submit(owner, operation, action));
        }
        CompletableFuture<dev.yu.mwi.storage.RecoveryInspection> result = new CompletableFuture<>();
        Flight flight = new Flight(operation, result);
        running.put(owner, flight);
        try {
            executor.execute(() -> {
                try {
                    var value = action.get();
                    synchronized (this) { running.remove(owner, flight); }
                    result.complete(value);
                } catch (IOException | RuntimeException failure) {
                    synchronized (this) { running.remove(owner, flight); }
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            running.remove(owner, flight); result.completeExceptionally(failure);
        }
        return result;
    }

    @Override public void close() throws IOException {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow(); throw new IOException("Automatic recovery workers did not stop before deadline");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); executor.shutdownNow(); throw new IOException(interrupted);
        }
    }

    @FunctionalInterface interface IoSupplier<T> { T get() throws IOException; }
    private record Flight(Object operation, CompletableFuture<dev.yu.mwi.storage.RecoveryInspection> result) {}
}
