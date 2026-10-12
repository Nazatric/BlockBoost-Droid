package dev.blockboost.client.adaptive;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;

import dev.blockboost.BlockBoost;
import dev.blockboost.client.ClientServices;
import dev.blockboost.client.features.VanillaOptions;
import dev.blockboost.client.services.DiagnosticsService;
import dev.blockboost.client.services.FrameSampler;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.SettingKey;
import dev.blockboost.config.Settings;
import dev.blockboost.device.DeviceCapabilities;

/**
 * The controller that turns measured frame time into a config change.
 *
 * <p>Three modes, because the right one depends on how much the user trusts it:
 * <ul>
 *   <li><b>off</b> — nothing is touched, nothing is suggested;</li>
 *   <li><b>recommend</b> — the HUD and the report say "3 of the last 5 seconds were
 *       over target; switching particles down would buy roughly X ms", and no key is
 *       written. This is the default on an untested renderer;</li>
 *   <li><b>active</b> — one step of the ladder is applied, and it can be taken back.
 *   </li>
 * </ul>
 *
 * <p>Why it is allowed to be this simple: an auto-tuner on an unknown GPU translator
 * is a loop with someone else's hardware in it. So the decisions are (1) hysteresis —
 * a sustained overrun, not one spike; (2) a cooldown after every change, so a change
 * is in place long enough to measure; (3) one step at a time, always reversible to
 * the snapshot taken when the controller first engaged; (4) an immediate stand-down
 * when the user edits anything, because a manual change means "I am driving".
 */
public final class AdaptiveController implements AutoCloseable {

    public enum Mode {
        OFF, RECOMMEND, ACTIVE;

