package dev.blockboost.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.blockboost.config.SettingKey.Category.ADVANCED;
import static dev.blockboost.config.SettingKey.Category.ANDROID;
import static dev.blockboost.config.SettingKey.Category.ADAPTIVE;
import static dev.blockboost.config.SettingKey.Category.CHUNKS;
import static dev.blockboost.config.SettingKey.Category.COMPAT;
import static dev.blockboost.config.SettingKey.Category.CPU;
import static dev.blockboost.config.SettingKey.Category.CULLING;
import static dev.blockboost.config.SettingKey.Category.DIAGNOSTICS;
import static dev.blockboost.config.SettingKey.Category.MEMORY;
import static dev.blockboost.config.SettingKey.Category.PARTICLES;
import static dev.blockboost.config.SettingKey.Category.RENDERING;
import static dev.blockboost.config.SettingKey.Risk.AGGRESSIVE;
import static dev.blockboost.config.SettingKey.Risk.EXPERIMENTAL;
import static dev.blockboost.config.SettingKey.Risk.SAFE;

/**
 * The single source of truth for every BlockBoost setting.
 *
 * <p>Each key here must be read by real code (a service, a mixin, the UI or the
 * adaptive controller). Nothing is allowed in this file that has no effect, and
 * the {@code ConfigSchemaTest} unit test enforces that every key is referenced
 * from production sources exactly the way the settings screen expects.
 */
public final class ConfigSchema {

    private static final Map<String, SettingKey> KEYS = new LinkedHashMap<>();

    // ── A. Rendering & frame submission ─────────────────────────────────────
    public static final SettingKey RENDER_FRAME_CAP = SettingKey.intRange(
            "render.framerate_cap", 0, 0, 1000, RENDERING, SAFE, false,
            "Hard cap on frames per second. 0 leaves the game uncapped. This is the cheapest way to stop a phone from cooking itself while it waits on the render queue.");
    public static final SettingKey RENDER_VSYNC = SettingKey.enumOf(
            "render.vsync", "auto", List.of("auto", "on", "off"), RENDERING, SAFE, false,
            "Overrides the vertical sync state. On translated GL stacks the swap interval is the real frame limiter, so getting this wrong is why a phone sits at exactly 60.");
    public static final SettingKey RENDER_SMOOTH_PACING = SettingKey.bool(
            "render.smooth_pacing", true, RENDERING, SAFE, false,
            "Aims the framerate cap at a hair above the ideal interval so a frame that misses the deadline by microseconds does not stall a whole extra refresh.");
    public static final SettingKey RENDER_IDLE_THROTTLE = SettingKey.bool(
            "render.idle_throttle", true, RENDERING, SAFE, false,
            "While no world is open, or the window has no focus, the cap drops to the idle value. Big battery win, zero gameplay effect.");
    public static final SettingKey RENDER_IDLE_FRAME_CAP = SettingKey.intRange(
            "render.idle_framerate_cap", 20, 5, 240, RENDERING, SAFE, false,
            "The reduced cap used while idle or unfocused.");
    public static final SettingKey RENDER_VIDEO_OVERRIDES = SettingKey.bool(
            "render.video_overrides", false, RENDERING, AGGRESSIVE, false,
            "Lets BlockBoost apply the vanilla video options below. When off, BlockBoost never touches your own video settings.");
    public static final SettingKey VANILLA_MIPMAP = SettingKey.intRange(
            "render.vanilla_mipmap_cap", -1, -1, 4, RENDERING, SAFE, false,
            "Caps the vanilla mipmap level (-1 leaves it alone). Lower mipmaps cut texture bandwidth on mobile GPUs.");
    public static final SettingKey VANILLA_BIOME_BLEND = SettingKey.intRange(
            "render.vanilla_biome_blend", -1, -1, 4, RENDERING, SAFE, false,
            "Forces the vanilla biome blend radius (-1 leaves it alone). Smaller values cost less per baked chunk section.");
    public static final SettingKey VANILLA_ENTITY_DISTANCE = SettingKey.dblRange(
            "render.vanilla_entity_distance", -1, -1, 2.0, RENDERING, SAFE, false,
            "Overrides the vanilla entity render distance multiplier (-1 leaves it alone). This is the single biggest lever in an entity farm.");
    public static final SettingKey VANILLA_RENDER_DISTANCE = SettingKey.intRange(
            "render.vanilla_render_distance", -1, -1, 32, RENDERING, SAFE, false,
            "Overrides client render distance in chunks (-1 leaves it alone). Fewer chunks means less mesh work and less VRAM.");
    public static final SettingKey VANILLA_SIMULATION_DISTANCE = SettingKey.intRange(
            "render.vanilla_simulation_distance", -1, -1, 32, RENDERING, SAFE, false,
            "Overrides simulation distance for single player / integrated servers (-1 leaves it alone). Does not touch a remote server's settings.");
    public static final SettingKey VANILLA_AMBIENT_OCCLUSION = SettingKey.enumOf(
            "render.vanilla_ambient_occlusion", "ignore", List.of("ignore", "on", "off"), RENDERING, SAFE, false,
            "Overrides smooth lighting. Off is faster to mesh and upload; it also changes how the world looks, so it is opt-in.");
    public static final SettingKey VANILLA_ENTITY_SHADOWS = SettingKey.enumOf(
            "render.vanilla_entity_shadows", "ignore", List.of("ignore", "on", "off"), RENDERING, SAFE, false,
            "Overrides entity shadows. Turning them off removes a full extra entity pass in busy scenes.");

