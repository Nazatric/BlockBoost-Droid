package dev.blockboost.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;

import dev.blockboost.client.HotState;

/**
 * The hard per-frame ceiling on new particles.
 *
 * <p>{@code ParticleEngine.add(Particle)} is the single funnel every particle in the
 * game passes through — block breaking, footsteps, lava, splash, mob effects — so
 * capping it there is the only place a ceiling is both cheap (one counter, no
 * allocation) and complete (nothing sneaks around it). Cancelling the add means the
 * particle is simply never created: no list entry, no tick, no vertex, and nothing
 * downstream has to know we exist. Vanilla's own limit machinery
 * ({@code ParticleLimit}) still applies as usual, and so does the particle's
 * {@code remove()} path, because we never get far enough to confuse either.
 *
 * <p>Order matters and is deliberate: this runs at HEAD, so an exhausted budget costs
 * one integer decrement instead of a particle object. Objects already constructed by
 * the caller are garbage either way — that is the remaining cost of hooking the
 * funnel instead of every factory, and it is the honest trade for covering every
 * particle in one place.
 */
@Mixin(ParticleEngine.class)
public final class ParticleEngineMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void blockboost$add(Particle particle, CallbackInfo ci) {
        if (!HotState.takeParticleSlot()) {
            ci.cancel();
        }
    }
}
