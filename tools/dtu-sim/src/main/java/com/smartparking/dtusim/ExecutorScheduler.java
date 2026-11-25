package com.smartparking.dtusim;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 基于 {@link ScheduledExecutorService} 的调度器，所有设备共用。 */
public final class ExecutorScheduler implements Scheduler, AutoCloseable {

    private final ScheduledExecutorService executor;

    public ExecutorScheduler(int threads) {
        this.executor = Executors.newScheduledThreadPool(threads, r -> {
            Thread t = new Thread(r, "dtu-sim");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMs) {
        var future = executor.schedule(task, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
