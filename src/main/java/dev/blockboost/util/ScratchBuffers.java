package dev.blockboost.util;

import java.util.Arrays;

/**
 * Reusable float buffers for the hot geometry/ray paths, with a hard ceiling.
 *
 * <p>Unpooled scratch arrays are the classic mod-authored allocation source:
 * every visibility test builds a list of sample points, and at 120 Hz over 800
 * entities that is real garbage. Pooling them per thread removes the garbage;
 * the per-thread byte ceiling is what stops "reuse" from becoming "retain".
 * Anything above {@code maxBytesPerThread} is allocated fresh and left to the
 * collector, which keeps the total footprint predictable.
 */
public final class ScratchBuffers {

    private static final int MAX_CACHED = 1 << 16; // floats

    private final ThreadLocal<float[]> holder = new ThreadLocal<>();
    private final ThreadLocal<Integer> heldBytes = ThreadLocal.withInitial(() -> 0);
    private final int maxBytesPerThread;

    private volatile long reuses;
    private volatile long freshAllocations;
    private volatile long refusals;

    public ScratchBuffers(int maxKbPerThread) {
        this.maxBytesPerThread = Math.max(64, Math.min(65536, maxKbPerThread)) * 1024;
    }

    /**
     * A float array of at least {@code minFloats} length. Never larger than
     * {@code MAX_CACHED}; above that the caller gets a fresh array, which is fine
     * because those paths are not per-frame.
     */
    public float[] floats(int minFloats) {
        int need = Math.max(1, minFloats);
        if (need > MAX_CACHED) {
            refusals++;
            return new float[need];
        }
        float[] cur = holder.get();
        if (cur != null && cur.length >= need) {
            reuses++;
            return cur;
        }
        int size = Integer.highestOneBit(need - 1) << 1;
        size = Math.max(16, Math.min(MAX_CACHED, size));
        if (cur != null && cur.length >= size) {
            size = cur.length;
        }
        int bytes = size * Float.BYTES;
        if (bytes > maxBytesPerThread) {
            refusals++;
            return new float[need];
        }
        float[] arr = cur != null && cur.length >= size ? cur : new float[size];
        holder.set(arr);
        heldBytes.set(bytes);
        freshAllocations++;
        return arr;
    }

    /** Zeroes whatever is cached; called on world change so stale tails cannot leak. */
    public void release() {
        float[] cur = holder.get();
        if (cur != null) {
            Arrays.fill(cur, 0f);
        }
        heldBytes.set(0);
    }

    public void closeAll() {
        holder.remove();
        heldBytes.remove();
    }

    public long reuses() {
        return reuses;
    }

    public long freshAllocations() {
        return freshAllocations;
    }

    public long refusals() {
        return refusals;
    }

    public int maxBytesPerThread() {
        return maxBytesPerThread;
    }
}
