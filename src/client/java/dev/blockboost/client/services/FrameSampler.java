package dev.blockboost.client.services;

/**
 * Frame interval measurement.
 *
 * <p>Sampled from the client loop, not from a wall-clock timer thread: the only
 * number that matters to a player is the interval between two frames the game
 * actually presented, and that is only observable on the render thread.
 *
 * <p>Cost: one {@code System.nanoTime()} pair and one array store per frame. No
 * allocation, no lock, no object creation, and percentile work happens only when
 * somebody asks (the HUD at its refresh rate, the report on demand).
 */
public final class FrameSampler {

    private final Object lock = new Object();
    private dev.blockboost.util.RollingStats stats;
    private dev.blockboost.util.RollingStats lifetime;

    private long lastNanos;
    private long frames;
    private long slowFrames;
    private long longestFrameNanos;
    private float targetMs = Float.POSITIVE_INFINITY;

    /** The most recent interval, so the HUD can show "last frame" without a copy. */
    private volatile double lastMs;

    public FrameSampler(int windowFrames) {
        synchronized (lock) {
            this.stats = new dev.blockboost.util.RollingStats(Math.max(16, windowFrames));
            this.lifetime = new dev.blockboost.util.RollingStats(Math.min(6000, Math.max(600, windowFrames * 8)));
        }
    }

    public void setWindow(int windowFrames) {
        synchronized (lock) {
            if (stats.capacity() != windowFrames) {
                dev.blockboost.util.RollingStats next =
                        new dev.blockboost.util.RollingStats(Math.max(16, windowFrames));
                next.copyFrom(stats);
                stats = next;
            }
        }
    }

    /** Marks the start of a frame; paired with {@link #sampleNow(long)}. */
    public void beginFrame(long nanos) {
        lastNanos = nanos;
    }

    public void sampleNow(long nanos) {
        long delta = lastNanos == 0 ? 0 : nanos - lastNanos;
        lastNanos = nanos;
        if (delta <= 0) {
            return;
        }
        frames++;
        double ms = delta / 1_000_000.0;
        lastMs = ms;
        synchronized (lock) {
            stats.sample(ms);
            lifetime.sample(ms);
        }
        if (ms > targetMs && delta > longestFrameNanos) {
            // count "missed the target" frames once per sample window
            slowFrames++;
        }
        if (delta > longestFrameNanos) {
            longestFrameNanos = delta;
        }
    }

    /** Feeds an externally measured duration (used by the benchmark harness). */
    public void sampleMs(double ms) {
        if (ms <= 0) {
            return;
        }
        frames++;
        synchronized (lock) {
            stats.sample(ms);
            lifetime.sample(ms);
        }
    }

    public void setTargetMs(float ms) {
        this.targetMs = ms <= 0 ? Float.POSITIVE_INFINITY : ms;
        synchronized (lock) {
            stats.setBudgetMs(targetMs);
            lifetime.setBudgetMs(targetMs);
        }
    }

    public void setWarmup(int frames) {
        synchronized (lock) {
            stats.setWarmup(frames);
        }
    }

    public void resetWindow() {
        synchronized (lock) {
            stats.reset();
            slowFrames = 0;
            longestFrameNanos = 0;
        }
    }

    public void resetAll() {
        synchronized (lock) {
            stats.reset();
            lifetime.reset();
            frames = 0;
            slowFrames = 0;
            longestFrameNanos = 0;
        }
    }

    public long framesMeasured() {
        return frames;
    }

    public long missedTargetFrames() {
        return slowFrames;
    }

    public double lastMs() {
        return lastMs;
    }

    public double meanMs() {
        synchronized (lock) {
            return stats.meanMs();
        }
    }

    public double meanFps() {
        synchronized (lock) {
            return stats.meanFps();
        }
    }

    /**
     * The "1% low" figure: the frame time at the 99th percentile, expressed as fps.
     * Reported only once the window has enough samples to mean something; a caller
     * that gets {@code -1} must print "insufficient data" instead of a made-up number.
     */
    public double low1PercentFps() {
        synchronized (lock) {
            if (stats.samples() < 60) {
                return -1;
            }
            return stats.fpsAtPercentile(99);
        }
    }

    public double percentileMs(double p) {
        synchronized (lock) {
            return stats.percentileMs(p);
        }
    }

    public double worstMs() {
        synchronized (lock) {
            return stats.maxMs();
        }
    }

    public double longestEverMs() {
        return longestFrameNanos / 1_000_000.0;
    }

    public int samples() {
        synchronized (lock) {
            return stats.samples();
        }
    }

    public double trendPercent() {
        synchronized (lock) {
            return stats.trendPercent();
        }
    }

    public double lifetimeMeanMs() {
        synchronized (lock) {
            return lifetime.meanMs();
        }
    }

    public String summarizeWindow() {
        synchronized (lock) {
            return stats.summarize();
        }
    }

    public dev.blockboost.util.RollingStats snapshotStats() {
        synchronized (lock) {
            dev.blockboost.util.RollingStats copy = new dev.blockboost.util.RollingStats(stats.capacity());
            copy.copyFrom(stats);
            return copy;
        }
    }

    /**
     * A stable fps estimate for the HUD: the mean of the whole window, rounded, or
     * "-" when there is nothing to average. Raw instantaneous fps is exactly the
     * number that misleads people tuning a phone, so it is not offered.
     */
    public String hudFpsText() {
        double fps = meanFps();
        if (fps < 0) {
            return "- fps";
        }
        return (int) Math.round(fps) + " fps";
    }

    public String hudFrameText() {
        double ms = meanMs();
        if (ms < 0) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT, "%.2f ms", ms);
    }
}
