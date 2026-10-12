package dev.blockboost.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.SettingKey;

/**
 * Turns what the runtime can actually observe into a starting configuration.
 *
 * <p>This is the "smart optimization" decision, and it is deliberately a pure function
 * over numbers rather than a pile of device lookups: {@link DeviceCapabilities} collects
 * the facts once (core count, heap ceiling, OS hints, the GL strings read on the first
 * frame), and everything below is what those facts imply. Being pure means the heuristics
 * are unit-testable, reviewable, and - more importantly for a mod that has to survive a
 * Minecraft update - not tangled into the game.
 *
 * <p>The rules, and why they are the way they are:
 *
 * <ul>
 *   <li><b>A GPU name is not a device.</b> "Mali-G720 MC7" says nothing about the CPU
 *       cluster it is attached to, the heap the launcher allowed, or whether the phone
 *       will still be boosting in ten minutes. So the GPU string only ever nudges the
 *       *renderer* handling (is this a translated GL stack), never the aggressiveness of
 *       quality or threading choices. Aggressiveness comes from cores and memory, which
 *       are what actually bound a frame budget on Android.</li>
 *   <li><b>Unknown means conservative, not average.</b> When the runtime exposes nothing
 *       reliable, the recommendation is the smallest change from vanilla that still buys
 *       something: culling on (it cannot change what is visible), no extra threads, no
 *       particle pressure, no quality edits.</li>
 *   <li><b>Never pick a cap the user did not ask for.</b> No recommendation here sets a
 *       frame-rate cap or turns vsync on, because those are exactly the settings that make
 *       a game feel wrong when a heuristic guesses badly. They stay untouched, and the
 *       user can ask for them.</li>
 *   <li><b>Applied once, explained always.</b> A fresh install gets this patch so the mod
 *       is useful without anyone reading a manual; re-applying it is an explicit action in
 *       the settings screen. Every key it touched is reported with the reason, so a
 *       surprising result is a readable one.</li>
 * </ul>
 */
public final class DeviceProfileAdvisor {

    /** What to write, and the sentences that explain each part of it. */
    public record Recommendation(Map<SettingKey, Object> patch, List<String> reasons, DeviceClass deviceClass,
                                String summary) {
        public boolean isEmpty() {
            return patch.isEmpty();
        }

        /** Human-readable, one line per change, for the settings screen and the log. */
        public List<String> describe() {
            List<String> out = new ArrayList<>();
            out.add(summary);
            out.addAll(reasons);
            return out;
        }
    }

    private DeviceProfileAdvisor() {
    }

