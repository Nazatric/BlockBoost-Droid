package dev.blockboost.client.features;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;

import dev.blockboost.BlockBoost;

/**
 * Every write BlockBoost makes to a vanilla video option goes through here.
 *
 * <p>Two rules. First: never touch GL state or a renderer internal — a video option
 * is the supported, reload-safe channel, and on 26.2+ (where the renderer moves to
 * Vulkan and the GL path is gone) it is the only one that survives. Second: remember
 * what we changed, so switching a feature off puts the user's own value back. A mod
 * that keeps overriding your video settings after you turned it off is worse than no
 * mod.
 *
 * <p>Every write is clamped to a range vanilla accepts, and an option that has moved
 * or been renamed in this exact build is skipped with one log line rather than
 * throwing: a missing option should cost a quality tweak, not a startup.
 */
public final class VanillaOptions {

    private static final Map<String, Object> PREVIOUS = new LinkedHashMap<>();
    private static boolean warned;

    private VanillaOptions() {
    }

    // ── individual options (negative = "leave vanilla alone") ─────────────────
    public static void renderDistance(Minecraft client, int chunks) {
        if (chunks < 0 || client == null || client.options == null) {
            return;
        }
        int value = Math.max(2, Math.min(32, chunks));
        write("renderDistance", () -> {
        var option = client.options.renderDistance();
        Integer current = option.get();
        if (current == null || current != value) {
            remember("renderDistance", current);
            option.set(value);
        }
    });
    }

    public static void simulationDistance(Minecraft client, int chunks) {
        if (chunks < 0 || client == null || client.options == null) {
            return;
        }
        int value = Math.max(2, Math.min(32, chunks));
        write("simulationDistance", () -> {
        var option = client.options.simulationDistance();
        Integer current = option.get();
        if (current == null || current != value) {
            remember("simulationDistance", current);
            option.set(value);
        }
    });
    }

    public static void mipmap(Minecraft client, int levels) {
        if (levels < 0 || client == null || client.options == null) {
            return;
        }
        int value = Math.max(0, Math.min(4, levels));
        write("mipmapLevels", () -> {
            var option = client.options.mipmapLevels();
            Integer current = option.get();
            if (current == null || current != value) {
                remember("mipmapLevels", current);
                option.set(value);
            }
            client.updateMaxMipLevel(value);
        });
    }

    public static void biomeBlend(Minecraft client, int radius) {
        if (radius < 0 || client == null || client.options == null) {
            return;
        }
        int value = Math.max(0, Math.min(7, radius));
        write("biomeBlendRadius", () -> {
        var option = client.options.biomeBlendRadius();
        Integer current = option.get();
        if (current == null || current != value) {
            remember("biomeBlendRadius", current);
            option.set(value);
        }
    });
    }

    /** 100 percent means "vanilla"; lower values let entities pop out sooner but cheaper. */
    public static void entityDistance(Minecraft client, int percent) {
        if (percent < 0 || client == null || client.options == null) {
            return;
        }
        double value = Math.max(0.5D, Math.min(2.5D, percent / 100.0D));
        write("entityDistanceScaling", () -> {
            var option = client.options.entityDistanceScaling();
            Double current = option.get();
            if (current == null || Math.abs(current - value) > 0.001D) {
                remember("entityDistanceScaling", current);
                option.set(value);
            }
        });
    }

    public static void particles(Minecraft client, ParticleStatus status) {
        if (status == null || client == null || client.options == null) {
            return;
        }
        write("particles", () -> {
            var option = client.options.particles();
            if (option.get() != status) {
                remember("particles", option.get());
                option.set(status);
            }
        });
    }

    public static void ambientOcclusion(Minecraft client, boolean on) {
        if (client == null || client.options == null) {
            return;
        }
        write("ambientOcclusion", () -> {
            var option = client.options.ambientOcclusion();
            if (!Boolean.valueOf(on).equals(option.get())) {
                remember("ambientOcclusion", option.get());
                option.set(on);
            }
        });
    }

