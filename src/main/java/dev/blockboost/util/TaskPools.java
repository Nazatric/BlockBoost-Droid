package dev.blockboost.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded worker pools for BlockBoost's own background work.
 *
 * <p>Rules this class enforces, because an unbounded queue is exactly how an
 * optimization mod turns a stutter problem into an OutOfMemoryError:
 * <ul>
 *   <li>fixed thread count, chosen from the device profile, never "one per
 *       job";</li>
 *   <li>bounded array-backed queue; when it is full the oldest pending job is
 *       discarded (it is by definition the least relevant one) and the drop is
 *       counted so the diagnostics screen can show it;</li>
 *   <li>daemon threads with a real name, so a stuck pool cannot keep the JVM
 *       alive and a profiler can tell what a thread is for;</li>
 *   <li>explicit {@link #shutdown()} on client stop; {@link #pauseForIdle()}
 *       while no world is loaded, so the mod does not burn cycles in menus.</li>
 * </ul>
 */
public final class TaskPools implements AutoCloseable {

    /** A pool plus the numbers needed to prove it stayed bounded. */
    public final class Pool {
        private final ThreadPoolExecutor executor;
        private final String name;
        private final int maxQueue;
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();

        Pool(String name, int threads, int queueCapacity) {
            this.name = name;
            this.maxQueue = queueCapacity;
            this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(queueCapacity), new Factory(name),
                    (r, exec) -> {
                        // Drop-oldest: pull the head and retry once. No unbounded growth.
                        if (exec.getQueue().poll() != null) {
                            dropped.incrementAndGet();
                        }
                        try {
                            exec.execute(r);
                        } catch (java.util.concurrent.RejectedExecutionException gone) {
                            dropped.incrementAndGet();
                        }
                    });
            this.executor.allowCoreThreadTimeOut(true);
        }

        public void submit(Runnable task) {
            if (paused || executor.isShutdown()) {
                dropped.incrementAndGet();
                return;
            }
            submitted.incrementAndGet();
            executor.execute(() -> {
                try {
                    task.run();
                    completed.incrementAndGet();
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    dev.blockboost.BlockBoost.LOG.warn("BlockBoost worker task failed in {}", name, t);
                }
            });
        }

        public int queueLength() {
            return executor.getQueue().size();
        }

        public int activeThreads() {
            return executor.getActiveCount();
        }

        public int maxQueueCapacity() {
            return maxQueue;
        }

        public long submitted() {
            return submitted.get();
        }

        public long completed() {
            return completed.get();
        }

        public long dropped() {
            return dropped.get();
        }

        public long failed() {
            return failed.get();
        }

        public void setThreads(int threads) {
            int n = Math.max(1, Math.min(16, threads));
            try {
                if (n > executor.getCorePoolSize()) {
                    executor.setMaximumPoolSize(n);
                    executor.setCorePoolSize(n);
                } else {
                    executor.setCorePoolSize(n);
                    executor.setMaximumPoolSize(n);
                }
            } catch (RuntimeException ignored) {
                // IllegalArgumentException on a bad order is not worth crashing a game.
            }
        }

        public void clearPending() {
            int n = executor.getQueue().size();
            executor.getQueue().clear();
            dropped.addAndGet(n);
        }

        void shutdown() {
            executor.shutdownNow();
        }

        public String describe() {
            return name + " threads=" + executor.getCorePoolSize() + " queue=" + queueLength() + "/"
                    + maxQueue + " done=" + completed.get() + " dropped=" + dropped.get();
        }
    }

    private static final class Factory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger();

        Factory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "blockboost-" + prefix + '-' + seq.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    }

    private final java.util.List<Pool> pools = new java.util.ArrayList<>();
    private volatile boolean paused;

    public Pool create(String name, int threads, int queueCapacity) {
        Pool p = new Pool(name, Math.max(1, threads), Math.max(8, queueCapacity));
        synchronized (pools) {
            pools.add(p);
        }
        return p;
    }

    public void pauseForIdle(boolean paused) {
        this.paused = paused;
        if (paused) {
            synchronized (pools) {
                for (Pool p : pools) {
                    p.clearPending();
                }
            }
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public java.util.List<Pool> pools() {
        synchronized (pools) {
            return java.util.List.copyOf(pools);
        }
    }

    public long droppedTotal() {
        long sum = 0;
        synchronized (pools) {
            for (Pool p : pools) {
                sum += p.dropped();
            }
        }
        return sum;
    }

    public int queueTotal() {
        int sum = 0;
        synchronized (pools) {
            for (Pool p : pools) {
                sum += p.queueLength();
            }
        }
        return sum;
    }

    @Override
    public void close() {
        synchronized (pools) {
            for (Pool p : pools) {
                p.shutdown();
            }
            pools.clear();
        }
    }
}
