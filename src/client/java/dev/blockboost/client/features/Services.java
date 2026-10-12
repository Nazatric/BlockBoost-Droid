package dev.blockboost.client.features;

import dev.blockboost.client.ClientServices;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * One place where a feature reaches the client services.
 *
 * <p>{@link FeatureRuntimeContext} is deliberately free of Minecraft types so the
 * config/util/core layers can be unit tested without a game; the client services are
 * handed in through its {@code environment}. Casting here, once, keeps that
 * trade-off instead of leaking {@code Minecraft} into every feature.
 */
public final class Services {

    private Services() {
    }

    public static ClientServices of(FeatureRuntimeContext ctx) {
        if (ctx == null || !(ctx.environment() instanceof ClientServices services)) {
            return null;
        }
        return services;
    }
}
