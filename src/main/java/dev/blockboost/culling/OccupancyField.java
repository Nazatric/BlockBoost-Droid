package dev.blockboost.culling;

/**
 * A coarse occupancy grid around the camera: built in slices on the client thread,
 * read by worker threads through a published snapshot.
 *
 * <p>Why a grid at all: sampling {@code ClientLevel} from a worker thread is not
 * thread-safe, and a race on world data is how an optimization mod turns into item
 * duplication or a corrupted chunk. So the visibility work is split — (1) a small,
 * budgeted snapshot taken on the client thread, (2) an arbitrary amount of pure
 * arithmetic on that snapshot off the client thread. The snapshot is 4 KB
 * (32³ voxels, one bit each) and is only ever swapped, never mutated in place while
 * readers are looking at it.
 *
 * <p>Conservative by construction: a voxel is solid only when every sampled block in
 * it is a full opaque cube, so a one-block-thick wall does not hide anything in this
 * model. BlockBoost therefore culls less than a perfect occlusion culler — which is
 * the right trade, because a coarse grid's failure mode here is "something that
 * could have been skipped was rendered", never "a visible entity disappeared".
 */
public final class OccupancyField {

    /** Voxels per axis. */
    public static final int SIDE = 32;
    /** Blocks covered by one voxel (per axis). */
    public static final int BLOCKS_PER_VOXEL = 4;
    public static final int SPAN = SIDE * BLOCKS_PER_VOXEL;

    private static final int VOXELS = SIDE * SIDE * SIDE;
    private static final int WORDS = (VOXELS + 63) >>> 6;

    /** Written by the client thread. */
    private final long[] editing = new long[WORDS];
    /** Read by everyone else; only ever replaced wholesale. */
    private volatile long[] published = new long[WORDS];
    private volatile int publishedSolid;

    private volatile int originX;
    private volatile int originY;
    private volatile int originZ;
    private volatile double centerX;
    private volatile double centerY;
    private volatile double centerZ;

    private int sliceCursor;
    private volatile int solidVoxels;
    private volatile long version;
    private volatile boolean complete;

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public long version() {
        return version;
    }

    public int solidVoxels() {
        return solidVoxels;
    }

    /** True once every slice has been written since the last recenter. */
    public boolean isComplete() {
        return complete;
    }

    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    public double centerZ() {
        return centerZ;
    }

    public int sliceCursor() {
        return sliceCursor;
    }

    /**
     * Recenters the grid on a block position and drops everything. The published
     * snapshot is cleared too, so in-flight worker results become "unknown" and the
     * caller renders the entity, which is the safe answer during a camera jump.
     */
    public void recenter(int blockX, int blockY, int blockZ) {
        originX = alignDown(blockX - SPAN / 2);
        originY = alignDown(blockY - SPAN / 2);
        originZ = alignDown(blockZ - SPAN / 2);
        java.util.Arrays.fill(editing, 0L);
        java.util.Arrays.fill(published, 0L);
        this.centerX = blockX;
        this.centerY = blockY;
        this.centerZ = blockZ;
        this.solidVoxels = 0;
        this.publishedSolid = 0;
        this.sliceCursor = 0;
        this.complete = false;
        version++;
    }

    static int alignDown(int v) {
        return Math.floorDiv(v, BLOCKS_PER_VOXEL) * BLOCKS_PER_VOXEL;
    }

    /** Distance the camera moved since the grid was centered, in blocks. */
    public double centerDrift(double x, double y, double z) {
        double dx = x - centerX;
        double dy = y - centerY;
        double dz = z - centerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Rebuilds one slice of the grid, spending at most {@code maxVoxels} voxel
     * probes, then republishes. Called from the client thread only.
     *
     * @return true when the grid is now fully built
     */
    public boolean refreshNextSlice(int maxVoxels, LevelProbe probe) {
        if (complete || sliceCursor >= SIDE) {
            complete = true;
            return true;
        }
        int budget = Math.max(1, maxVoxels);
        int slice = sliceCursor;
        int base = slice * SIDE * SIDE;
        int solidDelta = 0;
        for (int i = 0; i < SIDE * SIDE && budget > 0; i++, budget--) {
            int vx = i / SIDE;
            int vz = i % SIDE;
            int wx = originX + vx * BLOCKS_PER_VOXEL;
            int wy = originY + slice * BLOCKS_PER_VOXEL;
            int wz = originZ + vz * BLOCKS_PER_VOXEL;
            boolean solid = isVoxelSolid(probe, wx, wy, wz);
            int index = base + i;
            int word = index >>> 6;
            long mask = 1L << (index & 63);
            long cur = editing[word];
            long next = solid ? (cur | mask) : (cur & ~mask);
            if (next != cur) {
                editing[word] = next;
                solidDelta += solid ? Long.bitCount(next & ~cur) : -Long.bitCount(cur & ~next);
            }
        }
        solidVoxels += solidDelta;
        sliceCursor++;
        if (sliceCursor >= SIDE) {
            publish();
            complete = true;
        }
        return complete;
    }

    /** Makes the current edit buffer visible to readers (workers) in one write. */
    public void publish() {
        System.arraycopy(editing, 0, published, 0, WORDS);
        publishedSolid = solidVoxels;
        version++;
    }

    private boolean isVoxelSolid(LevelProbe probe, int x, int y, int z) {
        int half = BLOCKS_PER_VOXEL >> 1;
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    if (!probe.isOpaqueCube(x + dx * BLOCKS_PER_VOXEL - half, y + dy * BLOCKS_PER_VOXEL - half,
                            z + dz * BLOCKS_PER_VOXEL - half)) {
                        return false;
                    }
                }
            }
        }
        return probe.isOpaqueCube(x + half - 1, y + half - 1, z + half - 1);
    }

    /** Voxel lookup by block position; false outside the field (fail open). */
    public boolean solidAtBlock(double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        int vx = Math.floorDiv(bx - originX, BLOCKS_PER_VOXEL);
        int vy = Math.floorDiv(by - originY, BLOCKS_PER_VOXEL);
        int vz = Math.floorDiv(bz - originZ, BLOCKS_PER_VOXEL);
        if (vx < 0 || vy < 0 || vz < 0 || vx >= SIDE || vy >= SIDE || vz >= SIDE) {
            return false;
        }
        int index = vy * SIDE * SIDE + vx * SIDE + vz;
        return (published[index >>> 6] & (1L << (index & 63))) != 0;
    }

    public boolean containsBlock(double x, double y, double z) {
        return x >= originX && x < originX + SPAN && y >= originY && y < originY + SPAN
                && z >= originZ && z < originZ + SPAN;
    }

    public void clear() {
        java.util.Arrays.fill(editing, 0L);
        java.util.Arrays.fill(published, 0L);
        solidVoxels = 0;
        publishedSolid = 0;
        sliceCursor = 0;
        complete = false;
        version++;
    }

    public int publishedSolidVoxels() {
        return publishedSolid;
    }

    public String describe() {
        return "voxel=" + BLOCKS_PER_VOXEL + " blocks, span=" + SPAN + " blocks, solid=" + publishedSolid + "/"
                + VOXELS + " slices=" + sliceCursor + "/" + SIDE + (complete ? "" : " (filling)");
    }
}
