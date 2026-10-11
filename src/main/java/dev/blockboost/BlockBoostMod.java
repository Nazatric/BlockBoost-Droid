package dev.blockboost;

import net.fabricmc.api.ModInitializer;

/**
 * Mod entrypoint. Everything is delegated so a failure here is loud and
 * attributable instead of a half-initialized game.
 */
public final class BlockBoostMod implements ModInitializer {
    @Override
    public void onInitialize() {
        BlockBoostRuntime.get().start(false);
        if (!BlockBoostRuntime.get().isClientSide()) {
            BlockBoost.logInfo("loaded on a non-client environment: rendering systems stay idle by design");
        }
    }
}