    // ── B/C. Visibility: entities, block entities ───────────────────────────
    public static final SettingKey CULL_ENTITY = SettingKey.bool(
            "culling.entity_culling", true, CULLING, AGGRESSIVE, false,
            "Skips the render-state extraction of entities that cannot be seen. Saves the CPU work that a phone pays for every mob in range.");
    public static final SettingKey CULL_ENTITY_MODE = SettingKey.enumOf(
            "culling.entity_mode", "frustum_occlusion", List.of("frustum", "frustum_occlusion"), CULLING, AGGRESSIVE, false,
            "Frustum only is strictly safe. With occlusion, entities fully buried behind solid blocks are also skipped; anything a ray test cannot decide stays visible.");
    public static final SettingKey CULL_ENTITY_ASYNC = SettingKey.bool(
            "culling.entity_async", true, CULLING, AGGRESSIVE, false,
            "Runs occlusion ray tests on a small worker pool and uses the last completed result, so a big farm cannot spike the client thread.");
    public static final SettingKey CULL_ENTITY_WORKERS = SettingKey.intRange(
            "culling.entity_workers", 0, 0, 4, CULLING, SAFE, false,
            "Worker threads for visibility tests. 0 lets BlockBoost pick from the core count and the Android profile.");
    public static final SettingKey CULL_ENTITY_MAX_DISTANCE = SettingKey.intRange(
            "culling.entity_max_distance", 96, 0, 512, CULLING, SAFE, false,
            "Only entities closer than this are considered (0 = every entity). Keeps the ray budget on the stuff that actually matters.");
    public static final SettingKey CULL_ENTITY_REFRESH_TICKS = SettingKey.intRange(
            "culling.entity_refresh_ticks", 5, 1, 100, CULLING, SAFE, false,
            "How often a cached visibility result may be re-tested while the camera is standing still.");
    public static final SettingKey CULL_ENTITY_KEEP_NAMED = SettingKey.bool(
            "culling.entity_keep_named", true, CULLING, SAFE, false,
            "Never hides named/boss/vehicle entities. Interaction and nametags always keep working.");
    public static final SettingKey CULL_ENTITY_KEEP_NAMETAGS = SettingKey.bool(
            "culling.entity_keep_nametags", true, CULLING, SAFE, false,
            "When an entity is hidden, still draw its name tag. Costs one cheap draw call and stops named "
                    + "pets, armor stands and markers from looking like they vanished.");
    public static final SettingKey CULL_ENTITY_KEEP_PLAYERS = SettingKey.bool(
            "culling.entity_keep_players", true, CULLING, SAFE, false,
            "Excludes other players from culling entirely. Recommended for multiplayer.");
    public static final SettingKey CULL_ENTITY_DEBUG = SettingKey.bool(
            "culling.entity_debug_overlay", false, CULLING, SAFE, false,
            "Draws a box around entities this frame and colors it by decision, so you can see exactly why something was hidden.");
    public static final SettingKey CULL_BLOCK_ENTITY = SettingKey.bool(
            "culling.block_entity_culling", true, CULLING, AGGRESSIVE, false,
            "Per-block-entity frustum and occlusion test. Chest and sign halls are where phones lose the most time.");
    public static final SettingKey CULL_BLOCK_ENTITY_OCCLUSION = SettingKey.bool(
            "culling.block_entity_occlusion", true, CULLING, AGGRESSIVE, false,
            "Also hides block entities fully enclosed by opaque blocks. Conservative: a single unknown block keeps the renderer running.");
    public static final SettingKey CULL_BLOCK_ENTITY_CACHE_TICKS = SettingKey.intRange(
            "culling.block_entity_cache_ticks", 8, 1, 100, CULLING, SAFE, false,
            "How many ticks a block entity visibility result is reused before it is re-tested. Low values cost more CPU, high values delay updates.");
    public static final SettingKey CULL_CACHE_ENTRIES = SettingKey.intRange(
            "culling.cache_entries", 16384, 256, 1048576, CULLING, SAFE, false,
            "Hard capacity of the visibility caches. Bounded by design; the entry eviction policy is documented and unit tested.");
    public static final SettingKey CULL_ITEM_FRAMES = SettingKey.bool(
            "culling.fast_item_frames", true, CULLING, AGGRESSIVE, false,
            "Treats item frames and glowing item frames as cached static geometry so a wall of them is not re-tested per frame.");

