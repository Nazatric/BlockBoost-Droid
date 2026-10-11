package dev.blockboost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import dev.blockboost.client.BlockBoostClient;
import dev.blockboost.client.ClientServices;
import dev.blockboost.client.HotState;
import dev.blockboost.client.features.FramePacingFeature;

/**
 * The single frame hook BlockBoost needs from {@code Minecraft}.
 *
 * <p>Both injections are HEAD/TAIL on one method and both are guarded, so the
 * worst BlockBoost can do here is lose its own measurements — never break the
 * frame. {@code renderFrame} is used rather than {@code runTick} because the
 * engine's per-frame work (frame timing, HUD counters, adaptive decisions) has to
 * line up with frames, not ticks: on Android the two are routinely decoupled by
 * throttling and vsync.
 */
@Mixin(Minecraft.class)
public final class MinecraftFrameMixin {

    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void blockboost$frameStart(CallbackInfo ci) {
        ClientServices services = BlockBoostClient.services();
        if (services == null) {
            return;
        }
        try {
            HotState.newFrame();
            services.onFrameStart();
        } catch (RuntimeException | LinkageError e) {
            services.noteHookFailure("frame start", e);
        }
    }

    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void blockboost$frameEnd(CallbackInfo ci) {
        ClientServices services = BlockBoostClient.services();
        if (services == null) {
            return;
        }
        try {
            services.onFrameEnd();
            FramePacingFeature.afterFrame((Minecraft) (Object) this);
            BlockBoostClient.benchExpired((Minecraft) (Object) this);
        } catch (RuntimeException | LinkageError e) {
            services.noteHookFailure("frame end", e);
        }
    }
}