    /**
     * @param cores        usable processors as the JVM reports them
     * @param maxHeapBytes the launcher's allowed heap, not free memory
     * @param android      whether this looks like an Android runtime
     * @param mobileArch   ARM/AArch64 or another mobile ABI
     * @param gpuVendor    GL vendor string, or null when the query failed
     * @param gpuRenderer  GL renderer string, or null
     * @param translatedGl true when the renderer is provided through a GL-to-GLES
     *                     translator (MobileGlues/GL4ES/LTW/Zink), which is a fact about
     *                     the *stack*, not about how fast the device is
     */
    public static Recommendation recommend(int cores, long maxHeapBytes, boolean android, boolean mobileArch,
                                           String gpuVendor, String gpuRenderer, boolean translatedGl) {
        long heapMb = Math.max(0L, maxHeapBytes / (1024L * 1024L));
        DeviceClass cls = classify(cores, heapMb, android || mobileArch);

        Map<SettingKey, Object> patch = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();

        // Threading: the one place where "more" is actively harmful on a big.LITTLE
        // phone. Small cores are slow, and a worker parked on one of them while the
        // render thread waits for its answer is worse than not asking.
        int workers = switch (cls) {
            case LOW -> 1;
            case MID -> 2;
            case HIGH -> Math.min(4, Math.max(2, cores / 3));
            case UNKNOWN -> 1;
        };
        patch.put(ConfigSchema.CULL_ENTITY_WORKERS, workers);
        reasons.add("visibility workers: " + workers + " (" + cores + " cores reported; "
                + "extra threads on a phone's small cores cost latency rather than save it)");

        // Async culling needs a worker to be *useful* and a snapshot to be honest about.
        // On one or two cores it is a round trip for nothing, so it stays off there.
        boolean async = cls == DeviceClass.HIGH || (cls == DeviceClass.MID && cores >= 8);
        patch.put(ConfigSchema.CULL_ENTITY_ASYNC, async);
        reasons.add("async visibility: " + (async ? "on" : "off")
                + (async ? " (enough cores to pay for the hand-off)" : " (too few cores to gain from it)"));

        // Culling itself: strictly CPU work removed, cannot change what is visible while
        // the occlusion test stays conservative, so it is on everywhere by default.
        patch.put(ConfigSchema.CULL_ENTITY, true);
        patch.put(ConfigSchema.CULL_ENTITY_MODE, cls == DeviceClass.LOW ? "frustum" : "frustum_occlusion");
        patch.put(ConfigSchema.CULL_BLOCK_ENTITY, true);
        patch.put(ConfigSchema.CULL_BLOCK_ENTITY_OCCLUSION, cls != DeviceClass.LOW);
        reasons.add(cls == DeviceClass.LOW
                ? "occlusion tests off: on the weakest class the probe cost can approach what it saves"
                : "occlusion tests on, with a per-frame probe budget so no scene can cost a frame");

        // Particles: a real per-frame ceiling, and never the tier that hides gameplay
        // information (damage, rain, critical hits).
        int perFrame = switch (cls) {
            case LOW -> 192;
            case MID -> 384;
            case HIGH -> 768;
            case UNKNOWN -> 256;
        };
        patch.put(ConfigSchema.PARTICLE_BUDGET, true);
        patch.put(ConfigSchema.PARTICLE_MAX_PER_TICK, perFrame);
        patch.put(ConfigSchema.PARTICLE_KEEP_GAMEPLAY, true);
        reasons.add("particle ceiling: " + perFrame + " new particles per frame, gameplay particles kept");

        // Memory hygiene and idle throttling are free wins on every class: they reduce
        // work while nothing is being looked at, and never touch what is drawn.
        patch.put(ConfigSchema.MEM_RELEASE_ON_EXIT, true);
        patch.put(ConfigSchema.CPU_PAUSE_IDLE, true);
        patch.put(ConfigSchema.RENDER_IDLE_THROTTLE, true);
        reasons.add("idle: background work pauses when the window is hidden or no world is open");

        // A translated stack gets no help from us about vsync or mipmaps: the translator
        // and the driver own pacing and filtering there, and guessing wrong is the kind of
        // change that makes a game feel broken.
        patch.put(ConfigSchema.RENDER_VSYNC, "auto");
        patch.put(ConfigSchema.RENDER_SOFT_PACING, false);
        reasons.add(translatedGl
                ? "GL translator detected (" + safe(gpuVendor) + " / " + safe(gpuRenderer)
                        + "): vsync and filtering left to you and the driver"
                : "vsync left alone; the frame cap is not touched automatically");

        // Adaptive control advises but does not act, on every class: the recommendation
        // has to be something a person can trust without reading the manual first.
        patch.put(ConfigSchema.ADAPTIVE_MODE, "recommend");
        reasons.add("adaptive controller: recommends, does not change anything by itself");

        String summary = "Auto profile: " + cls.id() + " (" + cores + " cores, " + heapMb + " MB heap ceiling"
                + (android ? ", Android" : "") + (translatedGl ? ", translated GL" : "") + ")";
        return new Recommendation(java.util.Collections.unmodifiableMap(patch), List.copyOf(reasons), cls, summary);
    }

    /**
     * Device class from what the JVM can actually see. Deliberately not a lookup table of
     * SoC names: those go stale, they miss most devices, and a mod that treats an unknown
     * chip as "average" is guessing at a thermal envelope it cannot observe.
     */
    public static DeviceClass classify(int cores, long heapMb, boolean mobile) {
        if (cores <= 0) {
            return DeviceClass.UNKNOWN;
        }
        if (cores <= 4 || heapMb <= 1280) {
            return DeviceClass.LOW;
        }
        // A phone with 8 cores and 6 GB of allowed heap is a modern flagship on paper. It
        // still does not get the desktop settings: sustained load on a fanless chassis is a
        // different problem than peak throughput, and HIGH here also means "we are willing
        // to spend worker threads", which is the part that hurts when the scheduler moves
        // the render thread onto a small core.
        boolean strong = cores >= 8 && heapMb >= 5120;
        if (strong && !mobile) {
            return DeviceClass.HIGH;
        }
        if (cores <= 6 && heapMb < 2048) {
            return DeviceClass.LOW;
        }
        return DeviceClass.MID;
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s.trim();
    }
}
