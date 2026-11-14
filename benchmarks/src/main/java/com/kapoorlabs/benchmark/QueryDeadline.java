package com.kapoorlabs.benchmark;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class QueryDeadline implements AutoCloseable {
    private final long milliseconds;
    private final ScheduledExecutorService watchdog;

    QueryDeadline(long milliseconds) {
        if (milliseconds < 0) throw new IllegalArgumentException("Negative deadline");
        this.milliseconds = milliseconds;
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "benchmark-query-deadline");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        watchdog = scheduler;
    }

    QueryExecution.Sample run(QueryExecution.Query query, Runnable expired) throws Exception {
        if (milliseconds == 0) return query.run();
        AtomicBoolean active = new AtomicBoolean(true);
        Runnable expire = () -> {
            if (active.compareAndSet(true, false)) expired.run();
        };
        long start = System.nanoTime();
        ScheduledFuture<?> timer = watchdog.schedule(expire, milliseconds, TimeUnit.MILLISECONDS);
        try {
            QueryExecution.Sample result = query.run();
            if (System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(milliseconds)) expire.run();
            return result;
        } finally {
            active.set(false);
            timer.cancel(false);
        }
    }

    @Override public void close() { watchdog.shutdownNow(); }
}
