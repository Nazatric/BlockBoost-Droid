package dev.blockboost.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.minecraft.client.gui.screens.Screen;

import dev.blockboost.client.ui.BlockBoostSettingsScreen;

/**
 * Optional Mod Menu integration: the single method Mod Menu calls to get a mod's own
 * config screen.
 *
 * <p>BlockBoost does not depend on Mod Menu to be configured — there is a keybind
 * and a button on the vanilla options screen — so this is convenience only. It is
 * compiled against Mod Menu as a {@code compileOnly} dependency and reached through
 * the {@code "modmenu"} entrypoint, which the loader only resolves when Mod Menu is
 * actually installed. Absent Mod Menu, nothing here runs, so nothing here can fail.
 */
public final class BlockBoostModMenuApiImpl implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return BlockBoostModMenuApiImpl::create;
    }

    private static Screen create(Screen parent) {
        return BlockBoostSettingsScreen.create(parent);
    }
}
