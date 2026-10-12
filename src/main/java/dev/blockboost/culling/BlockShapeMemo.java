package dev.blockboost.culling;

/**
 * A fixed-size, per-frame memo for "is the block at this position a full opaque cube".
 *
 * <p>Why this exists: an occlusion test walks the same handful of walls over and over -
 * a mob farm has one or two big slabs of stone between the camera and everything else,
 * and thirty entities behind that wall means the same thirty probes of the same twelve
 * blocks. Answering each one with a block-state lookup (and the {@code BlockPos}
 * allocation it needs) is how an optimization ends up costing more than the rendering it
 * saved, which is exactly the regression this class exists to prevent.
 *
 * <p>Two properties make it safe rather than merely fast:
 *
 * <ul>
 *   <li><b>It is cleared every frame.</b> Staleness is bounded to one frame, so placing or
 *       breaking a block changes the answer immediately, and there is no invalidation
 *       protocol to get wrong. A memo that outlives a frame would need to know about
 *       every block update in the world, and knowing that is harder than doing the work.</li>
 *   <li><b>It is a hash set of "opaque" only.</b> A miss re-reads the world; only
 *       confirmed opaque positions are stored. A wrong answer can therefore only be
 *       "not opaque", which fails open (the object is drawn).</li>
 * </ul>
 *
 * <p>Open addressing with linear probing, power-of-two capacity, no allocation after
 * construction, and single-threaded by contract (the client thread owns it).
 */
public final class BlockShapeMemo {

    private static final int MIN_CAPACITY = 1024;
    private static final int MAX_CAPACITY = 1 << 17; // 131072 packed positions, ~1 MB

    private long[] keys;
    private int mask;
    private int size;
    private int hits;
    private int misses;

    public BlockShapeMemo(int capacityHint) {
        allocate(capacityHint);
    }

    private void allocate(int capacityHint) {
        int cap = MIN_CAPACITY;
        int wanted = Math.max(MIN_CAPACITY, capacityHint);
        while (cap < wanted && cap < MAX_CAPACITY) {
            cap <<= 1;
        }
        keys = new long[cap];
        mask = cap - 1;
        size = 0;
    }

    /** Called at the start of every frame: nothing in here is allowed to survive one. */
    public void beginFrame() {
        if (size == 0) {
            return;
        }
        java.util.Arrays.fill(keys, 0L);
        size = 0;
    }

    /**
     * Returns the memoised answer for {@code key}, or runs {@code query} and stores the
     * result if it says opaque.
     *
     * @param key packed block position; must never be 0 (see {@link #pack})
     */
    public boolean opaque(long key, Query query) {
        int index = spread(key) & mask;
        for (int probe = 0; probe < 8; probe++) {
            long slot = keys[index];
            if (slot == 0L) {
                misses++;
                if (query.isOpaque()) {
                    keys[index] = key;
                    size++;
                    return true;
                }
                return false;
            }
            if (slot == key) {
                hits++;
                return true;
            }
            index = (index + 1) & mask;
        }
        // Probe-capped: a table under heavy clustering stops answering rather than
        // spinning. Missing costs a lookup, not a frame.
        misses++;
        return query.isOpaque();
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    public int capacity() {
        return keys.length;
    }

    /** For tests and for the diagnostics line. */
    public void resetCounters() {
        hits = 0;
        misses = 0;
    }

    private static int spread(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    /** Packs a block position into a non-zero long. */
    public static long pack(int x, int y, int z) {
        long k = (((long) x & 0x3FFFFFFL) << 38) ^ (((long) y & 0xFFFFFFL) << 14) ^ ((long) z & 0x3FFF);
        return k == 0L ? 1L : k;
    }

    public interface Query {
        boolean isOpaque();
    }
}
