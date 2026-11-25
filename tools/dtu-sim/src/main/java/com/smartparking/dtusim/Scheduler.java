package com.smartparking.dtusim;

/** 延时任务调度；运行时用线程池，单测用手动推进的时钟，保证结果可重复。 */
public interface Scheduler {

    /** 延时执行，返回可取消的句柄 */
    Cancellable schedule(Runnable task, long delayMs);

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
