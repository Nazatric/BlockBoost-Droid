package dev.blockboost.client.features;

import dev.blockboost.client.HotState;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * Skips block entity render-state extraction behind solid terrain.
 *
 * <p>Block entities are the most expensive per-unit draws on a phone — a chest or a
 * sign costs a model lookup, a state allocation and its own draw call — and a
 * village or a mob farm stacks hundreds of them. The block position is a stable cache
 * key, so the verdict is computed once and reused for a configurable number of
 * ticks; a reload or a camera jump clears it, because a stale "hidden" is the one
 * mistake a user sees immediately.
 */
public final class BlockEntityCullingFeature implements Feature {

    @Override
    public FeatureId id() {
        return FeatureId.BLOCK_ENTITY_CULLING;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.bool(ConfigSchema.CULL_BLOCK_ENTITY);
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        String suppressed = ctx.mods().suppressionReason(id());
        if (suppressed != null) {
            return "suppressed: " + suppressed;
        }
        if (!settings.bool(ConfigSchema.CULL_BLOCK_ENTITY_OCCLUSION)) {
            return "block entity culling only decides anything when occlusion is enabled; with it off, vanilla "
                    + "already skips what is outside the view, so there is nothing left for us to do";
        }
        if (settings.integer(ConfigSchema.CULL_BLOCK_ENTITY_CACHE_TICKS) <= 0) {
            return "a cache lifetime of zero ticks would re-test every block entity every frame for no gain";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        HotState.refresh();
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        HotState.invalidate("block entity culling switched off");
        HotState.refresh();
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        return "occlusion=" + HotState.blockEntityOcclusion + " cacheTicks=" + HotState.blockEntityCacheTicks
                + " culled=" + HotState.culledBlockEntities;
    }
}
