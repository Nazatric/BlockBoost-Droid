package dev.blockboost.core;

import dev.blockboost.compat.ModCompatibility;
import dev.blockboost.config.Settings;
import dev.blockboost.device.DeviceCapabilities;
import dev.blockboost.util.ScratchBuffers;
import dev.blockboost.util.TaskPools;

/**
 * The services a {@link Feature} is allowed to touch. Passing one object instead
 * of pulling statics keeps features independent and makes it obvious what each
 * subsystem depends on when it is ported to a new Minecraft version.
 */
public record FeatureRuntimeContext(
        Settings settings,
        DeviceCapabilities device,
        ModCompatibility mods,
        TaskPools pools,
        ScratchBuffers scratch,
        FeatureEnvironment environment,
        FeatureDiagnostics diagnostics) {

    /** Callback surface the diagnostics service exposes to features (no MC types). */
    public interface FeatureDiagnostics {
        void counter(String name, long value);

        void note(String message);
    }
}
