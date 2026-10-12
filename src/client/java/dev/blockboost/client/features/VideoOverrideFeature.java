package dev.blockboost.client.features;

import net.minecraft.client.Minecraft;

import dev.blockboost.client.HotState;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * The user's own video settings, applied by BlockBoost instead of by hand.
 *
 * <p>Every key here is tri-state: {@code ignore} leaves vanilla exactly as it is,
 * {@code on}/{@code off} (or a number) writes the option. That matters on a launcher
 * where a bad value costs a whole session: someone who already tuned their own
 * render distance or mipmaps keeps it until they ask us to change it, and the
 * previous value is remembered so switching this off gives it back.
 *
 * <p>No renderer internals, no GL state, no shaders directory edits — the video
 * options screen is the game's supported interface for exactly these knobs, and it
 * survives the Vulkan transition that 26.2 starts.
 */
public final class VideoOverrideFeature implements Feature {

    @Override
    public FeatureId id() {
        return FeatureId.VIDEO_OVERRIDES;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.bool(ConfigSchema.RENDER_VIDEO_OVERRIDES);
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        if (ctx.environment() != null && ctx.environment().headless()) {
            return "not applying video option overrides in an automated test harness";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        apply(ctx, true);
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        VanillaOptions.restoreAll(Minecraft.getInstance());
        HotState.refresh();
    }

    /** Reads every tri-state key and writes the matching vanilla option. */
    public void apply(FeatureRuntimeContext ctx, boolean persist) {
        Settings s = ctx.settings();
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        VanillaOptions.mipmap(client, s.integer(ConfigSchema.VANILLA_MIPMAP));
        VanillaOptions.biomeBlend(client, s.integer(ConfigSchema.VANILLA_BIOME_BLEND));
        VanillaOptions.entityDistance(client, s.integer(ConfigSchema.VANILLA_ENTITY_DISTANCE));
        VanillaOptions.renderDistance(client, s.integer(ConfigSchema.VANILLA_RENDER_DISTANCE));
        VanillaOptions.simulationDistance(client, s.integer(ConfigSchema.VANILLA_SIMULATION_DISTANCE));
        int ao = s.triState(ConfigSchema.VANILLA_AMBIENT_OCCLUSION);
        if (ao == 1) {
            VanillaOptions.ambientOcclusion(client, true);
            VanillaOptions.requestRendererReload(client);
        } else if (ao == 0) {
            VanillaOptions.ambientOcclusion(client, false);
            VanillaOptions.requestRendererReload(client);
        }
        int shadows = s.triState(ConfigSchema.VANILLA_ENTITY_SHADOWS);
        if (shadows == 1) {
            VanillaOptions.entityShadows(client, true);
        } else if (shadows == 0) {
            VanillaOptions.entityShadows(client, false);
        }
        if (persist) {
            VanillaOptions.persist(client);
        }
        HotState.refresh();
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        return VanillaOptions.describe();
    }
}
