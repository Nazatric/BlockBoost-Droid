package dev.blockboost.client.features;

import dev.blockboost.client.ClientServices;
import dev.blockboost.client.HotState;
import dev.blockboost.client.culling.AsyncVisibilityEngine;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * Skips entity render-state extraction for entities the camera cannot see.
 *
 * <p>The mode selector is the honest part of this feature: {@code off} does nothing,
 * {@code frustum} relies on vanilla's own frustum pass (free, always correct), and
 * {@code frustum_occlusion} adds the occlusion tests that actually save GPU work in
 * a farm or a village. The async flag only moves the arithmetic to worker threads
 * against a snapshot — it never reads the world from another thread, so a wrong
 * answer can never become a corrupted chunk.
 */
public final class EntityCullingFeature implements Feature {

    @Override
    public FeatureId id() {
        return FeatureId.ENTITY_CULLING;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.bool(ConfigSchema.CULL_ENTITY)
                && !settings.is(ConfigSchema.CULL_ENTITY_MODE, "off");
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        String suppressed = ctx.mods().suppressionReason(id());
        if (suppressed != null) {
            return "suppressed: " + suppressed;
        }
        if (settings.bool(ConfigSchema.COMPAT_SAFE_MODE)) {
            return "compatibility safe mode is on; visibility culling is the first thing to switch off and "
                    + "the last thing worth switching back on while debugging";
        }
        if (settings.is(ConfigSchema.CULL_ENTITY_MODE, "frustum_occlusion")
                && settings.integer(ConfigSchema.CULL_ENTITY_MAX_DISTANCE) <= 0) {
            return "occlusion mode needs a positive max distance (it bounds the ray march)";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        HotState.refresh();
        ctx.diagnostics().note("entity culling active (" + ctx.settings().enumValue(ConfigSchema.CULL_ENTITY_MODE)
                + (ctx.settings().bool(ConfigSchema.CULL_ENTITY_ASYNC) ? ", async" : "") + ")");
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        if (services != null) {
            services.visibility().invalidateAll("culling turned off");
        }
        HotState.refresh();
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        AsyncVisibilityEngine engine = HotState.visibility;
        return (engine == null ? "engine not ready" : engine.describe()) + " | " + HotState.describeCulling();
    }
}
