package dev.blockboost.util;

import java.util.Arrays;

/**
 * Ring buffer of frame times in milliseconds with the statistics a player can
 * actually act on: mean, worst frame, percentiles (used for 1% lows), spike
 * count and a "sustained trend" delta.
 *
 * <p>Why hand-rolled: a percentile needs the whole window, and doing that with
 * {@code List<Double>} + sorting allocates on every sample. Here the window is a
 * fixed float array decided at construction, the only allocation is the scratch
 * copy used when someone asks for a percentile (i.e. at HUD refresh rate, not
 * per frame), and warm-up samples can be excluded so a chunk-loading hitch does
 * not live in the average forever.
 */
public final class RollingStats {

    public static final int NO_SAMPLE = -1;

    private final float[] window;
    private final float[] scratch;
    private int write;
    private int filled;

    private double sum;
    private float min = Float.MAX_VALUE;
    private float max = Float.MIN_VALUE;
    private long totalSamples;
    private int spikes;
    private long overBudgetFrames;
    private float budgetMs = Float.POSITIVE_INFINITY;

    /** Warm-up: the first {@code warmup} samples are counted but not mixed into the window. */
    private int warmupRemaining;
    private long discardedWarmup;

    public RollingStats(int windowFrames) {
        this(windowFrames, 0);
    }

    public RollingStats(int windowFrames, int warmupFrames) {
        int cap = Math.max(16, Math.min(1_000_000, windowFrames));
        this.window = new float[cap];
        this.scratch = new float[cap];
        this.warmupRemaining = Math.max(0, warmupFrames);
    }

    public void setBudgetMs(float ms) {
        this.budgetMs = ms <= 0 ? Float.POSITIVE_INFINITY : ms;
    }

    public void setWarmup(int frames) {
        this.warmupRemaining = Math.max(0, frames);
    }

    public void sample(double ms) {
        if (!(ms >= 0) || Float.isNaN((float) ms)) {
            return;
        }
        totalSamples++;
        if (ms > budgetMs) {
            overBudgetFrames++;
        }
        if (warmupRemaining > 0) {
            warmupRemaining--;
            discardedWarmup++;
            return;
        }
        float v = (float) ms;
        if (filled == window.length) {
            float leaving = window[write];
            sum -= leaving;
            if (leaving == min) {
                min = Float.MAX_VALUE;
                for (float f : window) {
                    if (f < min) {
                        min = f;
                    }
                }
            } else if (leaving == max) {
                max = -Float.MAX_VALUE;
                for (float f : window) {
                    if (f > max) {
                        max = f;
                    }
                }
            }
        } else {
            filled++;
        }
        window[write] = v;
        sum += v;
        if (v < min) {
            min = v;
        }
        if (v > max) {
            max = v;
        }
        write = (write + 1) % window.length;
    }

    public void reset() {
        Arrays.fill(window, 0f);
        write = 0;
        filled = 0;
        sum = 0;
        min = Float.MAX_VALUE;
        max = -Float.MAX_VALUE;
        spikes = 0;
        overBudgetFrames = 0;
        discardedWarmup = 0;
    }

    public int samples() {
        return filled;
    }

    public long totalSamples() {
        return totalSamples;
    }

    public long discardedWarmup() {
        return discardedWarmup;
    }

    public long overBudgetFrames() {
        return overBudgetFrames;
    }

    public double meanMs() {
        return filled == 0 ? NO_SAMPLE : sum / filled;
    }

    public double minMs() {
        return filled == 0 ? NO_SAMPLE : min;
    }

    public double maxMs() {
        return filled == 0 ? NO_SAMPLE : max;
    }

    /** {@code p} in 0..100; exact because the whole window is small. */
    public double percentileMs(double p) {
        if (filled == 0) {
            return NO_SAMPLE;
        }
        int n = filled;
        System.arraycopy(window, 0, scratch, 0, n);
        Arrays.sort(scratch, 0, n);
        double rank = Math.max(0, Math.min(0.999999, p / 100.0)) * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return scratch[lo];
        }
        float a = scratch[lo];
        float b = scratch[hi];
        return a + (b - a) * (rank - lo);
    }

    /** Frames per second at the given frame-time percentile (the "1% lows" number). */
    public double fpsAtPercentile(double p) {
        double ms = percentileMs(p);
        return ms < 0 ? NO_SAMPLE : 1000.0 / ms;
    }

    /**
     * Counts frames that took more than twice the median in the last pass. The
     * median is recomputed on demand (not per frame) to keep this cheap.
     */
    /** Number of samples the window can hold. */
    public int capacity() {
        return window.length;
    }

    /** Copies the live samples of {@code other} into this instance (snapshotting). */
    public void copyFrom(RollingStats other) {
        int n = Math.min(other.filled, this.window.length);
        for (int i = 0; i < n; i++) {
            this.window[i] = other.window[i];
        }
        this.filled = n;
        this.write = n % this.window.length;
        double sum = 0;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            sum += this.window[i];
            if (this.window[i] < min) {
                min = this.window[i];
            }
            if (this.window[i] > max) {
                max = this.window[i];
            }
        }
        this.sum = sum;
        this.min = n == 0 ? Float.MAX_VALUE : min;
        this.max = n == 0 ? -Float.MAX_VALUE : max;
        this.totalSamples = other.totalSamples;
    }

    public int countSpikes() {
        if (filled < 8) {
            return 0;
        }
        int n = filled;
        System.arraycopy(window, 0, scratch, 0, n);
        Arrays.sort(scratch, 0, n);
        float median = scratch[n / 2];
        int threshold = (int) Math.max(1, Math.round(Math.max(median * 2.0f, 50f)));
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (window[i] > threshold) {
                count++;
            }
        }
        spikes = count;
        return count;
    }

    public int lastSpikes() {
        return spikes;
    }

    /**
     * Slope of the second half of the window versus the first half, as a
     * percentage. The adaptive controller uses this to tell "the scene got
     * heavier" apart from "the device is throttling", which is the difference
     * between shedding work and doing nothing.
     */
    public double trendPercent() {
        if (filled < 16) {
            return 0;
        }
        double first = 0;
        double second = 0;
        int half = filled / 2;
        for (int i = 0; i < half; i++) {
            first += window[i];
        }
        for (int i = half; i < filled; i++) {
            second += window[i];
        }
        first /= half;
        second /= Math.max(1, filled - half);
        if (first <= 0) {
            return 0;
        }
        return (second - first) / first * 100.0;
    }

    public double meanFps() {
        double ms = meanMs();
        return ms < 0 ? NO_SAMPLE : 1000.0 / ms;
    }

    /** Compact one-line summary for logs and the report file. */
    public String summarize() {
        if (filled == 0) {
            return "no samples";
        }
        return String.format(java.util.Locale.ROOT,
                "avg %.1f ms (%.1f fps)  p50 %.1f  p95 %.1f  p99 %.1f  1%%low %.1f fps  worst %.1f  n=%d",
                meanMs(), meanFps(), percentileMs(50), percentileMs(95), percentileMs(99),
                fpsAtPercentile(99), max, filled);
    }
}
