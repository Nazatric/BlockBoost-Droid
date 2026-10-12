package dev.blockboost.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.client.adaptive.AdaptiveController;
import dev.blockboost.client.culling.AsyncVisibilityEngine;
import dev.blockboost.client.culling.LevelProbes;
import dev.blockboost.culling.LevelProbe;
import dev.blockboost.client.services.ChunkPipelineStats;
import dev.blockboost.client.services.DiagnosticsService;
import dev.blockboost.client.services.FrameSampler;
import dev.blockboost.client.services.GcMonitor;
import dev.blockboost.client.services.ReportWriter;
import dev.blockboost.compat.ModCompatibility;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.FeatureEnvironment;
import dev.blockboost.device.DeviceCapabilities;
import dev.blockboost.util.ScratchBuffers;
import dev.blockboost.util.TaskPools;

/**
 * Owns every shared client service: thread pools, scratch buffers, frame sampler,
 * GC watcher, chunk counters, visibility engine and the report writer.
 *
 * <p>It is also the {@link FeatureEnvironment} handed to features, which is what
 * keeps {@code dev.blockboost.config|util|core|device|compat} free of Minecraft
 * types (and therefore unit-testable without a game).
 *
 * <p>Lifecycle rules enforced here:
 * <ul>
 *   <li>level enter/leave invalidates all cached visibility state and releases
 *       scratch buffers, so repeated world entry cannot creep upwards;</li>
 *   <li>when the game is unfocused, minimized or in a menu, workers are paused
 *       and pending jobs are dropped instead of accumulating;</li>
 *   <li>everything is closed on client stop, so a crash report never lists a
 *       BlockBoost thread.</li>
 * </ul>
 */
public final class ClientServices implements FeatureEnvironment, AutoCloseable {

    private final Settings settings;
    private final DeviceCapabilities device;
    private final ModCompatibility mods;
    private final TaskPools pools;
    private final ScratchBuffers scratch;
    private final FrameSampler frames;
    private final GcMonitor gc;
    private final ChunkPipelineStats chunkStats;
    private final DiagnosticsService diagnostics;
    private final ReportWriter reports;
    private final AsyncVisibilityEngine visibility;

    private final AtomicBoolean levelLoaded = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong idleFrames = new AtomicLong();
    private final AtomicBoolean gpuProbeDone = new AtomicBoolean();
    private volatile AdaptiveController adaptive;
    private volatile int adaptiveLevel;
    private volatile LevelProbe probe;
    private volatile ClientLevel probeLevel;
    private volatile String probeDescription = "no level bound yet";
    private long frameStartNanos;
    private volatile long lastHookErrorMs;

    public ClientServices(Settings settings, DeviceCapabilities device, ModCompatibility mods) {
        this.settings = settings;
        this.device = device;
        this.mods = mods;
        this.scratch = new ScratchBuffers(settings.integer(ConfigSchema.MEM_SCRATCH_CAP_KB));
        this.pools = new TaskPools();
        this.frames = new FrameSampler(settings.integer(ConfigSchema.DIAG_WINDOW));
        this.gc = new GcMonitor();
        this.gc.setReportThresholdMs(settings.integer(ConfigSchema.MEM_GC_LOG_MS));
        this.chunkStats = new ChunkPipelineStats();
        this.diagnostics = new DiagnosticsService(settings, device, mods, this);
        this.reports = new ReportWriter();
        this.visibility = new AsyncVisibilityEngine(settings, device, pools, diagnostics);
        if (device != null) {
            device.resolveFromSettings(settings);
        }
    }

