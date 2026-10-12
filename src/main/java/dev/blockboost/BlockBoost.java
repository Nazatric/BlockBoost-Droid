package dev.blockboost;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BlockBoost — Android Performance Engine.
 *
 * <p>Common entrypoint. Everything that can run without a client is wired up
 * here so the mod also loads cleanly on a dedicated server (where it is a no-op
 * except for metadata and logging) instead of crashing.
 *
 * <p>The real optimization systems live in {@code dev.blockboost.client}; the
 * shared, game-independent logic (config engine, bounded caches, statistics,
 * thread pools) lives in {@code dev.blockboost.config|util|device|core} and is
 * covered by plain unit tests that do not need a running game.
 */
public final class BlockBoost {
    public static final String MOD_ID = "blockboost";
    public static final String MOD_NAME = "BlockBoost";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** Bumped whenever the on-disk config layout changes; drives migrations. */
    public static final int CONFIG_VERSION = 3;

    private static volatile String cachedVersion;

    private BlockBoost() {
    }

    /**
     * The version as it appears in the shipped {@code fabric.mod.json}, so a report
     * can never quote a stale literal. Unknown only if the loader metadata is
     * missing, which means the jar was repackaged by something else.
     */
    public static String version() {
        String v = cachedVersion;
        if (v != null) {
            return v;
        }
        try {
            v = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("0.0.0+unknown");
        } catch (RuntimeException | LinkageError e) {
            v = "0.0.0+unknown";
        }
        cachedVersion = v;
        return v;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static void logInfo(String message) {
        LOG.info("[{}] {}", MOD_NAME, message);
    }

    public static void logWarn(String message) {
        LOG.warn("[{}] {}", MOD_NAME, message);
    }
}
