package dev.blockboost.client.culling;

import java.util.concurrent.atomic.AtomicLong;

import dev.blockboost.client.services.DiagnosticsService;
import dev.blockboost.culling.BlockShapeMemo;
import dev.blockboost.culling.LevelProbe;
import dev.blockboost.culling.OcclusionRaycaster;
import dev.blockboost.culling.OccupancyField;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.device.DeviceCapabilities;
import dev.blockboost.util.LongByteCache;
import dev.blockboost.util.TaskPools;

/**
 * Shared visibility engine for entity and block entity culling.
 *
 * <p>Two execution modes, because they solve different problems:
 * <ul>
 *   <li><b>on-thread, budgeted (default).</b> A bounded number of exact ray tests
 *       per frame, run where reading world state is legal, results cached and reused
 *       for a few ticks. A 300-mob farm is walked over a handful of frames, so no
 *       single frame pays for the whole scene.</li>
 *   <li><b>async against a snapshot.</b> For scenes big enough that even the
 *       budgeted exact test shows up in a profile, a coarse occupancy grid —
 *       published atomically and immutable while readers use it — lets worker
 *       threads do the arithmetic off the client thread. Workers only ever touch
 *       numbers and the published grid, never {@code ClientLevel}.</li>
 * </ul>
 *
 * <p>Hand-off from workers to the render thread is a fixed-size ring of packed
 * longs drained at the start of a frame. That is deliberate: one table is owned by
 * one thread (no locks, no torn reads), the ring has a hard capacity and drops its
 * own overflow, and every answer is {@code visible | hidden | unknown} with
 * {@code unknown} meaning "draw it".
 */
public final class AsyncVisibilityEngine implements AutoCloseable {

    public static final byte HIDDEN = 1;
    public static final byte VISIBLE = 0;
    public static final byte UNKNOWN = -1;

    private static final int RING_SLOTS = 1 << 13; // 8192, power of two, fixed
    private static final long RING_MASK = RING_SLOTS - 1;

