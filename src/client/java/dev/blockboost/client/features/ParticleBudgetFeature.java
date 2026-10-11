package dev.blockboost.client.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;

import dev.blockboost.client.HotState;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRuntimeContext;
import dev.blockboost.device.DeviceClass;
import dev.blockboost.device.DeviceCapabilities;

/**
 * Particle density, applied through vanilla's particle mode.
 *
 * <p>Why an option and not a hook: the particle engine's per-tick list is private
 * state whose shape has moved in every recent Minecraft version, and a mod that
 * pokes at it with reflection either breaks on the next update or quietly stops
 * working. Vanilla's own DECREASED/MINIMAL modes are implemented inside the engine —
 * they skip emission where it is cheapest and they are correct about gameplay
 * particles — so BlockBoost chooses the mode from the configured budget and the
 * device class instead of pretending to count particles itself.
 *
 * <p>The budget therefore has a real, measurable effect (which vanilla tier is
 * selected and when the adaptive controller is allowed to step it down), and the
 * {@code keep_gameplay} flag is honoured by never choosing MINIMAL when it is on,
 * because MINIMAL hides rain and damage feedback that players read as information.
 */
public final class ParticleBudgetFeature implements Feature {

    private volatile boolean applied;

    @Override
    public FeatureId id() {
        return FeatureId.PARTICLE_BUDGET;
    }

    @Override
    public boolean enabledByConfig(Settings settings) {
        return settings.integer(ConfigSchema.PARTICLE_MAX_PER_TICK) > 0;
    }

    @Override
    public String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        // Nothing about picking a vanilla particle tier depends on the renderer or
        // the GPU, so there is no capability that can refuse this one.
        return null;
    }

    /** The vanilla tier a per-frame budget maps to. Kept public so tests can pin it. */
    public static ParticleStatus tierFor(int maxPerTick, boolean keepGameplay, DeviceClass deviceClass) {
        if (maxPerTick <= 0) {
            return null; // feature off
        }
        boolean weak = deviceClass == DeviceClass.LOW;
        if (maxPerTick <= (weak ? 96 : 64)) {
            return keepGameplay ? ParticleStatus.DECREASED : ParticleStatus.MINIMAL;
        }
        if (maxPerTick <= (weak ? 512 : 256)) {
            return ParticleStatus.DECREASED;
        }
        return ParticleStatus.ALL;
    }

    @Override
    public void onActivate(FeatureRuntimeContext ctx) {
        apply(ctx);
    }

    @Override
    public void onDeactivate(FeatureRuntimeContext ctx) {
        applied = false;
        VanillaOptions.restore(Minecraft.getInstance(), "particles");
        VanillaOptions.persist(Minecraft.getInstance());
        HotState.refresh();
    }

    @Override
    public String describeState(FeatureRuntimeContext ctx) {
        Minecraft client = Minecraft.getInstance();
        ParticleStatus now = client == null || client.options == null ? null : client.options.particles().get();
        return "budget=" + HotState.particleBudget + " per frame, vanilla mode=" + now
                + ", suppressed=" + HotState.suppressedParticles + (applied ? "" : " (not applied)");
    }

    /** Re-reads the config and writes the tier. Called on activation and on change. */
    public void apply(FeatureRuntimeContext ctx) {
        Settings s = ctx.settings();
        DeviceCapabilities device = ctx.device();
        DeviceClass cls = device == null ? DeviceClass.UNKNOWN : device.deviceClass();
        ParticleStatus tier = tierFor(s.integer(ConfigSchema.PARTICLE_MAX_PER_TICK),
                s.bool(ConfigSchema.PARTICLE_KEEP_GAMEPLAY), cls);
        if (tier == null) {
            return;
        }
        VanillaOptions.particles(Minecraft.getInstance(), tier);
        VanillaOptions.persist(Minecraft.getInstance());
        applied = true;
        ctx.diagnostics().counter("particles.tier", tier == ParticleStatus.ALL ? 0
                : tier == ParticleStatus.DECREASED ? 1 : 2);
    }
}
