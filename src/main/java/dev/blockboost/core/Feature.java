package dev.blockboost.core;

import dev.blockboost.config.Settings;

/**
 * One switchable optimization subsystem.
 *
 * <p>Lifecycle: {@link #supports} and {@link #enabledByConfig} decide the
 * target state, {@link #apply} is called whenever the state changes (including
 * from the settings screen at runtime), and {@link #describeState} feeds the
 * diagnostics report. Implementations must leave vanilla behaviour intact when
 * they are not applied: the hooks check {@link FeatureRegistry#isActive}.
 */
public interface Feature {

    FeatureId id();

    /** Cheap, allocation-free config check; called per state evaluation only. */
    boolean enabledByConfig(Settings settings);

    /** Hard capability gate (renderer, build, mod conflicts). May refuse. */
    default String refusalReason(Settings settings, FeatureRuntimeContext ctx) {
        return null; // null = nothing blocks this feature
    }

    /** Called when becoming active. Throw to be disabled with a logged reason. */
    default void onActivate(FeatureRuntimeContext ctx) throws Exception {
    }

    /** Called when becoming inactive. Must not throw; used to release resources. */
    default void onDeactivate(FeatureRuntimeContext ctx) {
    }

    /** One line of live state for the diagnostics screen and the report. */
    default String describeState(FeatureRuntimeContext ctx) {
        return "";
    }
}
