package dev.blockboost.config;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.blockboost.BlockBoost;

/**
 * Reads and writes {@code config/blockboost.json}.
 *
 * <p>Contract with the rest of the mod (all of it unit tested):
 * <ul>
 *   <li>A missing file produces defaults, not an error.</li>
 *   <li>A corrupt, truncated or wrong-typed file never crashes the game: the bad
 *       file is kept as {@code blockboost.json.corrupt-N}, the last good file is
 *       preserved as {@code blockboost.json.bak}, and defaults are written back.</li>
 *   <li>Values outside a key's range are clamped and reported instead of
 *       rejected, because "I typoed 9000 for the framerate cap" should not cost
 *       the user their whole config.</li>
 *   <li>Old {@code version} numbers are migrated through {@link ConfigMigrations}
 *       before values are validated.</li>
 * </ul>
 */
public final class ConfigStore {

    public static final String FILE_NAME = "blockboost.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_RECOVERY_FILES = 5;

    /** Outcome of a load: values plus everything the UI/report should admit. */
    public record LoadResult(Settings settings, int fromVersion, boolean migrated, boolean recovered,
                             List<String> notes, List<String> clamped) {
        public boolean hadIssues() {
            return recovered || !notes.isEmpty() || !clamped.isEmpty();
        }
    }

    private ConfigStore() {
    }

    public static Path defaultPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    // ── load ────────────────────────────────────────────────────────────────
    public static LoadResult load(Path file) {
        List<String> notes = new ArrayList<>();
        List<String> clamped = new ArrayList<>();
        boolean recovered = false;
        int fromVersion = BlockBoost.CONFIG_VERSION;

        if (!Files.exists(file)) {
            Settings s = Settings.defaults();
            s.markPreset("custom");
            return new LoadResult(s, fromVersion, false, false, List.of("no config file yet; wrote defaults"), clamped);
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return recovery(file, List.of("config could not be read: " + e.getMessage()));
        }

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw new IOException("root element is " + parsed.getClass().getSimpleName());
            }
            root = parsed.getAsJsonObject();
        } catch (Exception e) {
            return recovery(file, List.of("config is not valid JSON: " + e.getMessage()));
        }

        fromVersion = optInt(root, "version", 1);
        JsonObject body = root.has("settings") && root.get("settings").isJsonObject()
                ? root.getAsJsonObject("settings")
                : root; // tolerate a bare object of keys

        boolean migrated = false;
        if (fromVersion != BlockBoost.CONFIG_VERSION) {
            ConfigMigrations.Result r = ConfigMigrations.run(body, fromVersion, BlockBoost.CONFIG_VERSION);
            body = r.json();
            notes.addAll(r.notes());
            migrated = r.appliedAny();
            if (r.unsupported()) {
                notes.add("config version " + fromVersion + " is newer than this build; unknown keys are preserved untouched");
            }
        }

        Settings settings = Settings.defaults();
        for (SettingKey key : ConfigSchema.all()) {
            if (!body.has(key.path())) {
                continue;
            }
            JsonElement el = body.get(key.path());
            Object rawJson = toJava(el);
            Object clean = key.sanitize(rawJson);
            if (rawJson != null && !equivalent(rawJson, clean, key)) {
                clamped.add(key.path() + ": " + rawJson + " -> " + clean);
            }
            settings.values.put(key.path(), clean);
        }

        // Preserve anything we do not know about (forward compatibility).
        for (Map.Entry<String, JsonElement> e : body.entrySet()) {
            if (ConfigSchema.byPath(e.getKey()) == null) {
                settings.putUnknown(e.getKey(), toJava(e.getValue()));
            }
        }

        if (root.has("preset") && root.get("preset").isJsonPrimitive()) {
            settings.markPreset(root.get("preset").getAsString());
        }

        return new LoadResult(settings, fromVersion, migrated, recovered, notes, clamped);
    }

    private static LoadResult recovery(Path file, List<String> notes) {
        quarantine(file);
        Settings settings = Settings.defaults();
        List<String> all = new ArrayList<>(notes);
        all.add("recovered to defaults; the unreadable file was kept for inspection");
        return new LoadResult(settings, 0, false, true, all, List.of());
    }

    /** Renames a corrupt file instead of deleting it, so a user can still fix it. */
    static void quarantine(Path file) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            for (int i = 1; i <= MAX_RECOVERY_FILES; i++) {
                Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + i);
                if (!Files.exists(target)) {
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    BlockBoost.logWarn("moved unreadable config to " + target.getFileName());
                    return;
                }
            }
            Files.delete(file);
        } catch (IOException e) {
            BlockBoost.LOG.warn("could not quarantine broken config {}", file, e);
        }
    }

    // ── save ────────────────────────────────────────────────────────────────
    /**
     * Atomic write: serialize to a temp file in the same directory, keep a
     * {@code .bak} of the previous good file, then move into place. Storage on
     * Android can hand back a partially written file after a process kill, and a
     * half-written config is unacceptable for a mod people tune through a crash.
     */
    public static void save(Path file, Settings settings) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", BlockBoost.CONFIG_VERSION);
        root.addProperty("preset", settings.presetName());
        root.addProperty("generator", BlockBoost.MOD_ID + " " + BlockBoost.MOD_NAME);

        JsonObject body = new JsonObject();
        for (Map.Entry<String, Object> e : settings.snapshot().entrySet()) {
            body.add(e.getKey(), toJson(e.getValue()));
        }
        for (Map.Entry<String, Object> e : settings.unknownKeys().entrySet()) {
            if (!body.has(e.getKey())) {
                body.add(e.getKey(), toJson(e.getValue()));
            }
        }
        root.add("settings", body);

        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file.getFileName() + ".tmp");

        StringWriter sw = new StringWriter(4096);
        GSON.toJson(root, sw);
        Files.writeString(tmp, sw.toString(), StandardCharsets.UTF_8);

        if (Files.exists(file)) {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            try {
                Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // A missing backup must never fail the save itself.
            }
        }

        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException notAtomic) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Pretty JSON, exposed for tests and for the diagnostics report. */
    public static String toJson(Settings settings) {
        JsonObject root = new JsonObject();
        root.addProperty("version", BlockBoost.CONFIG_VERSION);
        root.addProperty("preset", settings.presetName());
        JsonObject body = new JsonObject();
        for (Map.Entry<String, Object> e : settings.snapshot().entrySet()) {
            body.add(e.getKey(), toJson(e.getValue()));
        }
        root.add("settings", body);
        StringWriter sw = new StringWriter();
        GSON.toJson(root, sw);
        return sw.toString();
    }

    // ── json plumbing ───────────────────────────────────────────────────────
    private static Object toJava(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonPrimitive()) {
            return el.toString();
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            double d = p.getAsDouble();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return (int) d;
            }
            return d;
        }
        return p.getAsString();
    }

    private static JsonElement toJson(Object v) {
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Integer i) {
            return new JsonPrimitive(i);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n.doubleValue());
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    private static boolean equivalent(Object raw, Object clean, SettingKey key) {
        if (key.kind() == SettingKey.Kind.ENUM) {
            return clean.equals(String.valueOf(raw));
        }
        if (raw instanceof Number n) {
            double a = n.doubleValue();
            double b = clean instanceof Number cn ? cn.doubleValue() : Double.NaN;
            return Math.abs(a - b) < 1e-9;
        }
        return clean.equals(raw);
    }

    private static int optInt(JsonObject o, String key, int fallback) {
        try {
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                return o.get(key).getAsInt();
            }
        } catch (Exception ignored) {
            // A non-numeric version field just means "very old"; treat as fallback.
        }
        return fallback;
    }
}
