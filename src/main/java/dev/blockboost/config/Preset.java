package dev.blockboost.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seven quick presets.
 *
 * <p>A preset is a *patch*, not a replacement config: only the keys listed here
 * are written, so a user's unrelated choices (HUD corner, cache sizes, their own
 * render distance) survive a preset change. The settings screen shows the exact
 * key/value diff and requires confirmation before applying.
 */
public record Preset(String id, String nameKey, String tooltipKey, Map<SettingKey, Object> patch) {

    public static final String CUSTOM_ID = "custom";

    private static Map<SettingKey, Object> p() {
        return new LinkedHashMap<>();
    }

    // ── definitions ─────────────────────────────────────────────────────────
    public static final Preset MAXIMUM_FPS = build("maximum_fps",
            "Uncapped frames, cheapest correct frame, all safe CPU-side systems on. Visual quality drops where that buys real time.",
            s -> {
                s.put(ConfigSchema.RENDER_FRAME_CAP, 0);
                s.put(ConfigSchema.RENDER_VSYNC, "off");
                s.put(ConfigSchema.RENDER_VIDEO_OVERRIDES, true);
                s.put(ConfigSchema.VANILLA_MIPMAP, 3);
                s.put(ConfigSchema.VANILLA_BIOME_BLEND, 1);
                s.put(ConfigSchema.VANILLA_ENTITY_DISTANCE, 1.0);
                s.put(ConfigSchema.VANILLA_RENDER_DISTANCE, -1);
                s.put(ConfigSchema.VANILLA_SIMULATION_DISTANCE, -1);
                s.put(ConfigSchema.VANILLA_AMBIENT_OCCLUSION, "ignore");
                s.put(ConfigSchema.VANILLA_ENTITY_SHADOWS, "off");
                s.put(ConfigSchema.CULL_ENTITY, true);
                s.put(ConfigSchema.CULL_ENTITY_MODE, "frustum_occlusion");
                s.put(ConfigSchema.CULL_ENTITY_ASYNC, true);
                s.put(ConfigSchema.CULL_ENTITY_MAX_DISTANCE, 128);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY, true);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY_OCCLUSION, true);
                s.put(ConfigSchema.CULL_ITEM_FRAMES, true);
                s.put(ConfigSchema.PARTICLE_BUDGET, true);
                s.put(ConfigSchema.PARTICLE_MAX_PER_TICK, 900);
                s.put(ConfigSchema.PARTICLE_SCALE, 90);
                s.put(ConfigSchema.PARTICLE_FAR_LIMIT, 64);
                s.put(ConfigSchema.CHUNK_WORKER_LIMIT, 0);
                s.put(ConfigSchema.CHUNK_UPLOAD_BUDGET, 0);
                s.put(ConfigSchema.CHUNK_REBUILD_COALESCING, true);
                s.put(ConfigSchema.CHUNK_PRIORITY_BIAS, true);
                s.put(ConfigSchema.ANDROID_BIGLITTLE, true);
                s.put(ConfigSchema.ANDROID_THERMAL_GUARD, true);
                s.put(ConfigSchema.ANDROID_BATTERY_SAVER, false);
                s.put(ConfigSchema.ADAPTIVE_MODE, "off");
                s.put(ConfigSchema.DIAG_HUD, "basic");
                s.put(ConfigSchema.COMPAT_SAFE_MODE, false);
            });

    public static final Preset BALANCED = build("balanced",
            "High framerate, but your own video settings stay yours and the adaptive controller only advises.",
            s -> {
                s.put(ConfigSchema.RENDER_FRAME_CAP, 0);
                s.put(ConfigSchema.RENDER_VSYNC, "auto");
                s.put(ConfigSchema.RENDER_VIDEO_OVERRIDES, false);
                s.put(ConfigSchema.CULL_ENTITY, true);
                s.put(ConfigSchema.CULL_ENTITY_MODE, "frustum_occlusion");
                s.put(ConfigSchema.CULL_ENTITY_ASYNC, true);
                s.put(ConfigSchema.CULL_ENTITY_MAX_DISTANCE, 96);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY, true);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY_OCCLUSION, true);
                s.put(ConfigSchema.CULL_ITEM_FRAMES, true);
                s.put(ConfigSchema.PARTICLE_BUDGET, true);
                s.put(ConfigSchema.PARTICLE_MAX_PER_TICK, 512);
                s.put(ConfigSchema.PARTICLE_SCALE, 100);
                s.put(ConfigSchema.PARTICLE_FAR_LIMIT, 48);
                s.put(ConfigSchema.CHUNK_WORKER_LIMIT, 0);
                s.put(ConfigSchema.CHUNK_UPLOAD_BUDGET, 0);
                s.put(ConfigSchema.CHUNK_REBUILD_COALESCING, true);
                s.put(ConfigSchema.CHUNK_PRIORITY_BIAS, true);
                s.put(ConfigSchema.ANDROID_THERMAL_GUARD, true);
                s.put(ConfigSchema.ANDROID_BATTERY_SAVER, false);
                s.put(ConfigSchema.ADAPTIVE_MODE, "recommend");
                s.put(ConfigSchema.DIAG_HUD, "off");
                s.put(ConfigSchema.COMPAT_SAFE_MODE, false);
            });

    public static final Preset SMOOTH = build("smooth_gameplay",
            "Trades a little peak framerate for even frame times: capped uploads, coalesced rebuilds, adaptive frame time target.",
            s -> {
                s.put(ConfigSchema.RENDER_FRAME_CAP, 0);
                s.put(ConfigSchema.RENDER_VSYNC, "auto");
                s.put(ConfigSchema.RENDER_VIDEO_OVERRIDES, false);
                s.put(ConfigSchema.CULL_ENTITY, true);
                s.put(ConfigSchema.CULL_ENTITY_MODE, "frustum_occlusion");
                s.put(ConfigSchema.CULL_ENTITY_ASYNC, true);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY, true);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY_CACHE_TICKS, 12);
                s.put(ConfigSchema.PARTICLE_BUDGET, true);
                s.put(ConfigSchema.PARTICLE_MAX_PER_TICK, 400);
                s.put(ConfigSchema.PARTICLE_SCALE, 100);
                s.put(ConfigSchema.CHUNK_WORKER_LIMIT, 0);
                s.put(ConfigSchema.CHUNK_UPLOAD_BUDGET, 32);
                s.put(ConfigSchema.CHUNK_REBUILD_COALESCING, true);
                s.put(ConfigSchema.CHUNK_COALESCE_TICKS, 2);
                s.put(ConfigSchema.CHUNK_PRIORITY_BIAS, true);
                s.put(ConfigSchema.ADAPTIVE_MODE, "active");
                s.put(ConfigSchema.ADAPTIVE_TARGET_FPS, 60);
                s.put(ConfigSchema.ADAPTIVE_MIN_QUALITY, "medium");
                s.put(ConfigSchema.DIAG_HUD, "basic");
                s.put(ConfigSchema.COMPAT_SAFE_MODE, false);
            });

    public static final Preset BATTERY_COOLER = build("battery_cooler",
            "Sustained 60 frames with quiet background work: capped framerate, fewer workers, smaller visual ceilings.",
            s -> {
                s.put(ConfigSchema.RENDER_FRAME_CAP, 60);
                s.put(ConfigSchema.RENDER_VSYNC, "on");
                s.put(ConfigSchema.RENDER_IDLE_THROTTLE, true);
                s.put(ConfigSchema.RENDER_IDLE_FRAME_CAP, 10);
                s.put(ConfigSchema.RENDER_VIDEO_OVERRIDES, true);
                s.put(ConfigSchema.VANILLA_MIPMAP, 2);
                s.put(ConfigSchema.VANILLA_ENTITY_SHADOWS, "off");
                s.put(ConfigSchema.CULL_ENTITY, true);
                s.put(ConfigSchema.CULL_ENTITY_ASYNC, true);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY, true);
                s.put(ConfigSchema.PARTICLE_BUDGET, true);
                s.put(ConfigSchema.PARTICLE_MAX_PER_TICK, 220);
                s.put(ConfigSchema.PARTICLE_SCALE, 70);
                s.put(ConfigSchema.PARTICLE_FAR_LIMIT, 28);
                s.put(ConfigSchema.CULL_ENTITY_WORKERS, 1);
                s.put(ConfigSchema.CHUNK_WORKER_LIMIT, 2);
                s.put(ConfigSchema.CHUNK_UPLOAD_BUDGET, 16);
                s.put(ConfigSchema.ANDROID_BATTERY_SAVER, true);
                s.put(ConfigSchema.ANDROID_THERMAL_GUARD, true);
                s.put(ConfigSchema.ANDROID_BIGLITTLE, true);
                s.put(ConfigSchema.CPU_PAUSE_IDLE, true);
                s.put(ConfigSchema.ADAPTIVE_MODE, "active");
                s.put(ConfigSchema.ADAPTIVE_TARGET_FPS, 45);
                s.put(ConfigSchema.ADAPTIVE_MIN_QUALITY, "low");
                s.put(ConfigSchema.DIAG_HUD, "off");
                s.put(ConfigSchema.DIAG_ENABLED, true);
                s.put(ConfigSchema.COMPAT_SAFE_MODE, false);
            });

    public static final Preset SHADERS = build("shaders",
            "Assumes a shader pack owns the framebuffer and the lighting: BlockBoost keeps its CPU-side wins and stops touching anything visual.",
            s -> {
                s.put(ConfigSchema.RENDER_FRAME_CAP, 0);
                s.put(ConfigSchema.RENDER_VSYNC, "auto");
                s.put(ConfigSchema.RENDER_VIDEO_OVERRIDES, false);
                s.put(ConfigSchema.CULL_ENTITY, true);
                s.put(ConfigSchema.CULL_ENTITY_MODE, "frustum");
                s.put(ConfigSchema.CULL_ENTITY_KEEP_NAMED, true);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY, true);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY_OCCLUSION, false);
                s.put(ConfigSchema.CULL_ITEM_FRAMES, false);
                s.put(ConfigSchema.PARTICLE_BUDGET, true);
                s.put(ConfigSchema.PARTICLE_MAX_PER_TICK, 800);
                s.put(ConfigSchema.PARTICLE_SCALE, 100);
                s.put(ConfigSchema.PARTICLE_FAR_LIMIT, 0);
                s.put(ConfigSchema.PARTICLE_KEEP_GAMEPLAY, true);
                s.put(ConfigSchema.CHUNK_REBUILD_COALESCING, true);
                s.put(ConfigSchema.CHUNK_PRIORITY_BIAS, false);
                s.put(ConfigSchema.CHUNK_BACKLOG_GUARD, 0);
                s.put(ConfigSchema.CHUNK_UPLOAD_BUDGET, 0);
                s.put(ConfigSchema.ADAPTIVE_MODE, "off");
                s.put(ConfigSchema.ANDROID_THERMAL_GUARD, true);
                s.put(ConfigSchema.DIAG_HUD, "basic");
                s.put(ConfigSchema.COMPAT_SAFE_MODE, false);
            });

    public static final Preset COMPATIBILITY_SAFE = build("compatibility_safe",
            "Only the optimizations that cannot change what you see. Use this to find out whether BlockBoost is involved in a problem.",
            s -> {
                s.put(ConfigSchema.COMPAT_SAFE_MODE, true);
                s.put(ConfigSchema.CULL_ENTITY, false);
                s.put(ConfigSchema.CULL_BLOCK_ENTITY, false);
                s.put(ConfigSchema.CULL_ITEM_FRAMES, false);
                s.put(ConfigSchema.PARTICLE_BUDGET, false);
                s.put(ConfigSchema.RENDER_VIDEO_OVERRIDES, false);
                s.put(ConfigSchema.CHUNK_REBUILD_COALESCING, false);
                s.put(ConfigSchema.CHUNK_PRIORITY_BIAS, false);
                s.put(ConfigSchema.CHUNK_BACKLOG_GUARD, 0);
                s.put(ConfigSchema.CPU_STABLE_LOOKUP_CACHE, false);
                s.put(ConfigSchema.ADAPTIVE_MODE, "off");
                s.put(ConfigSchema.ANDROID_THERMAL_GUARD, false);
                s.put(ConfigSchema.DIAG_ENABLED, true);
                s.put(ConfigSchema.DIAG_HUD, "basic");
            });

    /** Marker preset: leaves every value the user chose. */
    public static final Preset CUSTOM = new Preset(CUSTOM_ID,
            "blockboost.preset.custom.name", "blockboost.preset.custom.tooltip", Map.of());

    public static final List<Preset> ALL = List.of(
            MAXIMUM_FPS, BALANCED, SMOOTH, BATTERY_COOLER, SHADERS, COMPATIBILITY_SAFE, CUSTOM);

    public static Preset byId(String id) {
        for (Preset preset : ALL) {
            if (preset.id().equals(id)) {
                return preset;
            }
        }
        return CUSTOM;
    }

    @Override
    public String toString() {
        return "Preset[" + id + ", " + patch.size() + " keys]";
    }

    // ── construction helper ─────────────────────────────────────────────────
    private interface Patcher {
        void accept(Map<SettingKey, Object> out);
    }

    private static Preset build(String id, String tooltip, Patcher patcher) {
        Map<SettingKey, Object> patch = p();
        patcher.accept(patch);
        // Sanity: every patched key must be sanitegable to the patched value.
        for (Map.Entry<SettingKey, Object> e : patch.entrySet()) {
            SettingKey key = e.getKey();
            Object value = e.getValue();
            Object clean = key.sanitize(value);
            boolean unchanged = switch (key.kind()) {
                // A preset may only write a value the schema accepts as-is. If sanitize
                // had to move it, the preset is claiming something the key forbids -
                // e.g. a tri-state string on a boolean, or a render distance of 999.
                case BOOLEAN -> clean instanceof Boolean b && b.equals(value);
                case ENUM -> clean instanceof String s && s.equals(value);
                case INTEGER -> clean instanceof Number a && value instanceof Number b
                        && a.intValue() == b.intValue();
                case DOUBLE -> clean instanceof Number a && value instanceof Number b
                        && Math.abs(a.doubleValue() - b.doubleValue()) < 1e-9;
            };
            if (!unchanged) {
                throw new IllegalStateException("preset " + id + " patches " + key.path()
                        + " with an out-of-range value " + value + " (allowed " + clean + ")");
            }
        }
        return new Preset(id, "blockboost.preset." + id + ".name",
                "blockboost.preset." + id + ".tooltip", java.util.Collections.unmodifiableMap(patch));
    }
}