        public static Mode parse(String v) {
            if (v == null) {
                return RECOMMEND;
            }
            return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off", "disabled" -> OFF;
                case "active", "auto", "on" -> ACTIVE;
                default -> RECOMMEND;
            };
        }
    }

    /** One step down the ladder: a patch of our own keys, which other features consume. */
    private record Step(String name, Map<SettingKey, Object> patch) {
    }

    private static final Step[] LADDER = {
            new Step("particles down", Map.of(
                    ConfigSchema.PARTICLE_MAX_PER_TICK, 256)),
            new Step("cheap entity draws", Map.of(
                    ConfigSchema.PARTICLE_MAX_PER_TICK, 128,
                    ConfigSchema.VANILLA_ENTITY_SHADOWS, "off")),
            new Step("smaller world", Map.of(
                    ConfigSchema.PARTICLE_MAX_PER_TICK, 128,
                    ConfigSchema.VANILLA_ENTITY_SHADOWS, "off",
                    ConfigSchema.VANILLA_BIOME_BLEND, 0,
                    ConfigSchema.VANILLA_MIPMAP, 1)),
            new Step("budget the queues", Map.of(
                    ConfigSchema.PARTICLE_MAX_PER_TICK, 96,
                    ConfigSchema.VANILLA_ENTITY_SHADOWS, "off",
                    ConfigSchema.VANILLA_BIOME_BLEND, 0,
                    ConfigSchema.VANILLA_MIPMAP, 1,
                    ConfigSchema.CULL_ENTITY_MAX_DISTANCE, 48,
                    ConfigSchema.CULL_ENTITY_REFRESH_TICKS, 12,
                    ConfigSchema.CHUNK_UPLOAD_BUDGET, 8)),
            new Step("hold the frame budget", Map.of(
                    ConfigSchema.PARTICLE_MAX_PER_TICK, 64,
                    ConfigSchema.VANILLA_ENTITY_SHADOWS, "off",
                    ConfigSchema.VANILLA_BIOME_BLEND, 0,
                    ConfigSchema.VANILLA_MIPMAP, 0,
                    ConfigSchema.CULL_ENTITY_MAX_DISTANCE, 32,
                    ConfigSchema.CULL_ENTITY_REFRESH_TICKS, 16,
                    ConfigSchema.CHUNK_UPLOAD_BUDGET, 4,
                    ConfigSchema.RENDER_IDLE_FRAME_CAP, 20)),
    };

    private final Settings settings;
    private final DeviceCapabilities device;
    private final ClientServices services;
    private final DiagnosticsService diagnostics;

    private final Map<SettingKey, Object> baseline = new LinkedHashMap<>();
    private long lastChangeMs;
    private long lastEvaluateMs;
    private int overruns;
    private int recoveries;
    private int level;
    private int appliedLevel;
    private long suppressedUntilMs;
    private String recommendation = "nothing to change";
    private boolean engaged;

    public AdaptiveController(Settings settings, DeviceCapabilities device, ClientServices services,
                              DiagnosticsService diagnostics) {
        this.settings = settings;
        this.device = device;
        this.services = services;
        this.diagnostics = diagnostics;
    }

    public Mode mode() {
        return Mode.parse(settings.enumValue(ConfigSchema.ADAPTIVE_MODE));
    }

    /**
     * Called from the client tick at a fixed cadence. Nothing here allocates per
     * frame: the expensive part (percentiles) is computed by the sampler, this only
     * reads it.
     */
    public void sampleTick() {
        Mode mode = mode();
        if (mode == Mode.OFF || services == null) {
            resetCounters();
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(1_000L, settings.integer(ConfigSchema.ADAPTIVE_COOLDOWN) * 1_000L);
        if (now - lastEvaluateMs < 1_000L) {
            return;
        }
        lastEvaluateMs = now;
        if (now < suppressedUntilMs) {
            return;
        }
        FrameSampler frames = services.frames();
        if (frames.framesMeasured() < 120) {
            return;
        }
        double targetMs = 1000.0 / Math.max(5, settings.integer(ConfigSchema.ADAPTIVE_TARGET_FPS));
        double mean = frames.meanMs();
        double p95 = frames.percentileMs(95);
        double sensitivity = Math.max(0.05, settings.dbl(ConfigSchema.ADAPTIVE_SENSITIVITY));
        boolean videoAllowed = settings.bool(ConfigSchema.ADAPTIVE_ALLOW_VIDEO);

        if (mean > targetMs * (1.0 + sensitivity) || p95 > targetMs * 2.0) {
            recoveries = 0;
            overruns++;
        } else if (mean < targetMs * (1.0 - sensitivity * 0.5)) {
            overruns = 0;
            recoveries++;
        } else {
            overruns = 0;
            recoveries = 0;
        }

        int worstAllowed = maxLevelFor(videoAllowed);
        if (overruns >= 3 && appliedLevel < worstAllowed && now - lastChangeMs > cooldown) {
            if (mode == Mode.ACTIVE) {
                stepDown(worstAllowed);
            } else {
                recommendation = "over target for " + overruns + " s: '" + LADDER[appliedLevel + 1].name()
                        + "' would help; enable adaptive mode 'active' to let BlockBoost apply it";
                level = appliedLevel + 1;
            }
            overruns = 0;
        } else if (recoveries >= 5 && appliedLevel > 0 && now - lastChangeMs > cooldown * 2) {
            if (mode == Mode.ACTIVE) {
                stepUp();
            } else {
                recommendation = "steady below target for " + recoveries + " s; nothing is being held back any more";
            }
            recoveries = 0;
        } else if (mode == Mode.RECOMMEND && overruns == 0 && recoveries == 0) {
            recommendation = "frames inside target (" + String.format(java.util.Locale.ROOT, "%.1f", mean)
                    + " ms mean vs " + String.format(java.util.Locale.ROOT, "%.1f", targetMs) + " ms target)";
            level = appliedLevel;
        }
        diagnostics.counter("adaptive.level", appliedLevel);
        diagnostics.counter("adaptive.overruns", overruns);
    }

    /**
     * The last rung touches vsync and the frame cap, which is a video-settings change
     * the user has to opt into, so without that permission the ladder stops short.
     */
    private int maxLevelFor(boolean videoAllowed) {
        return videoAllowed ? LADDER.length - 1 : Math.min(3, LADDER.length - 1);
    }

    private void stepDown(int worstAllowed) {
        int next = Math.min(worstAllowed, appliedLevel + 1);
        if (next == appliedLevel) {
            return;
        }
        if (!engaged) {
            rememberBaseline();
            engaged = true;
        }
        Map<SettingKey, Object> patch = LADDER[next].patch();
        if (settings.applyAll(patch) == 0) {
            return;
        }
        appliedLevel = next;
        level = next;
        lastChangeMs = System.currentTimeMillis();
        recommendation = "applied '" + LADDER[next].name() + "' (level " + (next + 1) + "/" + LADDER.length + ")";
        BlockBoost.logInfo("adaptive: " + recommendation);
        services.markAdaptiveApplied(next);
    }

    private void stepUp() {
        if (appliedLevel <= 0) {
            return;
        }
        appliedLevel--;
        if (appliedLevel == 0) {
            restoreBaseline();
        } else {
            settings.applyAll(LADDER[appliedLevel].patch());
        }
        lastChangeMs = System.currentTimeMillis();
        recommendation = "relaxed to level " + (appliedLevel + 1) + " ("
                + (appliedLevel == 0 ? "your settings" : LADDER[appliedLevel].name()) + ")";
        BlockBoost.logInfo("adaptive: " + recommendation);
        services.markAdaptiveApplied(appliedLevel);
    }

    private void rememberBaseline() {
        for (Step step : LADDER) {
            for (SettingKey key : step.patch().keySet()) {
                baseline.putIfAbsent(key, settings.raw(key));
            }
        }
    }

    /** The user's own values, back in full. Called when the controller stands down. */
    public void restoreBaseline() {
        if (baseline.isEmpty()) {
            return;
        }
        settings.applyAll(new LinkedHashMap<>(baseline));
        baseline.clear();
        appliedLevel = 0;
        level = 0;
        engaged = false;
    }

    /**
     * A manual edit is the user saying "I am driving". Hold off for two cooldowns so
     * the controller never fights a slider the user is still moving, and keep the
     * new values as the baseline.
     */
    public void noteUserChange() {
        long cooldown = Math.max(1_000L, settings.integer(ConfigSchema.ADAPTIVE_COOLDOWN) * 1_000L);
        suppressedUntilMs = System.currentTimeMillis() + cooldown * 2;
        overruns = 0;
        recoveries = 0;
        if (engaged) {
            // Whatever is on screen now is what the user wants; adopt it as baseline.
            rememberBaseline();
        }
    }

    private void resetCounters() {
        overruns = 0;
        recoveries = 0;
        if (recommendation == null || recommendation.isBlank()) {
            recommendation = "adaptive control off";
        }
    }

    public String recommendation() {
        return recommendation;
    }

    public int level() {
        return appliedLevel;
    }

    public int maxLevel() {
        return LADDER.length;
    }

    public boolean engaged() {
        return engaged;
    }

    public String hudLine() {
        Mode mode = mode();
        if (mode == Mode.OFF) {
            return "adaptive: off";
        }
        return "adaptive: " + (mode == Mode.ACTIVE ? "active" : "suggest") + " L" + (appliedLevel + 1) + "/"
                + LADDER.length + " - " + recommendation;
    }

    public String describe() {
        return "mode=" + mode() + " level=" + (appliedLevel + 1) + "/" + LADDER.length
                + " target=" + settings.integer(ConfigSchema.ADAPTIVE_TARGET_FPS) + " fps"
                + " cooldown=" + settings.integer(ConfigSchema.ADAPTIVE_COOLDOWN) + "s"
                + " sensitivity=" + String.format(java.util.Locale.ROOT, "%.2f",
                settings.dbl(ConfigSchema.ADAPTIVE_SENSITIVITY))
                + " videoAllowed=" + settings.bool(ConfigSchema.ADAPTIVE_ALLOW_VIDEO)
                + " device=" + (device == null ? "unknown" : device.deviceClass())
                + " | " + recommendation;
    }

    /** Applies a recommendation by hand (the settings screen offers this as a button). */
    public void applyRecommended(Minecraft client) {
        int next = Math.min(LADDER.length - 1, appliedLevel + 1);
        if (!engaged) {
            rememberBaseline();
            engaged = true;
        }
        settings.applyAll(LADDER[next].patch());
        appliedLevel = next;
        lastChangeMs = System.currentTimeMillis();
        VanillaOptions.persist(client);
        BlockBoost.logInfo("adaptive: applied '" + LADDER[next].name() + "' on request");
    }

    public void forceRestore(Minecraft client) {
        restoreBaseline();
        VanillaOptions.persist(client);
    }

    @Override
    public void close() {
        if (engaged) {
            restoreBaseline();
        }
    }
}
