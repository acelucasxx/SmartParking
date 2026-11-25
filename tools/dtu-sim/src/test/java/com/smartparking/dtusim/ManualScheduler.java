package com.smartparking.dtusim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 手动推进时间的调度器：{@link #advance} 时按到期顺序执行任务。 */
final class ManualScheduler implements Scheduler {

    private record Task(long due, long seq, Runnable action) {}

    private final List<Task> tasks = new ArrayList<>();
    private long now;
    private long seq;

    @Override
    public synchronized Cancellable schedule(Runnable action, long delayMs) {
        Task task = new Task(now + delayMs, seq++, action);
        tasks.add(task);
        return () -> {
            synchronized (this) {
                tasks.remove(task);
            }
        };
    }

    void advance(long ms) {
        long target = now + ms;
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.stream()
                        .filter(t -> t.due() <= target)
                        .min(Comparator.comparingLong(Task::due).thenComparingLong(Task::seq))
                        .orElse(null);
                if (next == null) {
                    now = target;
                    return;
                }
                tasks.remove(next);
                now = next.due();
            }
            next.action().run();
        }
    }

    synchronized int pending() {
        return tasks.size();
    }
}