    // ── D. Chunk engine ─────────────────────────────────────────────────────
    public static final SettingKey CHUNK_WORKER_LIMIT = SettingKey.intRange(
            "chunks.worker_limit", 0, 0, 32, CHUNKS, SAFE, false,
            "Caps the number of mesh building threads. 0 = automatic. Phones have big.LITTLE cores; the extra threads mostly steal power from the ones that matter.");
    public static final SettingKey CHUNK_UPLOAD_BUDGET = SettingKey.intRange(
            "chunks.upload_budget", 0, 0, 512, CHUNKS, SAFE, false,
            "Maximum chunk sections uploaded to the GPU per frame (0 = unlimited). Spreads stalls from a teleport over a few frames instead of one huge hitch.");
    public static final SettingKey CHUNK_REBUILD_COALESCING = SettingKey.bool(
            "chunks.rebuild_coalescing", true, CHUNKS, AGGRESSIVE, false,
            "Collapses repeated dirty requests for the same section within a short window. A redstone clock that dirties the same section 20 times a second only needs one rebuild.");
    public static final SettingKey CHUNK_COALESCE_TICKS = SettingKey.intRange(
            "chunks.coalesce_ticks", 1, 0, 20, CHUNKS, AGGRESSIVE, false,
            "The window in which repeat rebuild requests are folded into the pending one.");
    public static final SettingKey CHUNK_PRIORITY_BIAS = SettingKey.bool(
            "chunks.priority_bias", true, CHUNKS, AGGRESSIVE, false,
            "Prefers sections near and in front of the camera when the queue backs up, so what you are looking at appears first.");
    public static final SettingKey CHUNK_BACKLOG_GUARD = SettingKey.intRange(
            "chunks.backlog_guard", 0, 0, 4096, CHUNKS, EXPERIMENTAL, false,
            "When more sections than this are queued, far away dirty requests are deferred until the queue drains (0 = off).");
    public static final SettingKey CHUNK_STATS = SettingKey.bool(
            "chunks.statistics", true, CHUNKS, SAFE, false,
            "Collects queue depth and build latency for the diagnostics report and HUD. Costs a counter increment per section.");

    // ── E. CPU / background work ────────────────────────────────────────────
    public static final SettingKey CPU_QUEUE_LIMIT = SettingKey.intRange(
            "cpu.task_queue_limit", 256, 8, 65536, CPU, SAFE, false,
            "Hard bound on BlockBoost's own background queues. When full, the oldest pending job is dropped rather than growing memory forever.");
    public static final SettingKey CPU_PAUSE_IDLE = SettingKey.bool(
            "cpu.pause_when_idle", true, CPU, SAFE, false,
            "Suspends BlockBoost workers while you are in a menu or the game is unfocused.");
    public static final SettingKey CPU_SCRATCH_REUSE = SettingKey.bool(
            "cpu.scratch_reuse", true, CPU, SAFE, false,
            "Reuses per-thread scratch arrays in the ray tracer and geometry code instead of allocating fresh ones each frame.");
    public static final SettingKey CPU_STABLE_LOOKUP_CACHE = SettingKey.bool(
            "cpu.stable_lookup_cache", true, CPU, AGGRESSIVE, false,
            "Caches results of stable per-block-state queries used by visibility code, invalidated on resource reload and world change.");

