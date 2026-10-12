package dev.blockboost.client.features;

import net.minecraft.client.Minecraft;

import dev.blockboost.BlockBoost;
import dev.blockboost.client.ClientServices;
import dev.blockboost.client.HotState;
import dev.blockboost.client.services.ChunkPipelineStats;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * Keeps chunk work from outrunning the frame.
 *
 * <p>What this does in 1.0.0, stated plainly: it bounds <em>BlockBoost's own</em>
 * background work (the visibility pool, queue depth, drop-oldest policy) and it
 * applies a backlog guard — when frames are consistently over budget the client
 * asks the world for less chunk work by shrinking render/simulation distance, and
 * grows it back once frames recover. Both levers are vanilla options, which is what
 * makes them safe with a custom GL translator underneath.
 *
 * <p>What it deliberately does not do: it does not reschedule or instrument
 * vanilla's chunk builder. Those methods have no version-verified hook for 26.1.x
 * in this build, and an unverified mixin into meshing is exactly how an "optimizing"
 * mod turns into invisible terrain. The {@code chunks.*} budget keys below are shown
 * in the UI as reserved for that work, not as switches that are wired up.
 */
public final class ChunkPipelineFeature implements Feature {

    private static final long COOLDOWN_MS = 5_000L;

    private long lastChangeMs;
    private int appliedRenderDistance = -1;
    private int appliedSimulationDistance = -1;
    private int shrinkStreak;
    private int growStreak;

    @Override
    public FeatureId id() {
        return FeatureId.CHUNK_PIPELINE;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.bool(ConfigSchema.CHUNK_BACKLOG_GUARD);
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        if (!settings.bool(ConfigSchema.RENDER_VIDEO_OVERRIDES)) {
            return "the backlog guard works by changing render and simulation distance, which needs "
                    + "'allow video overrides' switched on";
        }
        if (settings.integer(ConfigSchema.CHUNK_WORKER_LIMIT) <= 0) {
            return "worker limit must be positive";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        HotState.refresh();
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        Minecraft client = Minecraft.getInstance();
        if (appliedRenderDistance >= 0) {
            VanillaOptions.restore(client, "renderDistance");
            appliedRenderDistance = -1;
        }
        if (appliedSimulationDistance >= 0) {
            VanillaOptions.restore(client, "simulationDistance");
            appliedSimulationDistance = -1;
        }
        VanillaOptions.persist(client);
        shrinkStreak = 0;
        growStreak = 0;
    }

    /**
     * One decision per second, hysteresis on both sides: a single bad frame must not
     * shrink the world, and one good frame must not grow it back.
     */
    public void tick(ClientServices services, Settings settings) {
        if (!HotState.chunkPipeline || services == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChangeMs < COOLDOWN_MS) {
            return;
        }
        lastChangeMs = now;
        ChunkPipelineStats stats = services.chunkStats();
        double mean = services.frames().meanMs();
        double target = 1000.0 / Math.max(10, settings.integer(ConfigSchema.ADAPTIVE_TARGET_FPS));
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        int render = client.options.renderDistance().get();
        int sim = client.options.simulationDistance().get();
        if (appliedRenderDistance < 0) {
            appliedRenderDistance = render;
            appliedSimulationDistance = sim;
        }
        int floor = Math.max(4, settings.integer(ConfigSchema.VANILLA_RENDER_DISTANCE));
        if (mean > target * 1.25) {
            growStreak = 0;
            shrinkStreak++;
            if (shrinkStreak >= 2 && render > floor) {
                int next = Math.max(floor, render - 2);
                BlockBoost.logInfo("[BlockBoost] chunk backlog guard: " + render + " -> " + next
                        + " render distance (frames " + String.format(java.util.Locale.ROOT, "%.1f", mean) + " ms)");
                VanillaOptions.renderDistance(client, next);
                VanillaOptions.simulationDistance(client, Math.max(2, next - 2));
                appliedRenderDistance = next;
                appliedSimulationDistance = Math.max(2, next - 2);
                shrinkStreak = 0;
            }
        } else if (mean < target * 0.85) {
            shrinkStreak = 0;
            growStreak++;
            if (growStreak >= 3 && render < appliedRenderDistance + 0 && render < 32) {
                int next = Math.min(appliedRenderDistance, render + 2);
                if (next > render) {
                    VanillaOptions.renderDistance(client, next);
                    VanillaOptions.simulationDistance(client, Math.max(2, next - 2));
                    growStreak = 0;
                }
            }
        } else {
            shrinkStreak = 0;
            growStreak = 0;
        }
        stats.setGauges(0, 0, 0, settings.integer(ConfigSchema.CHUNK_WORKER_LIMIT));
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        return "guard=" + (appliedRenderDistance >= 0 ? "engaged at " + appliedRenderDistance + " chunks" : "idle")
                + " workerLimit=" + ctx.settings().integer(ConfigSchema.CHUNK_WORKER_LIMIT);
    }
}
