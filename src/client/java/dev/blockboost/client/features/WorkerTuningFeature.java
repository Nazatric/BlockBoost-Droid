package dev.blockboost.client.features;

import dev.blockboost.client.ClientServices;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;
import dev.blockboost.device.DeviceCapabilities;
import dev.blockboost.util.TaskPools;

/**
 * Thread and queue sizing for the work BlockBoost itself puts in the background.
 *
 * <p>The rules that make this safe on a phone rather than just fast on a desktop:
 * every pool is created here with a hard ceiling (no feature may spin its own
 * threads), the queue is bounded and full queues drop the oldest job instead of
 * growing memory, and the thread count is clamped by what the device actually
 * reports — on an 8-core SoC where the game already owns four big cores, "more
 * workers" is a frame-time tax, not a speedup.
 *
 * <p>Pausing on an unfocused window is the piece that matters most on Android: the
 * alternative is a background app that keeps burning CPU and battery for nobody.
 */
public final class WorkerTuningFeature implements Feature {

    @Override
    public FeatureId id() {
        return FeatureId.WORKER_TUNING;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.integer(ConfigSchema.CULL_ENTITY_WORKERS) > 0
                || settings.bool(ConfigSchema.CPU_PAUSE_IDLE);
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        if (ctx.device() != null && ctx.device().cores() <= 2) {
            return "this device reports two cores or fewer; background workers would only take frames away "
                    + "from the render thread";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        apply(ctx);
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        if (services != null) {
            services.pools().pauseForIdle(false);
        }
    }

    public void apply(FeatureRuntimeContext ctx) {
        Settings s = ctx.settings();
        DeviceCapabilities device = ctx.device();
        ClientServices services = Services.of(ctx);
        if (services == null) {
            return;
        }
        int requested = Math.max(1, s.integer(ConfigSchema.CULL_ENTITY_WORKERS));
        int threads = device == null ? requested
                : device.workerThreads(requested, Math.max(2, device.cores() / 2),
                        s.bool(ConfigSchema.ANDROID_BIGLITTLE));
        services.visibility().setWorkers(threads);
        services.pools().pauseForIdle(s.bool(ConfigSchema.CPU_PAUSE_IDLE) && services.isIdle());
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        TaskPools pools = services == null ? null : services.pools();
        return pools == null ? "no pools"
                : "pools=" + pools.pools().size() + " queued=" + pools.queueTotal()
                        + " dropped=" + pools.droppedTotal() + " paused=" + pools.isPaused()
                        + " scratchReuse=" + ctx.settings().bool(ConfigSchema.CPU_SCRATCH_REUSE);
    }
}