    // ── F. Memory ───────────────────────────────────────────────────────────
    public static final SettingKey MEM_RELEASE_ON_EXIT = SettingKey.bool(
            "memory.release_on_world_exit", true, MEMORY, SAFE, false,
            "Drops caches and pools references when you leave a world, so repeated world entry does not walk the heap upwards.");
    public static final SettingKey MEM_GC_LOG_MS = SettingKey.intRange(
            "memory.gc_pause_log_ms", 50, 0, 2000, MEMORY, SAFE, false,
            "Reports garbage collections that stop the world longer than this. 0 disables reporting; it never triggers a collection itself.");
    public static final SettingKey MEM_SCRATCH_CAP_KB = SettingKey.intRange(
            "memory.scratch_cap_kb", 1024, 64, 65536, MEMORY, SAFE, false,
            "Ceiling on the reused scratch buffers. The pools cannot grow past this, so reuse cannot become a leak.");
    public static final SettingKey MEM_HEAP_REPORT = SettingKey.bool(
            "memory.heap_report", true, MEMORY, SAFE, false,
            "Includes heap usage and GC pause history in the diagnostics report.");

    // ── G. Particles ────────────────────────────────────────────────────────
    public static final SettingKey PARTICLE_BUDGET = SettingKey.bool(
            "particles.budget", true, PARTICLES, AGGRESSIVE, false,
            "Limits how many particles may be created per client tick. A rain storm or a cave full of drip particles otherwise dominates a mobile frame budget.");
    public static final SettingKey PARTICLE_MAX_PER_TICK = SettingKey.intRange(
            "particles.max_per_tick", 512, 0, 20000, PARTICLES, AGGRESSIVE, false,
            "The per tick ceiling (0 = unlimited).");
    public static final SettingKey PARTICLE_SCALE = SettingKey.intRange(
            "particles.scale_percent", 100, 10, 100, PARTICLES, AGGRESSIVE, false,
            "Percentage of particles kept when the ceiling is exceeded. This is a visual trade-off and is never hidden: the HUD shows the real kept/created numbers.");
    public static final SettingKey PARTICLE_FAR_LIMIT = SettingKey.intRange(
            "particles.far_distance", 48, 0, 256, PARTICLES, AGGRESSIVE, false,
            "Cosmetic particles beyond this distance are skipped (0 = off). Gameplay feedback types are exempt.");
    public static final SettingKey PARTICLE_KEEP_GAMEPLAY = SettingKey.bool(
            "particles.keep_gameplay_types", true, PARTICLES, SAFE, false,
            "Never drops particles that carry information: damage crits, enchanted hit, breaking progress, portal, explosion, ender, potion effects.");
    public static final SettingKey PARTICLE_GROUP_AWARE = SettingKey.bool(
            "particles.group_aware_batching", true, PARTICLES, AGGRESSIVE, false,
            "Balances the budget across particle groups so one spawner cannot starve every other effect out of the frame.");

    // ── I. Android / device profile ─────────────────────────────────────────
    public static final SettingKey ANDROID_RENDERER = SettingKey.enumOf(
            "android.renderer_profile", "auto",
            List.of("auto", "mobileglues", "gl4es", "ltw", "zink", "desktop_gl", "unknown"), ANDROID, SAFE, false,
            "Which GL translation path is in use. Auto reads the renderer string when a GL context is reachable; set it manually when the launcher hides it.");
    public static final SettingKey ANDROID_DEVICE_CLASS = SettingKey.enumOf(
            "android.device_class", "auto", List.of("auto", "low", "mid", "high", "unknown"), ANDROID, SAFE, false,
            "Scales worker counts, queue bounds and cache sizes. Unknown devices get the conservative behaviour on purpose.");
    public static final SettingKey ANDROID_BIGLITTLE = SettingKey.bool(
            "android.biglittle_workers", true, ANDROID, SAFE, false,
            "Assumes performance and efficiency cores and keeps background work on roughly half the reported cores.");
    public static final SettingKey ANDROID_BATTERY_SAVER = SettingKey.bool(
            "android.battery_saver", false, ANDROID, SAFE, false,
            "Caps the framerate, disables the adaptive controller's appetite for work and keeps optional diagnostics light. Best sustained thermals, lowest charge drain.");
    public static final SettingKey ANDROID_THERMAL_GUARD = SettingKey.bool(
            "android.thermal_guard", true, ANDROID, AGGRESSIVE, false,
            "When frame times keep degrading with the scene unchanged, shed background workers and lower the ceiling instead of melting the phone.");

