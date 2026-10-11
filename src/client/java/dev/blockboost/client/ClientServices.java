package dev.blockboost.client;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.state.BlockState;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.client.adaptive.AdaptiveController;
import dev.blockboost.client.culling.AsyncVisibilityEngine;
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
        long t = ticks.incrementAndGet();
        if (settings.bool(ConfigSchema.CULL_ENTITY)) {
            Minecraft c = Minecraft.getInstance();
            double cx = 0, cy = 0, cz = 0;
            try {
                var cam = c.getMainCamera();
                cx = cam.getPosition().x;
                cy = cam.getPosition().y;
                cz = cam.getPosition().z;
            } catch (RuntimeException e) {
                registryFail(e);
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

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(java.util.Locale.ROOT, "%.3f", v) : "0.000";
    }

    public void onFrameEnd() {
        long now = System.nanoTime();
        frames.sampleNow(now);
        if ((t & 15L) == 0) {
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
            BlockBoostRuntime.get().registry().refresh();
        }
        AdaptiveController a = this.adaptive;
        if (a != null) {
            a.onFrameEnd(ticks.get());
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
            visibility.clear();
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

    @Override
    /**
     * Reader the culling code uses, re-bound whenever the client level changes.
     * Deliberately a lambda over one method: the ray caster stays unit testable and
     * nothing but this class touches {@code ClientLevel}.
     */
    public LevelProbe levelProbe() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client == null ? null : client.level;
        if (probeLevel != level) {
            probeLevel = level;
            probe = null;
        }
        if (probe == null && level != null) {
            final ClientLevel target = level;
            probe = (x, y, z) -> {
                try {
                    BlockPos pos = BlockPos.containing(x, y, z);
                    BlockState state = target.getBlockState(pos);
                    return state.isSolidRender(target, pos);
                } catch (RuntimeException e) {
                    // An unreadable position is never solid: culling must fail open.
                    return false;
                }
            };
        }
        return probe;
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