    /** An object to test: identity plus its world-space box. No MC types cross threads. */
    /** {@code id} is an entity id or a packed block position; both fit in one long. */
    public record Request(long id, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    private final Settings settings;
    private final DeviceCapabilities device;
    private final DiagnosticsService diagnostics;
    private final TaskPools.Pool pool;

    private final LongByteCache results;
    private final long[] ringKey = new long[RING_SLOTS];
    private final byte[] ringValue = new byte[RING_SLOTS];
    private final AtomicLong produce = new AtomicLong();
    private long consume;

    private final AtomicLong tested = new AtomicLong();
    private final AtomicLong hiddenCount = new AtomicLong();
    private final AtomicLong visibleCount = new AtomicLong();
    private final AtomicLong overBudget = new AtomicLong();
    private final AtomicLong ringDrops = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();
    private final AtomicLong exactTests = new AtomicLong();
    private final AtomicLong probesThisFrameCounter = new AtomicLong();
    private final AtomicLong memoHits = new AtomicLong();
    private final AtomicLong memoMisses = new AtomicLong();

    /** Reusable worker tasks: a submission must not allocate. */
    private static final int JOB_SLOTS = 128;
    private static final byte JOB_FREE = 0;
    private static final byte JOB_QUEUED = 1;

    private final class Job implements Runnable {
        private final java.util.concurrent.atomic.AtomicInteger state =
                new java.util.concurrent.atomic.AtomicInteger(JOB_FREE);
        private long id;
        private double minX, minY, minZ, maxX, maxY, maxZ;
        private double cx, cy, cz;
        private int distance;
        private OccupancyField snapshot;
        private long snapshotVersion;

        boolean claim(long id, double a, double b, double c, double d, double e, double f,
                      double cx, double cy, double cz, int distance, OccupancyField snapshot) {
            if (!state.compareAndSet(JOB_FREE, JOB_QUEUED)) {
                return false;
            }
            this.id = id;
            this.minX = a;
            this.minY = b;
            this.minZ = c;
            this.maxX = d;
            this.maxY = e;
            this.maxZ = f;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.distance = distance;
            this.snapshot = snapshot;
            this.snapshotVersion = snapshot.version();
            return true;
        }

        private volatile long claimedAtNanos;

        void release() {
            claimedAtNanos = 0L;
            state.set(JOB_FREE);
        }

        @Override
        public void run() {
            // The task guards its own exceptions: that is the price of being handed to
            // submitRaw, which skips the pool's wrapper (and its allocation).
            try {
                boolean hidden = OcclusionRaycaster.boxHidden(snapshot, cx, cy, cz,
                        minX, minY, minZ, maxX, maxY, maxZ, distance);
                // Only publish if nobody recentered or cleared the grid meanwhile.
                if (snapshotVersion == snapshot.version()) {
                    publish(id, hidden);
                }
                tested.incrementAndGet();
            } catch (Throwable t) {
                // Never let a worker's surprise reach the pool's handler, and never
                // retry here: the object simply stays visible this frame.
                dev.blockboost.BlockBoost.LOG.debug("[BlockBoost] visibility job failed", t);
            } finally {
                snapshot = null;
                state.set(JOB_FREE);
                claimedAtNanos = 0L;
            }
        }
    }

    private final OccupancyField field = new OccupancyField();
    private final BlockShapeMemo memo = new BlockShapeMemo(1 << 14);

    // Per-frame cached settings. The mixin calls evaluate() once per entity per frame;
    // reading a ConcurrentHashMap and calling System.currentTimeMillis() that often is
    // how an optimization ends up costing more than the work it removes, so all of it is
    // read once in beginFrame() and held in plain fields for the rest of the frame.
    private int frameProbes;
    private int frameSubmissions;
    private long frameStartNanos;
    private long frameNanosBudget = 1_200_000L;
    private int maxDistance = 96;
    private boolean fieldMode;
    private long cacheValidForMs = 250L;
    private final java.util.concurrent.atomic.AtomicInteger jobCursor = new java.util.concurrent.atomic.AtomicInteger();
    private final Job[] jobs = new Job[JOB_SLOTS];
    private double camX, camY, camZ;
    private long lastCameraMoveMs;
    private long lastWorldChangeTick;
    private int testsThisFrame;
    private volatile String lastReason = "idle";
    private volatile boolean debug;

    private boolean jobsInstalled;

    private void installJobs() {
        if (jobsInstalled) {
            return;
        }
        for (int i = 0; i < jobs.length; i++) {
            jobs[i] = new Job();
        }
        jobsInstalled = true;
    }

    public AsyncVisibilityEngine(Settings settings, DeviceCapabilities device, TaskPools pools,
                                 DiagnosticsService diagnostics) {
        this.settings = settings;
        this.device = device;
        this.diagnostics = diagnostics;
        this.results = new LongByteCache(settings.integer(ConfigSchema.CULL_CACHE_ENTRIES));
        int workers = settings.integer(ConfigSchema.CULL_ENTITY_WORKERS);
        int threads = device == null ? 1
                : device.workerThreads(workers, 4, settings.bool(ConfigSchema.ANDROID_BIGLITTLE));
        boolean async = settings.bool(ConfigSchema.CULL_ENTITY_ASYNC);
        this.pool = pools.create("visibility", async ? threads : 1,
                settings.integer(ConfigSchema.CPU_QUEUE_LIMIT));
    }

    // ── per-frame ─────────────────────────────────────────────────────────────
    /** Drains worker results into the render-thread table. Client thread only. */
    public void beginFrame(long tick, double cameraX, double cameraY, double cameraZ) {
        installJobs();
        memo.beginFrame();
        int probes = switch (device == null ? dev.blockboost.device.DeviceClass.UNKNOWN : device.deviceClass()) {
            case LOW -> 1024;
            case MID -> 2048;
            case HIGH -> 4096;
            case UNKNOWN -> 1536;
        };
        // Debug mode is about *why* a decision was made, not about spending the frame:
        // it may look at more objects, but it must never be allowed to cost more time
        // than the budget, because a diagnostic that halves the framerate is not a
        // diagnostic anyone leaves on.
        if (settings.bool(ConfigSchema.CULL_ENTITY_DEBUG)) {
            probes = (int) Math.min(8192L, probes * 2L);
        }
        frameProbes = probes;
        frameSubmissions = fieldModeHint() ? 48 : 0;
        frameStartNanos = System.nanoTime();
        frameNanosBudget = Math.max(250_000L, settings.integer(ConfigSchema.CULL_ENTITY_BUDGET_MS) * 10_000L);
        maxDistance = settings.integer(ConfigSchema.CULL_ENTITY_MAX_DISTANCE);
        fieldMode = settings.bool(ConfigSchema.CULL_ENTITY_ASYNC);
        int refreshTicks = Math.max(1, settings.integer(ConfigSchema.CULL_ENTITY_REFRESH_TICKS));
        cacheValidForMs = refreshTicks * 50L;
        memo.resetCounters();
        reclaimStuckJobs();
        noteCamera(cameraX, cameraY, cameraZ);
        int drained = 0;
        while (consume != produce.get() && drained < 2048) {
            int slot = (int) (consume++ & RING_MASK);
            results.put(ringKey[slot], ringValue[slot] == 1 ? HIDDEN : VISIBLE);
            drained++;
        }
        testsThisFrame = 0;
        debug = settings.bool(ConfigSchema.CULL_ENTITY_DEBUG);
    }

    /**
     * A job can be left claimed if the pool dropped it (paused, saturated, shut down).
     * Nothing else would ever clear it, so once a frame we hand back anything that has
     * been sitting on a worker for more than two seconds. With 128 slots and no reclaim,
     * a stall would eventually starve the queue for good.
     */
    private void reclaimStuckJobs() {
        long deadline = System.nanoTime() - 2_000_000_000L;
        for (Job job : jobs) {
            if (job != null && job.state.get() != JOB_FREE && job.claimedAtNanos != 0L
                    && job.claimedAtNanos < deadline) {
                job.release();
                ringDrops.incrementAndGet();
            }
        }
    }

    private boolean fieldModeHint() {
        return settings.bool(ConfigSchema.CULL_ENTITY_ASYNC);
    }

    /** Keeps the cache honest while the camera is running away from the snapshot. */
    private void noteCamera(double x, double y, double z) {
        double moved = Math.abs(x - camX) + Math.abs(y - camY) + Math.abs(z - camZ);
        camX = x;
        camY = y;
        camZ = z;
        if (moved > 0.75) {
            lastCameraMoveMs = System.currentTimeMillis();
        }
        if (useFieldMode() && field.centerDrift(x, y, z) > OccupancyField.SPAN * 0.3) {
            field.recenter((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            invalidations.incrementAndGet();
        }
    }

    /**
     * Refreshes the coarse occupancy snapshot with a bounded number of voxel probes.
     * Client thread only: this is the only place the async path reads live blocks.
     */
    public void updateField(LevelProbe probe) {
        if (!frameMode && !fieldModeHint()) {
            return;
        }
        if (probe == null) {
            // No level bound yet (first frames, or between dimension changes). Keeping
            // the previous snapshot is correct; reading through a null probe is not.
            return;
        }
        if (!useFieldMode()) {
            return;
        }
        // A coarse grid over a whole render distance must not be rebuilt every frame:
        // one slice every few frames, and a smaller first pass so the initial scan is
        // spread over several frames instead of one long one.
        int budget = field.isComplete() ? 32 : 96;
        field.refreshNextSlice(budget, probe);
    }

    public boolean useFieldMode() {
        return settings.bool(ConfigSchema.CULL_ENTITY_ASYNC);
    }

    /** Cached answer for this object, or {@link #UNKNOWN} if we have not decided yet. */
    public byte cached(long id) {
        return (byte) results.get(id, UNKNOWN);
    }

    /**
     * Runs (or queues) a test for one object and returns the decision to use for
     * this frame. Never returns {@link #HIDDEN} without a completed test.
     */
    public byte evaluate(Request req, LevelProbe probe, boolean occlusionEnabled) {
        return evaluate(req.id(), req.minX(), req.minY(), req.minZ(), req.maxX(), req.maxY(), req.maxZ(),
                probe, occlusionEnabled);
    }

    /**
     * Primitive form, used from the render-thread mixins: boxing a record per entity per
     * frame is pure garbage for the collector to clean up, and the collector is the
     * reason a "smoother" mod can feel worse on a phone.
     */
    public byte evaluate(long id, double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ,
                         LevelProbe probe, boolean occlusionEnabled) {
        if (!occlusionEnabled) {
            // Frustum-only mode: an occlusion result from a previous frame may still
            // be applied only when the cache is fresh for this camera, which we cannot
            // guarantee here, so nothing is culled.
            return VISIBLE;
        }
        byte known = cached(id);
        if (fieldMode) {
            // Async mode: the worker answer is authoritative until it is replaced, and
            // it stays in the table across frames, so a farm stops costing per-frame work
            // after the first pass. Re-asking is rate limited per frame, not per object -
            // queueing a task for every entity every frame is exactly the kind of
            // background churn that shows up as a stall on a phone.
            if (known != UNKNOWN) {
                if (known == HIDDEN) {
                    hiddenCount.incrementAndGet();
                } else {
                    visibleCount.incrementAndGet();
                }
                if (freshEnough()) {
                    return known;
                }
            }
            if (frameSubmissions > 0 && submitAsync(id, minX, minY, minZ, maxX, maxY, maxZ)) {
                frameSubmissions--;
            }
            return known == UNKNOWN ? VISIBLE : known;
        }

        // On-thread mode: bounded by object count, by probe count and by wall clock,
        // whichever runs out first. Everything above that stays visible this frame.
        if (testsThisFrame >= exactTestBudget() || frameProbes <= 0
                || System.nanoTime() - frameStartNanos > frameNanosBudget) {
            overBudget.incrementAndGet();
            return VISIBLE;
        }
        testsThisFrame++;
        int probesStart = frameProbes;
        int[] budget = {frameProbes};
        boolean hidden = OcclusionRaycaster.boxHiddenQuick(probe, memo, camX, camY, camZ,
                minX, minY, minZ, maxX, maxY, maxZ, maxDistance, budget);
        frameProbes = Math.max(0, budget[0]);
        probesThisFrameCounter.addAndGet(Math.max(0, probesStart - budget[0]));
        memoHits.set(memo.hits());
        memoMisses.set(memo.misses());
        exactTests.incrementAndGet();
        tested.incrementAndGet();
        results.put(id, (byte) (hidden ? HIDDEN : VISIBLE));
        if (hidden) {
            hiddenCount.incrementAndGet();
        } else {
            visibleCount.incrementAndGet();
        }
        return hidden ? HIDDEN : VISIBLE;
    }

    /** Re-test sooner while the camera is still, later while it moves. */
    private boolean freshEnough() {
        int ticks = Math.max(1, settings.integer(ConfigSchema.CULL_ENTITY_REFRESH_TICKS));
        if (cameraRecentlyMoved()) {
            ticks = Math.max(1, ticks >> 2);
        }
        return System.currentTimeMillis() - lastCameraMoveMs > ticks * 50L;
    }

    public boolean cameraRecentlyMoved() {
        return System.currentTimeMillis() - lastCameraMoveMs < 250;
    }

    /** How many exact tests one frame may spend: scaled to the device, never unbounded. */
    public int exactTestBudget() {
        int base = device == null ? 24 : switch (device.deviceClass()) {
            case LOW -> 12;
            case MID -> 24;
            case HIGH -> 64;
            case UNKNOWN -> 16;
        };
        if (settings.bool(ConfigSchema.CULL_ENTITY_DEBUG)) {
            base *= 2; // the user asked for per-object reasons, so pay for accuracy
        }
        return base;
    }

    /**
     * Queues a test on a reusable job. Returns false when everything is busy, which is
     * a normal outcome and simply means "this object gets re-asked next frame": the
     * point of the cap is that a busy worker must not turn into an unbounded queue.
     */
    private boolean submitAsync(long id, double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
        if (produce.get() - consume >= RING_SLOTS / 2) {
            ringDrops.incrementAndGet();
            return false;
        }
        OccupancyField snapshot = field;
        if (!snapshot.isComplete()) {
            return false; // nothing to test against yet
        }
        for (int n = 0; n < jobs.length; n++) {
            int index = Math.floorMod(jobCursor.getAndIncrement(), jobs.length);
            Job job = jobs[index];
            if (job == null || !job.claim(id, minX, minY, minZ, maxX, maxY, maxZ, camX, camY, camZ,
                    maxDistance, snapshot)) {
                continue;
            }
            job.claimedAtNanos = System.nanoTime();
            if (!pool.submitRaw(job)) {
                job.release();
                ringDrops.incrementAndGet();
                return false;
            }
            return true;
        }
        return false;
    }

    /** Probes used this frame, for the diagnostics line. */
    public int probesLeftThisFrame() {
        return frameProbes;
    }

    public String memoSummary() {
        long h = memo.hits();
        long m = memo.misses();
        return h + "/" + (h + m);
    }

    /** Bounded, lock free hand-off from a worker to the client thread. */
    private void publish(long id, boolean hidden) {
        long slot = produce.getAndIncrement();
        if (slot - consume >= RING_SLOTS) {
            // Full: the client thread is behind, so this decision is simply not
            // made this frame (the caller renders the object). Dropped work must
            // never become a wrong answer.
            ringDrops.incrementAndGet();
            produce.decrementAndGet();
            return;
        }
        int index = (int) (slot & RING_MASK);
        ringKey[index] = id;
        ringValue[index] = (byte) (hidden ? 1 : 0);
    }

    public void forceVisible(long id) {
        results.put(id, VISIBLE);
    }

    /** Teleport, dimension change, respawn: every cached answer is now suspect. */
    public void invalidateAll(String why) {
        results.invalidate();
        field.clear();
        invalidations.incrementAndGet();
        lastReason = why;
    }

    public void onWorldChange(long tick) {
        lastWorldChangeTick = tick;
        invalidateAll("world change");
    }

    public long lastWorldChangeTick() {
        return lastWorldChangeTick;
    }

    /** Runtime re-sizing of the worker pool (bounded by DeviceCapabilities at the caller). */
    public void setWorkers(int threads) {
        pool.setThreads(threads);
    }

    public int workers() {
        return pool.activeThreads();
    }

    public long poolDropped() {
        return pool.dropped();
    }

    public OccupancyField field() {
        return field;
    }

    public String lastReason() {
        return lastReason;
    }

    public boolean debugEnabled() {
        return debug;
    }

    public long testedCount() {
        return tested.get();
    }

    public long hiddenCountValue() {
        return hiddenCount.get();
    }

    public long visibleCountValue() {
        return visibleCount.get();
    }

    public long overBudgetValue() {
        return overBudget.get();
    }

    public long ringDropsValue() {
        return ringDrops.get();
    }

    public long invalidationCount() {
        return invalidations.get();
    }

    public String describe() {
        return "cache " + results.describe() + " exactTests=" + exactTests.get() + " async=" + useFieldMode()
                + " probes/frame=" + probesThisFrameCounter.get() + " memoHits=" + memoHits.get()
                + " hidden=" + hiddenCount.get() + " visible=" + visibleCount.get()
                + " overBudget=" + overBudget.get() + " drops=" + ringDrops.get()
                + " invalidations=" + invalidations.get() + " pool=" + pool.describe()
                + " field=" + field.describe();
    }

    public void publishCounters() {
        if (diagnostics != null && settings.bool(ConfigSchema.DIAG_ENABLED)) {
            diagnostics.counter("visibility.tested", tested.get());
            diagnostics.counter("visibility.hidden", hiddenCount.get());
            diagnostics.counter("visibility.visible", visibleCount.get());
            diagnostics.counter("visibility.overBudget", overBudget.get());
            diagnostics.counter("visibility.ringDrops", ringDrops.get());
            diagnostics.counter("visibility.invalidations", invalidations.get());
            diagnostics.counter("visibility.solidVoxels", field.publishedSolidVoxels());
            diagnostics.counter("visibility.probesLastFrame", probesThisFrameCounter.get());
            diagnostics.counter("visibility.memoHits", memoHits.get());
        }
    }

    @Override
    public void close() {
        // The owning TaskPools shuts the threads down; nothing here holds a resource.
    }
}
