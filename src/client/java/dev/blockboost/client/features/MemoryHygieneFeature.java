package dev.blockboost.client.features;

import dev.blockboost.client.ClientServices;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;
import dev.blockboost.device.DeviceCapabilities;

/**
 * Bounded caches, scratch reuse, and honest heap reporting.
 *
 * <p>The claim this makes is narrow on purpose: BlockBoost's own allocations are
 * capped (the visibility table, the scratch pool, the diagnostic ring), and they
 * are released when a level unloads, so a long session with many world changes does
 * not creep upwards. It never calls {@code System.gc()}, it never shrinks or grows
 * the launcher's heap, and it never reports a "memory freed" number — under a
 * translated GL stack, a forced collection during a frame costs more than the
 * garbage it removes, and an honest mod says so instead.
 *
 * <p>The GC pause log is read from the platform MXBeans: observation only, safe on
 * Android, and the reason the report can say "your stutters were a 240 ms collection"
 * instead of guessing.
 */
public final class MemoryHygieneFeature implements Feature {

    @Override
    public FeatureId id() {
        return FeatureId.MEMORY_HYGIENE;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.bool(ConfigSchema.MEM_RELEASE_ON_EXIT)
                || settings.integer(ConfigSchema.MEM_GC_LOG_MS) >= 0
                || settings.bool(ConfigSchema.CPU_SCRATCH_REUSE);
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        if (settings.integer(ConfigSchema.MEM_SCRATCH_CAP_KB) <= 0) {
            return "a scratch cap of zero would leave nothing to reuse";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        if (services != null) {
            services.gc().setReportThresholdMs(ctx.settings().integer(ConfigSchema.MEM_GC_LOG_MS));
        }
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        if (services != null) {
            services.gc().setReportThresholdMs(-1);
        }
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        DeviceCapabilities device = ctx.device();
        ClientServices services = Services.of(ctx);
        String heap = device == null ? "heap unknown" : device.heapSummary();
        String scratch = "scratch reused=" + ctx.scratch().reuses() + " fresh=" + ctx.scratch().freshAllocations()
                + " refused=" + ctx.scratch().refusals() + " cap=" + ctx.scratch().maxBytesPerThread() + "B/thread";
        String gc = services == null ? "" : " " + services.gc().hudLine();
        return heap + " " + scratch + gc;
    }
}
