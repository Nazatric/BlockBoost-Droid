package dev.blockboost.client.features;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;

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
            reloadRenderer(client);
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

    private static volatile int requestedCap = 0;
    private static volatile int vanillaCap = -1;

    /**
     * The ceiling the user asked for, whether or not the game can honour the number.
     * Kept so the idle throttle can put the previous value back.
     */
    public static int requestedFrameCap() {
        return requestedCap;
    }

    /**
     * Puts a frame ceiling on the game through vanilla's own limiter, and never through
     * vsync or a sleep of ours.
     *
     * <p>{@code FramerateLimitTracker} is the object the render loop parks against, so a
     * value written here behaves exactly like the video-setting cap: it is honoured by
     * whatever pacing the game is already doing, and it works under a GL translator that
     * treats the swap interval in its own way. Requesting 0 hands the limit back to the
     * value the game had before we first touched it, which is the only honest way to say
     * "uncapped" - the game may have its own reasons to limit, including ones the user
     * set and ones the operating system imposes.
     *
     * @return the limit in force afterwards, or -1 when it could not be written
     */
    public static void noteRequestedFrameCap(Minecraft client, int cap) {
        requestedCap = Math.max(0, Math.min(1000, cap));
        if (client == null) {
            return;
        }
        try {
            var tracker = client.getFramerateLimitTracker();
            if (tracker == null) {
                return;
            }
            if (vanillaCap < 0) {
                vanillaCap = Math.max(0, tracker.getFramerateLimit());
            }
            int want = requestedCap > 0 ? requestedCap : vanillaCap;
            if (tracker.getFramerateLimit() != want) {
                tracker.setFramerateLimit(want);
            }
        } catch (RuntimeException | LinkageError e) {
            // A missing tracker means this build paces frames some other way. Refusing to
            // write a cap is right; inventing our own limiter is what made frames worse.
            BlockBoost.LOG.warn("[BlockBoost] frame cap not applied: this build exposes no"
                    + " FramerateLimitTracker, so no ceiling was written", e);
        }
    }

    /**
     * Why the game is limiting itself right now. Reported verbatim from vanilla, because
     * "the phone decided to draw at 30" is otherwise invisible and gets blamed on the mod.
     */
    public static String throttleReport(Minecraft client) {
        try {
            var tracker = client == null ? null : client.getFramerateLimitTracker();
            if (tracker == null) {
                return "not available on this build";
            }
            return tracker.getFramerateLimit() + " fps limit, throttle reason "
                    + tracker.getThrottleReason()
                    + (tracker.isHeavilyThrottled() ? " (heavy)" : "");
        } catch (RuntimeException | LinkageError e) {
            return "not available on this build";
        }
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
                reloadRenderer(client);
            });
            case "biomeBlendRadius" -> write(key, () -> client.options.biomeBlendRadius().set((Integer) previous));
            case "entityDistanceScaling" ->
                    write(key, () -> client.options.entityDistanceScaling().set((Double) previous));
            case "particles" -> write(key, () -> client.options.particles().set((ParticleStatus) previous));
            case "ambientOcclusion" -> write(key, () -> client.options.ambientOcclusion().set((Boolean) previous));
            case "entityShadows" -> write(key, () -> client.options.entityShadows().set((Boolean) previous));
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

    /**
     * Options that the renderer baked into its buffers (mipmaps, ambient occlusion)
     * need a renderer reload. {@code allChanged()} is vanilla's own entry point for
     * that — the same call its options screen makes — so we ask for it instead of
     * touching GL state ourselves.
     */
    private static void reloadRenderer(Minecraft client) {
        try {
            if (client.levelRenderer != null) {
                client.levelRenderer.allChanged();
            }
        } catch (RuntimeException | LinkageError e) {
            BlockBoost.LOG.debug("[BlockBoost] renderer reload request skipped: {}", e.toString());
        }
    }

    static void requestRendererReload(Minecraft client) {
        reloadRenderer(client);
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