    // ── K. Compatibility ────────────────────────────────────────────────────
    public static final SettingKey COMPAT_AUTO_SUPPRESS = SettingKey.bool(
            "compat.auto_suppress", true, COMPAT, SAFE, false,
            "Disables a BlockBoost feature when an installed mod already does that job better, instead of running two systems over the same data.");
    public static final SettingKey COMPAT_SODIUM = SettingKey.bool(
            "compat.sodium_coop", true, COMPAT, SAFE, false,
            "When Sodium is present, BlockBoost keeps its hands off the chunk pipeline it owns and only adds its CPU-side systems.");
    public static final SettingKey COMPAT_SAFE_MODE = SettingKey.bool(
            "compat.safe_mode", false, COMPAT, SAFE, true,
            "Startup guard: every unverified or visual-changing optimization stays off. Use this when something renders wrong and you need to know whether BlockBoost is the cause.");
    public static final SettingKey COMPAT_ISOLATE_FAILURES = SettingKey.bool(
            "compat.isolate_failures", true, COMPAT, SAFE, false,
            "If a subsystem throws at startup, switch that one off with a logged reason and keep the rest of the mod running.");
    public static final SettingKey COMPAT_CRASH_ON_FAILURE = SettingKey.bool(
            "compat.crash_on_failure", false, COMPAT, EXPERIMENTAL, false,
            "For developers: turn subsystem failures into a crash so nothing is silently disabled.");

    // ── Advanced ────────────────────────────────────────────────────────────
    public static final SettingKey ADV_AUTOSAVE_MS = SettingKey.intRange(
            "advanced.autosave_debounce_ms", 1200, 200, 30000, ADVANCED, SAFE, false,
            "How long a change waits before the config file is written, so dragging a slider does not hammer storage.");
    public static final SettingKey ADV_LOG_STARTUP = SettingKey.bool(
            "advanced.log_startup_summary", true, ADVANCED, SAFE, false,
            "Logs the capability, compatibility and feature summary once at startup.");
    public static final SettingKey ADV_DIAG_DETAILED = SettingKey.bool(
            "advanced.detailed_feature_reasons", false, ADVANCED, SAFE, false,
            "Records a per-frame reason string for every visibility decision. Off by default: it allocates.");

    // ── Diagnostics & benchmarks ────────────────────────────────────────────
    public static final SettingKey DIAG_ENABLED = SettingKey.bool(
            "diagnostics.enabled", true, DIAGNOSTICS, SAFE, false,
            "Frame time, GC and queue tracking. A ring buffer per counter; measured cost is well under a microsecond per frame.");
    public static final SettingKey DIAG_HUD = SettingKey.enumOf(
            "diagnostics.hud", "off", List.of("off", "basic", "advanced"), DIAGNOSTICS, SAFE, false,
            "Compact on-screen overlay. Basic is fps/frame time/1% lows; advanced adds GC, chunks, culling and adaptive state.");
    public static final SettingKey DIAG_HUD_CORNER = SettingKey.enumOf(
            "diagnostics.hud_corner", "top_left",
            List.of("top_left", "top_right", "bottom_left", "bottom_right"), DIAGNOSTICS, SAFE, false,
            "Where the overlay sits. Pick a corner the launcher's touch controls do not cover.");
    public static final SettingKey DIAG_HUD_SCALE = SettingKey.intRange(
            "diagnostics.hud_scale_percent", 100, 50, 300, DIAGNOSTICS, SAFE, false,
            "Overlay text scale, for phones that run a huge GUI scale.");
    public static final SettingKey DIAG_WINDOW = SettingKey.intRange(
            "diagnostics.sample_window_frames", 240, 30, 3600, DIAGNOSTICS, SAFE, false,
            "How many frames the percentile math looks at.");
    public static final SettingKey DIAG_EXPORT_CSV = SettingKey.bool(
            "diagnostics.export_csv", true, DIAGNOSTICS, SAFE, false,
            "Writes benchmark samples as CSV next to the report so results are machine-checkable, not just a screenshot.");
    public static final SettingKey BENCH_SAMPLES = SettingKey.intRange(
            "diagnostics.bench_samples", 600, 30, 6000, DIAGNOSTICS, SAFE, false,
            "Frames measured per benchmark step.");
    public static final SettingKey BENCH_WARMUP = SettingKey.intRange(
            "diagnostics.bench_warmup_ticks", 100, 0, 1200, DIAGNOSTICS, SAFE, false,
            "Discarded warm-up, so JIT and chunk load noise stay out of the numbers.");
    public static final SettingKey BENCH_SCENARIO = SettingKey.enumOf(
            "diagnostics.bench_scenario", "idle", List.of("idle", "entity", "churn", "walk"), DIAGNOSTICS, SAFE, false,
            "idle: stand still and render. entity: spawn a mob farm load. churn: repeatedly dirty the nearby sections. walk: force chunk loading.");
    public static final SettingKey BENCH_TOGGLE_FEATURES = SettingKey.bool(
            "diagnostics.bench_compare_features", true, DIAGNOSTICS, SAFE, false,
            "Runs each scenario twice, once with the BlockBoost optimization groups off and once on, and reports the difference instead of a single number.");