    public static void entityShadows(Minecraft client, boolean on) {
        if (client == null || client.options == null) {
            return;
        }
        write("entityShadows", () -> {
            var option = client.options.entityShadows();
            if (!Boolean.valueOf(on).equals(option.get())) {
                remember("entityShadows", option.get());
                option.set(on);
            }
        });
    }

    /** Frames per second; 0 means "as fast as the driver allows", like vanilla's slider. */
    public static void maxFramerate(Minecraft client, int cap) {
        if (client == null || client.options == null) {
            return;
        }
        int value = Math.max(0, Math.min(1000, cap));
        write("maxFramerate", () -> {
        var option = client.options.maxFramerate();
        Integer current = option.get();
        if (current == null || current != value) {
            remember("maxFramerate", current);
            option.set(value);
        }
    });
    }

    public static void vsync(Minecraft client, boolean on) {
        if (client == null || client.options == null) {
            return;
        }
        write("enableVsync", () -> {
            var option = client.options.enableVsync();
            if (!Boolean.valueOf(on).equals(option.get())) {
                remember("enableVsync", option.get());
                option.set(on);
            }
        });
    }

    /** Vanilla writes options.txt itself; we only ask it to do so. */
    public static void persist(Minecraft client) {
        if (client == null || client.options == null) {
            return;
        }
        write("save", client.options::save);
    }

    /** Puts one option back the way the user had it. */
    public static void restore(Minecraft client, String key) {
        Object previous = PREVIOUS.remove(key);
        if (previous == null || client == null || client.options == null) {
            return;
        }
        switch (key) {
            case "renderDistance" -> write(key, () -> client.options.renderDistance().set((Integer) previous));
            case "simulationDistance" -> write(key, () -> client.options.simulationDistance().set((Integer) previous));
            case "mipmapLevels" -> write(key, () -> {
                client.options.mipmapLevels().set((Integer) previous);
                client.updateMaxMipLevel((Integer) previous);
            });
            case "biomeBlendRadius" -> write(key, () -> client.options.biomeBlendRadius().set((Integer) previous));
            case "entityDistanceScaling" ->
                    write(key, () -> client.options.entityDistanceScaling().set((Double) previous));
            case "particles" -> write(key, () -> client.options.particles().set((ParticleStatus) previous));
            case "ambientOcclusion" -> write(key, () -> client.options.ambientOcclusion().set((Boolean) previous));
            case "entityShadows" -> write(key, () -> client.options.entityShadows().set((Boolean) previous));
            case "maxFramerate" -> write(key, () -> client.options.maxFramerate().set((Integer) previous));
            case "enableVsync" -> write(key, () -> client.options.enableVsync().set((Boolean) previous));
            default -> {
            }
        }
    }

    public static void restoreAll(Minecraft client) {
        for (String key : new java.util.ArrayList<>(PREVIOUS.keySet())) {
            restore(client, key);
        }
        PREVIOUS.clear();
        persist(client);
    }

    public static boolean overriding() {
        return !PREVIOUS.isEmpty();
    }

    public static String describe() {
        if (PREVIOUS.isEmpty()) {
            return "no vanilla options overridden";
        }
        StringBuilder sb = new StringBuilder("overridden:");
        PREVIOUS.forEach((k, v) -> sb.append(' ').append(k).append("=<was ").append(v).append('>'));
        return sb.toString();
    }

    private static void write(String key, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            // One line, not a stack trace per frame: the option is not available in
            // this build, which is a supported outcome.
            if (!warned) {
                warned = true;
                BlockBoost.LOG.warn("[BlockBoost] video option '{}' is not writable in this build; "
                        + "skipping option writes from now on", key, e);
            }
            PREVIOUS.remove(key);
        }
    }

    private static void remember(String key, Object value) {
        if (value != null) {
            PREVIOUS.putIfAbsent(key, value);
        }
    }
}
