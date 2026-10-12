package dev.blockboost;

import net.fabricmc.api.ModInitializer;

/**
 * Mod entrypoint. Everything is delegated so a failure here is loud and
 * attributable instead of a half-initialized game.
 */
public final class BlockBoostMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // The main entrypoint runs before the client entrypoint on the client, so the
        // runtime's own client flag is not set yet here - the environment type is the
        // only honest signal available at this point.
        BlockBoostRuntime.get().start(false);
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                != net.fabricmc.loader.api.FabricLoader.EnvType.CLIENT) {
            BlockBoost.logInfo("loaded outside the client: this mod's systems are client-side"
                    + " and stay idle here by design");
        }
    }
}
