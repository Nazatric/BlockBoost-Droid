package dev.blockboost.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBooleanArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.blockboost.BlockBoost;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;

/**
 * Owns the on/off state of every subsystem.
 *
 * <p>Three properties are deliberate:
 * <ol>
 *   <li><b>Per-frame checks are one array read.</b> {@link #isActive} is called
 *       from render hooks, so it must not allocate, take a lock or hit a map.
 *       State lives in an {@link AtomicBooleanArray} indexed by enum ordinal.</li>
 *   <li><b>One failing subsystem never takes the game down.</b> Activation runs
 *       inside a try/catch that marks only that feature failed and keeps a
 *       reason; broad catch, narrow blast radius. {@code crashOnFailure} exists
 *       so developers can opt into the loud behaviour instead of us hiding bugs.</li>
 *   <li><b>Every decision has a recorded reason.</b> The UI, HUD and exported
 *       report all read from here, so "why is my culling not running" is
 *       answerable without a debugger.</li>
 * </ol>
 */
public final class FeatureRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(BlockBoost.MOD_ID);

    private static volatile FeatureRegistry instance;

    private final Map<FeatureId, Feature> features = new EnumMap<>(FeatureId.class);
    private final Map<FeatureId, FeatureStatus> status = new EnumMap<>(FeatureId.class);
    private final Map<FeatureId, String> reasons = new EnumMap<>(FeatureId.class);
    private final AtomicBooleanArray active = new AtomicBooleanArray(FeatureId.values().length);
    private final Map<FeatureId, Integer> failures = new EnumMap<>(FeatureId.class);
    /** Features the user switched off after a runtime error, until restart. */
    private final java.util.Set<FeatureId> quarantined = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile Settings settings;
    private volatile FeatureRuntimeContext context;

    public static void install(FeatureRegistry registry) {
        instance = registry;
    }

    public static FeatureRegistry instance() {
        return instance;
    }

    /** Static fast path for hot code; a missing registry means "not active". */
    public static boolean active(FeatureId id) {
        FeatureRegistry r = instance;
        return r != null && r.isActive(id);
    }

    public void register(Feature feature) {
        features.put(feature.id(), feature);
        status.put(feature.id(), FeatureStatus.OFF_BY_CONFIG);
        reasons.put(feature.id(), "not evaluated yet");
    }

    public void bind(FeatureRuntimeContext ctx, Settings settings) {
        this.context = ctx;
        this.settings = settings;
    }

    /** Re-evaluates every feature; called at init and after any config change. */
    public void refresh() {
        Settings cfg = this.settings;
        FeatureRuntimeContext ctx = this.context;
        if (cfg == null || ctx == null) {
            return;
        }
        for (Feature f : features.values()) {
            evaluate(f, cfg, ctx);
        }
    }

    public void refresh(FeatureId id) {
        Settings cfg = this.settings;
        FeatureRuntimeContext ctx = this.context;
        Feature f = features.get(id);
        if (cfg == null || ctx == null || f == null) {
            return;
        }
        evaluate(f, cfg, ctx);
    }

    private void evaluate(Feature f, Settings cfg, FeatureRuntimeContext ctx) {
        FeatureId id = f.id();
        boolean wantActive;
        FeatureStatus next;
        String reason;

        if (quarantined.contains(id)) {
            wantActive = false;
            next = FeatureStatus.DISABLED_AT_RUNTIME;
            reason = "disabled after a runtime error; restart BlockBoost to try again";
        } else if (cfg.bool(ConfigSchema.COMPAT_CRASH_ON_FAILURE)) {
            wantActive = f.enabledByConfig(cfg);
            next = wantActive ? FeatureStatus.ACTIVE : FeatureStatus.OFF_BY_CONFIG;
            reason = "crash-on-failure mode";
        } else {
            String refusal = safeRefusal(f, cfg, ctx);
            if (refusal != null) {
                wantActive = false;
                next = refusal.startsWith("suppressed:") ? FeatureStatus.SUPPRESSED_BY_MOD : FeatureStatus.UNSUPPORTED;
                reason = refusal;
            } else if (cfg.bool(ConfigSchema.COMPAT_SAFE_MODE)
                    && keyIsAggressive(id)) {
                wantActive = false;
                next = FeatureStatus.SAFE_MODE;
                reason = "compatibility safe mode is on";
            } else {
                wantActive = f.enabledByConfig(cfg);
                next = wantActive ? FeatureStatus.ACTIVE : FeatureStatus.OFF_BY_CONFIG;
                reason = wantActive ? "enabled" : "disabled in settings";
            }
        }

        boolean wasActive = active.get(id.ordinal());
        if (wantActive && !wasActive) {
            try {
                f.onActivate(ctx);
                active.set(id.ordinal(), true);
                status.put(id, next == FeatureStatus.ACTIVE ? FeatureStatus.ACTIVE : next);
                reasons.put(id, reason);
                LOG.info("[BlockBoost] enabled {} ({})", id.label(), id);
            } catch (Throwable t) {
                active.set(id.ordinal(), false);
                if (cfg.bool(ConfigSchema.COMPAT_ISOLATE_FAILURES)) {
                    quarantined.add(id);
                    next = FeatureStatus.DISABLED_AT_RUNTIME;
                } else {
                    next = FeatureStatus.FAILED;
                }
                status.put(id, next);
                reasons.put(id, "activation failed: " + describe(t));
                failures.merge(id, 1, Integer::sum);
                LOG.error("[BlockBoost] feature {} failed to initialize; it has been disabled"
                        + " ({}). Everything else keeps running.", id, describe(t), t);
                if (!cfg.bool(ConfigSchema.COMPAT_ISOLATE_FAILURES)) {
                    throw new IllegalStateException("BlockBoost feature " + id + " failed", t);
                }
            }
        } else if (!wantActive && wasActive) {
            active.set(id.ordinal(), false);
            try {
                f.onDeactivate(ctx);
            } catch (Throwable t) {
                LOG.warn("[BlockBoost] feature {} failed to shut down cleanly", id, t);
            }
            status.put(id, next);
            reasons.put(id, reason);
            LOG.info("[BlockBoost] disabled {} ({})", id.label(), id);
        } else if (!wantActive) {
            status.put(id, next);
            reasons.put(id, reason);
        }
    }

    private String safeRefusal(Feature f, Settings cfg, FeatureRuntimeContext ctx) {
        try {
            return f.refusalReason(cfg, ctx);
        } catch (Throwable t) {
            LOG.warn("[BlockBoost] capability check for {} threw", f.id(), t);
            return "capability check failed: " + describe(t);
        }
    }

    /** Settings whose risk level makes them ineligible for safe mode. */
    private boolean keyIsAggressive(FeatureId id) {
        return switch (id) {
            case ENTITY_CULLING, BLOCK_ENTITY_CULLING, ITEM_FRAME_CACHE, PARTICLE_BUDGET, CHUNK_PIPELINE,
                 VIDEO_OVERRIDES, ADAPTIVE ->
                    true;
            default -> false;
        };
    }

    public boolean isActive(FeatureId id) {
        return active.get(id.ordinal());
    }

    public FeatureStatus status(FeatureId id) {
        return status.getOrDefault(id, FeatureStatus.OFF_BY_CONFIG);
    }

    public String reason(FeatureId id) {
        return reasons.getOrDefault(id, "unknown");
    }

    public int failureCount() {
        int n = 0;
        for (int v : failures.values()) {
            n += v;
        }
        return n;
    }

    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        for (FeatureId id : FeatureId.values()) {
            if (!features.containsKey(id)) {
                continue;
            }
            String state = status(id).label;
            String detail = reason(id);
            out.add(String.format("%-34s %-24s %s", id.label(), state, detail == null ? "" : detail));
        }
        return out;
    }

    /** Live state per feature for the diagnostics screen. */
    public List<String> liveLines() {
        List<String> out = new ArrayList<>();
        FeatureRuntimeContext ctx = this.context;
        for (Feature f : features.values()) {
            String d;
            try {
                d = ctx == null ? "" : f.describeState(ctx);
            } catch (Throwable t) {
                d = "(state unavailable: " + describe(t) + ")";
            }
            if (d != null && !d.isBlank()) {
                out.add(f.id().name() + ": " + d);
            }
        }
        return out;
    }

    public void quarantine(FeatureId id, String why) {
        quarantined.add(id);
        active.set(id.ordinal(), false);
        status.put(id, FeatureStatus.DISABLED_AT_RUNTIME);
        reasons.put(id, why);
        LOG.warn("[BlockBoost] {} switched off at runtime: {}", id, why);
    }

    public boolean isQuarantined(FeatureId id) {
        return quarantined.contains(id);
    }

    public int registeredCount() {
        return features.size();
    }

    public static String describe(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
