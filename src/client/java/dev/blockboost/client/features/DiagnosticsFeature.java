package dev.blockboost.client.features;

import dev.blockboost.client.ClientServices;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * The measurement layer: frame samples, percentiles, chunk and GC counters, and the
 * report writer.
 *
 * <p>Everything BlockBoost claims about itself has to come from here, which is why
 * it is a feature with an off switch rather than unconditional logging: the sampler
 * keeps a fixed ring of doubles and nothing else, so turning diagnostics off removes
 * the last per-frame write and the last allocation the mod makes. Reports are only
 * ever written into the game directory when asked for, and there is no network code
 * in this mod at all — a launcher on a phone should not need my word that nothing is
 * uploaded.
 */
public final class DiagnosticsFeature implements Feature {

    @Override
    public FeatureId id() {
        return FeatureId.DIAGNOSTICS;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.bool(ConfigSchema.DIAG_ENABLED);
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        if (settings.integer(ConfigSchema.DIAG_WINDOW) < 16) {
            return "the rolling window must hold at least 16 frames for percentiles to mean anything";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        if (services != null) {
            services.frames().setWindow(ctx.settings().integer(ConfigSchema.DIAG_WINDOW));
        }
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        if (services == null) {
            return "diagnostics not started";
        }
        return services.frames().summarizeWindow() + " | reports=" + services.diagnostics().reportCount()
                + (services.diagnostics().lastReportPath() == null ? "" : " last=" + services.diagnostics().lastReportPath());
    }
}
