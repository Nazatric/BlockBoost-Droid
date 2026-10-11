package dev.blockboost.client.culling;

import java.util.concurrent.atomic.AtomicLong;

import dev.blockboost.client.services.DiagnosticsService;
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

    private final OccupancyField field = new OccupancyField();
    private double camX, camY, camZ;
    private long lastCameraMoveMs;
    private long lastWorldChangeTick;
    private int testsThisFrame;
    private volatile String lastReason = "idle";
    private volatile boolean debug;

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
        if (!useFieldMode()) {
            return;
        }
        int budget = field.isComplete() ? 128 : 512;
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
        int maxDistance = settings.integer(ConfigSchema.CULL_ENTITY_MAX_DISTANCE);
        if (!occlusionEnabled) {
            // Frustum-only mode: an occlusion result from a previous frame may still
            // be applied only when the cache is fresh for this camera, which we cannot
            // guarantee here, so nothing is culled.
            return VISIBLE;
        }
        byte known = cached(req.id());
        if (known != UNKNOWN && freshEnough()) {
            if (known == HIDDEN) {
                hiddenCount.incrementAndGet();
            } else {
                visibleCount.incrementAndGet();
            }
            return known;
        }
        if (useFieldMode()) {
            submitAsync(req, maxDistance);
            // Never guess from a value we know to be stale: render it this frame and
            // use the worker's answer on a later one. One extra frame of work beats
            // one wrong frame of missing terrain content.
            return VISIBLE;
        }
        int budget = exactTestBudget();
        if (testsThisFrame >= budget) {
            overBudget.incrementAndGet();
            return VISIBLE;
        }
        testsThisFrame++;
        boolean hidden = OcclusionRaycaster.boxHidden(probe, camX, camY, camZ,
                req.minX(), req.minY(), req.minZ(), req.maxX(), req.maxY(), req.maxZ(), maxDistance);
        exactTests.incrementAndGet();
        tested.incrementAndGet();
        results.put(req.id(), (byte) (hidden ? HIDDEN : VISIBLE));
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
        int base = device == null ? 32 : switch (device.deviceClass()) {
            case LOW -> 16;
            case MID -> 48;
            case HIGH -> 128;
            case UNKNOWN -> 24;
        };
        if (settings.bool(ConfigSchema.CULL_ENTITY_DEBUG)) {
            base *= 2; // the user asked for per-object reasons, so pay for accuracy
        }
        return base;
    }

    private void submitAsync(Request req, int maxDistance) {
        long seq = produce.get();
        if (seq - consume >= RING_SLOTS) {
            ringDrops.incrementAndGet();
            return;
        }
        final OccupancyField snapshot = field;
        final double cx = camX, cy = camY, cz = camZ;
        final long version = field.version();
        final long id = req.id();
        final double minX = req.minX(), minY = req.minY(), minZ = req.minZ();
        final double maxX = req.maxX(), maxY = req.maxY(), maxZ = req.maxZ();
        pool.submit(() -> {
            boolean hidden = OcclusionRaycaster.boxHidden(snapshot, cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ,
                    maxDistance);
            if (version == snapshot.version()) {
                publish(id, hidden);
            }
            tested.incrementAndGet();
        });
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
        }
    }

    @Override
    public void close() {
        // The owning TaskPools shuts the threads down; nothing here holds a resource.
    }
}
