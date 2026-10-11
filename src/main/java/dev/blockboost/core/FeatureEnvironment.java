package dev.blockboost.core;

/**
 * Marker for the per-side context handed to {@link Feature}s. The client
 * implementation carries the {@code Minecraft} instance and the shared
 * services; keeping the interface free of Minecraft types lets the registry and
 * its state machine be unit tested without a game.
 */
public interface FeatureEnvironment {
    /** True while a world is loaded and rendering is actually happening. */
    boolean hasActiveLevel();

    /** True when the client is running in a game test / headless harness. */
    default boolean headless() {
        return false;
    }
}