    // ── per-frame hooks (called from the client mixin; must not allocate) ─────
    public void onFrameStart() {
        frameStartNanos = System.nanoTime();
        long t = ticks.incrementAndGet();
        if (settings.bool(ConfigSchema.CULL_ENTITY)) {
            // The camera position is captured by the level renderer mixin, from the
            // exact Camera object the frame is being built with. Falling back to the
            // player only matters on the first frame after a level loads.
            // Ray origin: the client player. The camera object is not reachable from
            // here through anything we could verify against 26.1.2, and guessing a
            // getter for it would either crash or be silently wrong. Using the player
            // is conservative in the right direction, and the culling code refuses to
            // hide anything while you are riding something (HotState.alwaysRender).
            Minecraft c = Minecraft.getInstance();
            double cx = HotState.camX, cy = HotState.camY, cz = HotState.camZ;
            if (c.player != null) {
                var eye = c.player.position();
                cx = eye.x;
                cy = eye.y + c.player.getEyeHeight();
                cz = eye.z;
                HotState.camX = cx;
                HotState.camY = cy;
                HotState.camZ = cz;
            }
            visibility.beginFrame(t, cx, cy, cz);
            visibility.updateField(levelProbe());
        }
        Minecraft client = Minecraft.getInstance();
        boolean active = client.level != null;
        if (levelLoaded.getAndSet(active) && !active) {
            onLevelStateChange(false);
        } else if (!levelLoaded.get() && active) {
            onLevelStateChange(true);
        }
        if (settings.bool(ConfigSchema.CPU_PAUSE_IDLE)) {
            boolean wantPaused = !active || isWindowHidden(client);
            if (paused.getAndSet(wantPaused) != wantPaused) {
                pools.pauseForIdle(wantPaused);
            }
        } else if (paused.getAndSet(false)) {
            pools.pauseForIdle(false);
        }
        if (!active) {
            idleFrames.incrementAndGet();
        }
    }

    /** Called from the client tick: keeps slow work off the render thread. */
    public void onClientTickStart(Minecraft client) {
        // Nothing runs per tick on the render thread today; kept as the single
        // entry point so a future tick hook does not need another mixin target.
    }

    public void onClientTick(Minecraft client) {
        long t = ticks.get();
        if ((t & 63L) == 0L) {
            AdaptiveController a = adaptive;
            if (a != null) {
                a.sampleTick();
            }
        }
        if (settings.bool(ConfigSchema.MEM_HEAP_REPORT) && client.level != null
                && (t % (20L * 60L * 5L)) == 0L) {
            BlockBoost.logInfo("[BlockBoost] heap " + device.heapSummary() + " " + gc.hudLine());
        }
    }

    /** CSV + text export for the benchmark harness ({@code -Dblockboost.bench}). */
    public String writeBenchReport() {
        java.util.List<String> lines = diagnostics.fullReport();
        String text = reports.writeText("bench-report.txt", String.join("\n", lines) + "\n");
        java.util.List<String> header = java.util.List.of("frame_ms_mean", "p50", "p95", "p99", "worst", "fps",
                "fps_1pct_low", "gc_pauses", "gc_pause_ms", "sections_built", "sections_uploaded",
                "entities_culled", "block_entities_culled", "particles_suppressed", "frames");
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        rows.add(java.util.List.of(
                fmt(frames.meanMs()), fmt(frames.percentileMs(50)), fmt(frames.percentileMs(95)),
                fmt(frames.percentileMs(99)), fmt(frames.worstMs()), fmt(frames.meanFps()),
                fmt(frames.low1PercentFps()), Long.toString(gc.pauseCount()), Long.toString(gc.totalPauseMs()),
                Long.toString(chunkStats.sectionsBuilt()), Long.toString(chunkStats.sectionsUploaded()),
                Long.toString(dev.blockboost.client.HotState.culledEntities),
                Long.toString(dev.blockboost.client.HotState.culledBlockEntities),
                Long.toString(dev.blockboost.client.HotState.suppressedParticles),
                Long.toString(frames.framesMeasured())));
        reports.writeCsv("bench-frames", header, rows);
        return text;
    }

    /**
     * Optional frame limiter, off unless {@code render.soft_pacing} is on.
     *
     * <p>It waits out a frame only when the frame finished with more than 1.5 ms to
     * spare, and it leaves half a millisecond of margin, so it can never turn a frame
     * that was going to be on time into one that is late. The point is not to hit a
     * number on the FPS overlay: it is to stop the GPU and the memory subsystem doing
     * work nobody asked for while the phone is thermally limited, which is the only way
     * a cap actually helps on hardware where vsync is unreliable.
     */
    private volatile boolean autoProfileConsidered;
    private volatile List<String> lastAutoLines = List.of();

    /** What the automatic profile says about this device right now, without writing it. */
    public List<String> previewRecommendedProfile() {
        dev.blockboost.device.DeviceCapabilities d = device;
        if (d == null) {
            return List.of("device capabilities unavailable");
        }
        dev.blockboost.device.GpuInfo gpu = d.gpu();
        return dev.blockboost.device.DeviceProfileAdvisor.recommend(d.cores(), d.maxHeapBytes(),
                d.androidEnvironment(), d.mobileArch(),
                gpu == null ? null : gpu.vendor(), gpu == null ? null : gpu.renderer(),
                d.rendererProfile() != null && d.rendererProfile().translating()).describe();
    }

