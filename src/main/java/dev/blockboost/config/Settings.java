package dev.blockboost.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live configuration values.
 *
 * <p>Design notes for a performance mod:
 * <ul>
 *   <li>Reads are lock free ({@link ConcurrentHashMap}) because the renderer,
 *       worker pools and the UI all read settings; only the UI writes.</li>
 *   <li>Writes always go through {@link SettingKey#sanitize(Object)}, so a value
 *       can never leave the validated range no matter whether it came from disk,
 *       a preset, the adaptive controller or a slider.</li>
 *   <li>Unknown keys found on disk are preserved verbatim and written back, so a
 *       config file from a newer BlockBoost does not lose data when opened by an
 *       older one.</li>
 * </ul>
 */
public final class Settings {

    /** Package private so {@link ConfigStore} can bulk-fill without a setter storm. */
    final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, Object> unknownKeys = Collections.synchronizedMap(new TreeMap<>());
    /**
     * Unknown members found at the top level of the file, next to {@code settings} -
     * a future version's own sections. Kept separately so they are written back where
     * they were read from instead of being shuffled into our namespace.
     */
    private final Map<String, Object> unknownRoot = Collections.synchronizedMap(new TreeMap<>());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String presetName = "custom";

    public static Settings defaults() {
        Settings s = new Settings();
        for (SettingKey k : ConfigSchema.all()) {
            s.values.put(k.path(), k.defaultValue());
        }
        return s;
    }

    // ── reads ────────────────────────────────────────────────────────────────
    public Object raw(SettingKey key) {
        Object v = values.get(key.path());
        return v == null ? key.defaultValue() : v;
    }

    public boolean bool(SettingKey key) {
        return raw(key) instanceof Boolean b ? b : Boolean.TRUE.equals(key.sanitize(raw(key)));
    }

    public int integer(SettingKey key) {
        Object v = raw(key);
        return v instanceof Number n ? n.intValue() : ((Number) key.sanitize(v)).intValue();
    }

    public double dbl(SettingKey key) {
        Object v = raw(key);
        return v instanceof Number n ? n.doubleValue() : ((Number) key.sanitize(v)).doubleValue();
    }

    public String string(SettingKey key) {
        return String.valueOf(raw(key));
    }

    /** Index of the current value inside {@code key.enumValues()}; never negative. */
    public int enumIndex(SettingKey key) {
        return Math.max(0, key.enumIndex(string(key)));
    }

    public String enumValue(SettingKey key) {
        int i = key.enumValues().indexOf(string(key));
        return i < 0 ? String.valueOf(key.defaultValue()) : key.enumValues().get(i);
    }

    /** True when an ENUM setting currently sits on the given allowed value. */
    public boolean is(SettingKey key, String enumValue) {
        return enumValue.equals(string(key));
    }

    /** Tri-state helper for the "ignore / on / off" vanilla overrides. */
    public int triState(SettingKey key) {
        int i = enumIndex(key);
        return i; // 0 = ignore, 1 = on, 2 = off
    }

    // ── writes ───────────────────────────────────────────────────────────────
    public boolean set(SettingKey key, Object value) {
        Object sanitized = key.sanitize(value);
        Object previous = values.put(key.path(), sanitized);
        boolean changed = !sanitized.equals(previous);
        if (changed) {
            presetName = "custom";
            notifyChanged();
        }
        return changed;
    }

    /** Bulk write used by presets and migrations; notifies once. */
    public int applyAll(Map<SettingKey, Object> patch) {
        int changed = 0;
        for (Map.Entry<SettingKey, Object> e : patch.entrySet()) {
            Object sanitized = e.getKey().sanitize(e.getValue());
            if (!sanitized.equals(values.put(e.getKey().path(), sanitized))) {
                changed++;
            }
        }
        if (changed > 0) {
            notifyChanged();
        }
        return changed;
    }

    public void markPreset(String name) {
        this.presetName = name;
    }

    public String presetName() {
        return presetName;
    }

    public void onChanged(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    private void notifyChanged() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Throwable t) {
                dev.blockboost.BlockBoost.LOG.warn("BlockBoost settings listener threw", t);
            }
        }
    }

    // ── serialization helpers ────────────────────────────────────────────────
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SettingKey k : ConfigSchema.all()) {
            out.put(k.path(), raw(k));
        }
        return out;
    }

    /** Paths whose value differs from {@code other}. Used by the preset preview. */
    public List<String> diffPaths(Settings other) {
        List<String> out = new ArrayList<>();
        for (SettingKey k : ConfigSchema.all()) {
            if (!java.util.Objects.equals(raw(k), other.raw(k))) {
                out.add(k.path());
            }
        }
        return out;
    }

    void putUnknown(String path, Object value) {
        unknownKeys.put(path, value);
    }

    public void putUnknownRoot(String key, Object value) {
        unknownRoot.put(key, value);
    }

    private volatile boolean freshInstall;

    /**
     * True when this instance was created because no config file existed. Transient by
     * design: it is a fact about this launch, not about the user's preferences, and it
     * must not be written into the file it describes.
     */
    public boolean isFreshInstall() {
        return freshInstall;
    }

    public void setFreshInstall(boolean fresh) {
        this.freshInstall = fresh;
    }

    public Map<String, Object> unknownRoot() {
        synchronized (unknownRoot) {
            return new LinkedHashMap<>(unknownRoot);
        }
    }

    public Map<String, Object> unknownKeys() {
        synchronized (unknownKeys) {
            return new LinkedHashMap<>(unknownKeys);
        }
    }

    public int knownCount() {
        return values.size();
    }

    @Override
    public String toString() {
        return "Settings{" + values.size() + " values, preset=" + presetName + '}';
    }
}