    // ── Adaptive controller ─────────────────────────────────────────────────
    public static final SettingKey ADAPTIVE_MODE = SettingKey.enumOf(
            "adaptive.mode", "off", List.of("off", "recommend", "active"), ADAPTIVE, AGGRESSIVE, false,
            "off: never touch anything. recommend: show a suggested change in the UI only. active: apply it, log it and keep it reversible.");
    public static final SettingKey ADAPTIVE_TARGET_FPS = SettingKey.intRange(
            "adaptive.target_fps", 60, 10, 400, ADAPTIVE, SAFE, false,
            "The frame time the controller aims for.");
    public static final SettingKey ADAPTIVE_MIN_QUALITY = SettingKey.enumOf(
            "adaptive.min_quality", "low", List.of("minimal", "low", "medium", "high"), ADAPTIVE, SAFE, false,
            "The floor: the controller will never reduce visual quality past this point.");
    public static final SettingKey ADAPTIVE_COOLDOWN = SettingKey.intRange(
            "adaptive.cooldown_ticks", 200, 20, 2400, ADAPTIVE, SAFE, false,
            "Minimum ticks between automatic changes. This is the anti-oscillation setting.");
    public static final SettingKey ADAPTIVE_SENSITIVITY = SettingKey.intRange(
            "adaptive.sensitivity_percent", 100, 50, 300, ADAPTIVE, SAFE, false,
            "How far measured frame time must drift from the target before a step is taken. Higher is calmer.");
    public static final SettingKey ADAPTIVE_ALLOW_VIDEO = SettingKey.bool(
            "adaptive.allow_video_changes", false, ADAPTIVE, EXPERIMENTAL, false,
            "Allows the adaptive controller to change vanilla render/simulation distance. Off by default because it changes what you see.");

    private static final List<SettingKey> ALL;

    static {
        List<SettingKey> keys = new ArrayList<>();
        for (java.lang.reflect.Field f : ConfigSchema.class.getFields()) {
            if (f.getType() == SettingKey.class) {
                try {
                    keys.add((SettingKey) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("unreadable setting " + f.getName(), e);
                }
            }
        }
        Map<String, SettingKey> byPath = new LinkedHashMap<>();
        for (SettingKey k : keys) {
            if (byPath.putIfAbsent(k.path(), k) != null) {
                throw new IllegalStateException("duplicate setting path: " + k.path());
            }
        }
        KEYS.putAll(byPath);
        ALL = Collections.unmodifiableList(new ArrayList<>(byPath.values()));
    }

    private ConfigSchema() {
    }

    public static List<SettingKey> all() {
        return ALL;
    }

    public static SettingKey byPath(String path) {
        return KEYS.get(path);
    }

    public static List<SettingKey> byCategory(SettingKey.Category category) {
        List<SettingKey> out = new ArrayList<>();
        for (SettingKey k : ALL) {
            if (k.category() == category) {
                out.add(k);
            }
        }
        return out;
    }

    public static int count() {
        return ALL.size();
    }
}