    public List<String> lastAutoProfileLines() {
        return lastAutoLines.isEmpty() ? previewRecommendedProfile() : lastAutoLines;
    }

    /**
     * Chooses a starting configuration from what the runtime actually reported, on a fresh
     * install only - and never again unless the user asks for it from the settings screen.
     *
     * <p>Why on the first frame: the renderer identity (translated GL or not) is only
     * knowable once a context exists, and a recommendation built without it would be a
     * guess about the one thing Android varies most on.
     *
     * @param force re-apply even if the config was not created by this launch
     * @return the lines describing what was chosen, empty when nothing was applied
     */
    public List<String> applyRecommendedProfile(boolean force) {
        if (!force) {
            if (autoProfileConsidered) {
                return List.of();
            }
            autoProfileConsidered = true;
            if (!settings.isFreshInstall()) {
                return List.of();
            }
        }
        if (!"auto".equals(settings.string(ConfigSchema.ANDROID_DEVICE_CLASS))) {
            // The user picked a class by hand; that outranks any heuristic of ours.
            diagnostics.note("auto profile skipped: android.device_class is set manually");
            return List.of();
        }
        dev.blockboost.device.DeviceCapabilities d = device;
        dev.blockboost.device.GpuInfo gpu = d.gpu();
        dev.blockboost.device.DeviceProfileAdvisor.Recommendation rec =
                dev.blockboost.device.DeviceProfileAdvisor.recommend(d.cores(), d.maxHeapBytes(),
                        d.androidEnvironment(), d.mobileArch(),
                        gpu == null ? null : gpu.vendor(), gpu == null ? null : gpu.renderer(),
                        d.rendererProfile() != null && d.rendererProfile().translating());
        lastAutoLines = rec.describe();
        int changed = settings.applyAll(rec.patch());
        BlockBoostRuntime.get().requestSave();
        for (String line : rec.describe()) {
            BlockBoost.logInfo("[BlockBoost] " + line);
            diagnostics.note(line);
        }
        BlockBoost.logInfo("[BlockBoost] auto profile wrote " + changed + " setting(s)");
        HotState.refresh();
        return rec.describe();
    }

    private void pace(long frameEndNanos) {
        if (!HotState.softPacing) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        int cap = HotState.frameCap;
        if (cap <= 0 && client != null && isWindowHidden(client) && settings.bool(ConfigSchema.RENDER_IDLE_THROTTLE)) {
            cap = HotState.idleFrameCap;
        }
        if (cap <= 0) {
            return;
        }
        long target = 1_000_000_000L / cap;
        long slack = target - (frameEndNanos - frameStartNanos);
        if (slack < 1_500_000L) {
            return;
        }
        try {
            java.util.concurrent.locks.LockSupport.parkNanos(slack - 500_000L);
        } catch (Throwable ignored) {
            // A parked thread that wakes early or late is a cosmetic problem, not a
            // correctness one, and the game must never see an exception from a limiter.
        }
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(java.util.Locale.ROOT, "%.3f", v) : "0.000";
    }

    public void onFrameEnd() {
        long now = System.nanoTime();
        pace(now);
        frames.sampleNow(now);
        if ((ticks.get() & 15L) == 0) {
            visibility.publishCounters();
        }
        if (gpuProbeDone.compareAndSet(false, true)) {
            // First frame: the GL context is current on this thread, so this is the
            // only moment it is safe to ask the driver what it actually is.
            try {
                device.tryReadGpuOnce();
                device.resolveFromSettings(settings);
            } catch (Throwable t) {
                BlockBoost.LOG.warn("[BlockBoost] renderer probe failed; staying conservative", t);
            }
            // The GPU strings only exist after the first frame, and the auto profile wants
            // them (they decide whether this is a translated GL stack), so the first useful
            // moment to choose defaults is exactly here - once, outside any loop.
            applyRecommendedProfile(false);
            BlockBoostRuntime.get().registry().refresh();
        }
    }

