package dev.blockboost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import dev.blockboost.client.HotState;

/**
 * Entity visibility culling for 26.1.x.
 *
 * <p>Minecraft 26.1 builds a render state per entity on the render thread and then
 * draws the states, so the cheapest correct place to skip work is state extraction:
 * no vertex building, no model lookup, no texture binds, and no second pass over
 * the entity list. We return an <em>empty</em> state (invisible, no nametag data)
 * instead of cancelling the whole loop, which is what vanilla's own "don't draw
 * this" shape looks like and keeps every caller's assumptions intact.
 *
 * <p>Nametags survive the cull on purpose: an armor stand or a named pet that loses
 * its label looks like a bug even when the model itself was hidden, so the label is
 * kept while the expensive model is skipped.
 */
@Mixin(LevelRenderer.class)
public final class LevelRendererMixin {

    @Inject(method = "extractEntity", at = @At("HEAD"), cancellable = true)
    private void blockboost$extractEntity(Entity entity, float partialTick,
                                          CallbackInfoReturnable<EntityRenderState> cir) {
        if (!HotState.skipEntity(entity)) {
            return;
        }
        EntityRenderState state = new EntityRenderState();
        // INTERACTION is the entity type with no renderer of its own: an invisible
        // state of that type draws nothing while still satisfying the state's
        // non-null contract for everything downstream.
        state.entityType = EntityType.INTERACTION;
        state.isInvisible = true;
        state.x = Mth.lerp(partialTick, entity.xOld, entity.getX());
        state.y = Mth.lerp(partialTick, entity.yOld, entity.getY());
        state.z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        if (HotState.keepNametags && entity.shouldShowName()) {
            state.nameTag = entity.getDisplayName();
        }
        cir.setReturnValue(state);
    }

    
    /**
     * A resource reload or a full-visibility change invalidates every cached verdict:
     * block state may have changed everywhere at once, and stale occlusion is the
     * one failure mode that shows as a missing mob.
     */
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void blockboost$allChanged(CallbackInfo ci) {
        HotState.invalidate("level renderer allChanged");
    }
}
