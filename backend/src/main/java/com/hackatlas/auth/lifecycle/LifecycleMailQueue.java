package com.hackatlas.auth.lifecycle;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.hackatlas.web.ApiFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Best-effort delivery: a process restart loses pending jobs; no secret payload is persisted. */
public final class LifecycleMailQueue implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleMailQueue.class);
    private final ThreadPoolExecutor executor;

    public LifecycleMailQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Mail queue capacity must be positive");
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    var thread = new Thread(task, "lifecycle-mail");
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((failed, error) -> LOG.error("Lifecycle background task terminated"));
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void submit(Runnable job) {
        try {
            executor.execute(() -> {
                try { job.run(); }
                catch (RuntimeException exception) { LOG.warn("Lifecycle background task failed"); }
            });
        } catch (RejectedExecutionException exception) {
            throw new ApiFailure(503, "SERVICE_UNAVAILABLE", "Отправка временно недоступна. Попробуйте позже.");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) LOG.warn("Lifecycle delivery shutdown timed out");
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
