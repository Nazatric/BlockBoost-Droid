package dev.blockboost.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Version-to-version upgrade steps for the config file.
 *
 * <p>v3 is the first published format of BlockBoost; the steps below exist for
 * the renames that happened during development, and they are the mechanism every
 * future release must extend instead of breaking user configs. A step may only
 * rename, split or clamp values, never drop them: {@link ConfigStore} keeps
 * unknown keys verbatim so a downgrade is not destructive either.
 */
public final class ConfigMigrations {

    /** A single upgrade step. {@code from} must equal the loaded version. */
    record Step(int from, int to, String describe, Action action) {
        interface Action {
            void apply(JsonObject settings);
        }
    }

    public record Result(JsonObject json, List<String> notes, boolean appliedAny, boolean unsupported) {
    }

    private static final List<Step> STEPS = List.of(
            new Step(1, 2, "renamed the pre-release 'toggles.*' paths onto their final categories",
                    s -> {
                        rename(s, "toggles.entityCulling", ConfigSchema.CULL_ENTITY.path());
                        rename(s, "toggles.blockEntityCulling", ConfigSchema.CULL_BLOCK_ENTITY.path());
                        rename(s, "toggles.particleCap", ConfigSchema.PARTICLE_BUDGET.path());
                        rename(s, "toggles.fastchunks", ConfigSchema.CHUNK_STATS.path());
                    }),
            new Step(2, 3, "split the vsync boolean into a tri-state and moved the fps ceiling to render.framerate_cap",
                    s -> {
                        Boolean vsync = popBool(s, "toggles.vsync");
                        if (vsync != null) {
                            s.addProperty(ConfigSchema.RENDER_VSYNC.path(), vsync ? "on" : "off");
                        }
                        rename(s, "toggles.maxFps", ConfigSchema.RENDER_FRAME_CAP.path());
                        rename(s, "toggles.adaptive", ConfigSchema.ADAPTIVE_MODE.path(),
                                v -> v instanceof Boolean b && b ? "active" : "off");
                    })
    );

    private ConfigMigrations() {
    }

    public static Result run(JsonObject settings, int fromVersion, int toVersion) {
        List<String> notes = new ArrayList<>();
        boolean applied = false;
        boolean unsupported = false;
        JsonObject cur = settings.deepCopy();

        if (fromVersion > toVersion) {
            unsupported = true;
            notes.add("config was written by a newer BlockBoost (v" + fromVersion + "); values read as-is");
            return new Result(cur, notes, false, true);
        }

        int v = fromVersion;
        while (v < toVersion) {
            final int want = v;
            Step step = null;
            for (Step s : STEPS) {
                if (s.from() == want) {
                    step = s;
                    break;
                }
            }
            if (step == null) {
                unsupported = true;
                notes.add("no migration path from config v" + v + " to v" + toVersion + "; using validated defaults where keys are missing");
                break;
            }
            step.action().apply(cur);
            notes.add("applied migration v" + step.from() + "->" + step.to() + ": " + step.describe());
            applied = true;
            v = step.to();
        }
        return new Result(cur, notes, applied, unsupported);
    }

    // ── helpers (also handy for the next release's migrations) ──────────────
    static void rename(JsonObject o, String from, String to) {
        rename(o, from, to, v -> v);
    }

    static void rename(JsonObject o, String from, String to, java.util.function.UnaryOperator<JsonElement> map) {
        if (o.has(from)) {
            JsonElement v = map.apply(o.remove(from));
            if (v != null && !o.has(to)) {
                o.add(to, v);
            }
        }
    }

    static Boolean popBool(JsonObject o, String key) {
        if (o.has(key) && o.get(key).isJsonPrimitive()) {
            JsonElement el = o.remove(key);
            try {
                return el.getAsBoolean();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** Visible for tests: the number of upgrade steps currently registered. */
    public static int stepCount() {
        return STEPS.size();
    }

    static List<Map.Entry<String, JsonElement>> asEntries(JsonObject o) {
        List<Map.Entry<String, JsonElement>> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            out.add(Map.entry(e.getKey(), e.getValue()));
        }
        return out;
    }
}
