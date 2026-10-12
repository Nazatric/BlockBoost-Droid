package dev.blockboost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;

import dev.blockboost.client.HotState;

/**
 * Block entity visibility culling for 26.1.x.
 *
 * <p>Block entities are the worst per-unit cost on a phone: a chest, a sign and a
 * spawner each pay a model lookup, a render-state allocation and their own draw
 * call, and a single village or mob farm can hold thousands of them. Skipping state
 * extraction — rather than the draw — is what actually saves time, because the
 * extraction is where the allocation and the model work happen.
 *
 * <p>Two things must never be culled, and both are decided by vanilla rather than by
 * a hand-written list, so the rule survives modded blocks and future updates:
 * a block entity with no renderer at all (nothing to skip), and a renderer that
 * says it draws off-screen (beacon beams, conduit lasers, which are visible far
 * beyond their own block).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Shadow
    public abstract <E extends BlockEntity> BlockEntityRenderer getRenderer(E blockEntity);

    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true)
    private void blockboost$tryExtract(BlockEntity blockEntity, float partialTicks,
                                       net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay,
                                       CallbackInfoReturnable<BlockEntityRenderState> info) {
        if (blockEntity == null) {
            return;
        }
        BlockEntityRenderer renderer = getRenderer(blockEntity);
        if (renderer == null || renderer.shouldRenderOffScreen()) {
            return;
        }
        if (!HotState.skipBlockEntity(blockEntity)) {
            return;
        }
        // A null state is vanilla's own "this block entity has nothing to draw"
        // shape, so every caller already handles it.
        info.setReturnValue(null);
    }
}
