package dev.blockboost.util;

/**
 * Fixed-capacity, allocation-free cache from a packed long key to a byte value
 * plus a freshness stamp.
 *
 * <p>This is the structure the visibility systems use. It exists instead of a
 * {@code HashMap<Long, X>} for three reasons that matter on a phone:
 * <ul>
 *   <li>no boxing and no per-entry objects, so a farm with 800 entities does not
 *       add 800 allocations per refresh to the young generation;</li>
 *   <li>a hard ceiling on memory: the table is a fixed number of slots, decided
 *       once, and can never grow;</li>
 *   <li>O(1) bulk invalidation via {@link #invalidate()}: bumping the generation
 *       makes every entry stale without touching the arrays, which is what world
 *       changes and resource reloads need.</li>
 * </ul>
 *
 * <p>Collision policy is last-write-wins on the probe chain (an entry is
 * overwritten, never accumulated), so a full table degrades into more misses —
 * a miss means "do the normal work", which is always the safe answer. Reads and
 * writes are unsynchronized; the cache is only touched from the client thread,
 * while workers write through {@link Snapshot} hand-off (see the culling
 * services).
 */
public final class LongByteCache {

    private static final byte EMPTY = 0;
    private static final byte USED = 1;

    private final long[] keys;
    private final byte[] values;
    private final byte[] state;
    private final int[] generation;
    private final int mask;

    private int size;
    private int hits;
    private int misses;
    private int overwrites;
    private int gen = 1;

    public LongByteCache(int requestedCapacity) {
        int cap = tableSizeFor(Math.max(64, requestedCapacity));
        this.keys = new long[cap];
        this.values = new byte[cap];
        this.state = new byte[cap];
        this.generation = new int[cap];
        this.mask = cap - 1;
    }

    static int tableSizeFor(int wanted) {
        int n = Integer.highestOneBit(Math.max(2, wanted - 1)) << 1;
        return n <= 0 ? (1 << 30) : n;
    }

    public int capacity() {
        return keys.length;
    }

    public int size() {
        return size;
    }

    public int hits() {
        return hits;
    }

    public int misses() {
        return misses;
    }

    /** Entries that had to evict a previous value on their probe chain. */
    public int overwrites() {
        return overwrites;
    }

    public float hitRate() {
        int total = hits + misses;
        return total == 0 ? 0f : (float) hits / total;
    }

    /** Stores {@code value} for {@code key}. Returns true if the value was new. */
    public boolean put(long key, byte value) {
        int idx = index(key);
        for (int i = 0; i < 8; i++) {
            int slot = (idx + i) & mask;
            if (state[slot] == EMPTY) {
                state[slot] = USED;
                keys[slot] = key;
                values[slot] = value;
                generation[slot] = gen;
                size++;
                return true;
            }
            if (keys[slot] == key) {
                if (values[slot] == value && generation[slot] == gen) {
                    return false;
                }
                values[slot] = value;
                generation[slot] = gen;
                return true;
            }
        }
        // Probe chain exhausted: replace the first slot. Bounded work, no growth.
        int slot = idx & mask;
        if (state[slot] == EMPTY) {
            size++;
        } else {
            overwrites++;
        }
        state[slot] = USED;
        keys[slot] = key;
        values[slot] = value;
        generation[slot] = gen;
        return true;
    }

    /** @return the stored value, or {@code miss} when absent or invalidated. */
    public int get(long key, int miss) {
        int idx = index(key);
        for (int i = 0; i < 8; i++) {
            int slot = (idx + i) & mask;
            if (state[slot] == EMPTY) {
                misses++;
                return miss;
            }
            if (keys[slot] == key && generation[slot] == gen) {
                hits++;
                return values[slot];
            }
        }
        misses++;
        return miss;
    }

    public boolean contains(long key) {
        return get(key, -128) != -128;
    }

    /** Drops every entry in O(1) by advancing the generation counter. */
    public void invalidate() {
        gen++;
        if (gen == 0) {
            // Wrapped: clear stamps so the new generation cannot alias old rows.
            java.util.Arrays.fill(generation, 0);
            gen = 1;
        }
        size = 0;
        hits = 0;
        misses = 0;
    }

    public void clearStats() {
        hits = 0;
        misses = 0;
        overwrites = 0;
    }

    private int index(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return (int) h & mask;
    }

    /** Packs two ints (typically chunk-relative block coords + entity index). */
    public static long pack(int a, int b) {
        return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
    }

    public static long packPosition(double x, double y, double z) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        return ((long) (ix & 0x3FFFFFF) << 38) | ((long) (iz & 0x3FFFFFF) << 12) | (iy & 0xFFF);
    }

    /** Human-readable occupancy, used by the diagnostics report. */
    public String describe() {
        return "capacity=" + capacity() + " entries=" + size + " hitRate="
                + Math.round(hitRate() * 100) + "% overwrites=" + overwrites;
    }
}