    public boolean isWindowHidden(Minecraft client) {
        try {
            var window = client.getWindow();
            return window != null && (window.isMinimized() || !window.isFocused());
        } catch (Throwable e) {
            // Focused-state callbacks are unreliable under some translators: treat as
            // visible so we never pause work based on a wrong signal.
            return false;
        }
    }

    private void onLevelStateChange(boolean loaded) {
        if (loaded) {
            visibility.onWorldChange(ticks.get());
            chunkStats.reset();
        } else {
            visibility.invalidateAll("left the level");
            chunkStats.reset();
            if (settings.bool(ConfigSchema.MEM_RELEASE_ON_EXIT)) {
                scratch.release();
            }
        }
    }

    // ── accessors ─────────────────────────────────────────────────────────────
    public Settings settings() {
        return settings;
    }

    public DeviceCapabilities device() {
        return device;
    }

    public ModCompatibility mods() {
        return mods;
    }

    public TaskPools pools() {
        return pools;
    }

    public ScratchBuffers scratch() {
        return scratch;
    }

    public FrameSampler frames() {
        return frames;
    }

    public GcMonitor gc() {
        return gc;
    }

    public ChunkPipelineStats chunkStats() {
        return chunkStats;
    }

    public DiagnosticsService diagnostics() {
        return diagnostics;
    }

    public ReportWriter reports() {
        return reports;
    }

    public AsyncVisibilityEngine visibility() {
        return visibility;
    }

    /** One line for the HUD and the report: what our own pools are doing right now. */
    public String poolSummary() {
        return "pools=" + pools.pools().size() + " queued=" + pools.queueTotal()
                + " dropped=" + pools.droppedTotal() + (pools.isPaused() ? " paused" : "");
    }

    public long tick() {
        return ticks.get();
    }

    public long idleFrames() {
        return idleFrames.get();
    }

    public boolean isIdle() {
        return paused.get();
    }

    /** Lets the HUD and the report show what the controller did, without a cycle. */
    public void markAdaptiveApplied(int level) {
        adaptiveLevel = level;
        diagnostics.note("adaptive level " + level);
    }

    public int adaptiveLevel() {
        return adaptiveLevel;
    }

    public void setAdaptive(AdaptiveController adaptive) {
        this.adaptive = adaptive;
    }

    public AdaptiveController adaptive() {
        return adaptive;
    }

    /**
     * Reader the culling code uses, re-bound whenever the client level changes. The
     * block-opacity predicate itself is resolved by {@link LevelProbes} — by name, from
     * a candidate list, once per level — because that method's name is exactly the kind
     * of thing that changes between Minecraft versions and because "no occlusion" is a
     * recoverable state while "wrong occlusion" is not.
     */
    public LevelProbe levelProbe() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client == null ? null : client.level;
        if (probeLevel != level) {
            probeLevel = level;
            probe = null;
        }
        if (probe == null && level != null) {
            LevelProbes.Probe probed = LevelProbes.forLevel(level);
            probe = probed.probe();
            probeDescription = probed.description();
            if (!probed.available()) {
                diagnostics.note("occlusion: " + probed.description());
                HotState.entityOcclusion = false; // frustum only until a query resolves
            }
        }
        return probe;
    }

    /** What the occlusion path resolved (also shown in the diagnostics report). */
    public String probeDescription() {
        return probeDescription;
    }

    /** Hook failures are reported at most once a minute; the game must keep running. */
    public void noteHookFailure(String where, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastHookErrorMs > 60_000L) {
            lastHookErrorMs = now;
            BlockBoost.LOG.warn("[BlockBoost] {} hook failed (continuing without it)", where, t);
        }
    }

    private void registryFail(RuntimeException e) {
        // Never let a hook take the frame down: report once per minute, keep going.
        long now = System.currentTimeMillis();
        if (now - lastHookErrorMs > 60_000L) {
            lastHookErrorMs = now;
            BlockBoost.LOG.warn("[BlockBoost] per-frame hook failed (continuing)", e);
        }
    }

    public boolean hasActiveLevel() {
        Minecraft c = Minecraft.getInstance();
        return c != null && c.level != null;
    }

    @Override
    public boolean headless() {
        return Boolean.getBoolean("fabric.client.gametest");
    }

    @Override
    public void close() {
        try {
            visibility.close();
        } catch (RuntimeException e) {
            BlockBoost.LOG.warn("[BlockBoost] visibility engine shutdown problem", e);
        }
        pools.close();
        gc.close();
    }
}
