package dev.blockboost.client.features;

import net.minecraft.client.Minecraft;

import dev.blockboost.client.ClientServices;
import dev.blockboost.client.HotState;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * Frame cap, vsync and the unfocused-window throttle.
 *
 * <p>All three are vanilla options rather than GL calls, which is the only way this
 * is correct on a phone: under MobileGlues/GL4ES, setting swap interval yourself is
 * a driver-dependent experiment (the extension may be absent, or it may apply to a
 * different context than the one drawing your frame), while {@code enableVsync} and
 * {@code maxFramerate}-equivalent ceiling, vsync and the idle reduction go through
 * the option set the game already owns, never through the graphics API.
 *
 * <p>The idle throttle is the one with the biggest measured effect on a phone that
 * is not in a world at all — a menu at 300 fps is a phone at 100% GPU load for no
 * reason, and battery and thermals are the whole game on that device.
 */
public final class FramePacingFeature implements Feature {

    private static volatile boolean idleApplied;
    private static volatile int lastCap = Integer.MIN_VALUE;
    private static volatile int lastRenderDistance = -1;
    private static volatile String lastParticles;
    private static volatile Boolean lastVsync;

    @Override
    public FeatureId id() {
        return FeatureId.FRAME_PACING;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.integer(ConfigSchema.RENDER_FRAME_CAP) >= 0
                || !settings.is(ConfigSchema.RENDER_VSYNC, "ignore")
                || settings.bool(ConfigSchema.RENDER_IDLE_THROTTLE);
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        if (!settings.bool(ConfigSchema.RENDER_VIDEO_OVERRIDES)) {
            return "frame cap and vsync are written into the game's video settings, which needs "
                    + "'allow video overrides' switched on";
        }
        if (settings.integer(ConfigSchema.RENDER_FRAME_CAP) > 1000) {
            return "frame cap above 1000 is a typo, not a setting";
        }
        return null;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        apply(ctx, true);
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        Minecraft client = Minecraft.getInstance();
        VanillaOptions.restore(client, "maxFramerate");
        VanillaOptions.restore(client, "enableVsync");
        VanillaOptions.persist(client);
        idleApplied = false;
        lastCap = Integer.MIN_VALUE;
        lastVsync = null;
    }

    /** Applies cap and vsync from config. Safe to call on every config change. */
    public void apply(FeatureRuntimeContext ctx, boolean persist) {
        Settings s = ctx.settings();
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        int cap = s.integer(ConfigSchema.RENDER_FRAME_CAP);
        String vsync = s.enumValue(ConfigSchema.RENDER_VSYNC);
        if (cap != lastCap) {
            // vsync is the only supported way to cap frames here, so the cap is only
            // allowed to drive it while the user has left vsync on "ignore"; otherwise
            // two settings would fight and the one you touched would lose.
            if ("auto".equals(vsync)) {
                VanillaOptions.maxFramerate(client, Math.max(0, cap));
            } else {
                VanillaOptions.noteRequestedFrameCap(Math.max(0, cap));
            }
            lastCap = cap;
        }
        if ("on".equals(vsync) && !Boolean.TRUE.equals(lastVsync)) {
            VanillaOptions.vsync(client, true);
            lastVsync = Boolean.TRUE;
        } else if ("off".equals(vsync) && !Boolean.FALSE.equals(lastVsync)) {
            VanillaOptions.vsync(client, false);
            lastVsync = Boolean.FALSE;
        }
        if (persist) {
            VanillaOptions.persist(client);
        }
        HotState.refresh();
    }

    /**
     * Called from the frame hook at the end of every frame: cheap checks only, and it
     * changes the cap at most once per state transition.
     */
    public static void afterFrame(Minecraft client) {
        if (!HotState.framePacing || client == null) {
            return;
        }
        boolean hidden = false;
        try {
            var window = client.getWindow();
            hidden = window != null && (window.isMinimized() || !window.isFocused());
        } catch (RuntimeException e) {
            // Focused state is unreliable behind some translators; treat as visible so
            // a wrong signal never costs frames during play.
            hidden = false;
        }
        if (hidden == idleApplied) {
            return;
        }
        idleApplied = hidden;
        if (hidden) {
            lastCap = VanillaOptions.requestedFrameCap();
            lastRenderDistance = client.options == null ? -1 : client.options.getEffectiveRenderDistance();
            lastParticles = client.options == null || client.options.particles().get() == null
                    ? null : client.options.particles().get().name();
            // An unfocused window on a phone means the screen is off or another app is
            // foreground: cut the work that costs power and heat, all of it reversible.
            VanillaOptions.maxFramerate(client, Math.max(1, HotState.idleFrameCap));
            if (lastRenderDistance > 4) {
                VanillaOptions.renderDistance(client, Math.max(2, lastRenderDistance - 2));
                VanillaOptions.simulationDistance(client, Math.max(2, lastRenderDistance - 3));
            }
            VanillaOptions.particles(client, net.minecraft.server.level.ParticleStatus.MINIMAL);
        } else if (lastCap != Integer.MIN_VALUE) {
            VanillaOptions.maxFramerate(client, lastCap);
            if (lastRenderDistance > 0) {
                VanillaOptions.renderDistance(client, lastRenderDistance);
                VanillaOptions.simulationDistance(client, Math.max(2, lastRenderDistance - 1));
            }
            if (lastParticles != null) {
                try {
                    VanillaOptions.particles(client,
                            net.minecraft.server.level.ParticleStatus.valueOf(lastParticles));
                } catch (IllegalArgumentException ignored) {
                    // A tier name from another version: leaving it as-is is correct.
                }
            }
        }
    }

    public static boolean idleThrottleEngaged() {
        return idleApplied;
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        ClientServices services = Services.of(ctx);
        return "cap=" + ctx.settings().integer(ConfigSchema.RENDER_FRAME_CAP)
                + " vsync=" + ctx.settings().enumValue(ConfigSchema.RENDER_VSYNC)
                + " idleThrottle=" + (idleApplied ? "engaged" : "ready")
                + (services == null ? "" : " measured=" + services.frames().hudFpsText());
    }
}
