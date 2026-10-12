package dev.blockboost.config;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Definition of a single configuration entry.
 *
 * <p>Every setting exposed anywhere in BlockBoost (presets, settings screen,
 * adaptive controller, diagnostics report) is described by one of these, so
 * ranges, defaults, doc keys and restart requirements are defined exactly once.
 * The settings screen and the JSON reader both validate against this object,
 * which is what makes "the value on disk can never be nonsense" true.
 */
public record SettingKey(
        String path,
        Kind kind,
        Object defaultValue,
        double min,
        double max,
        List<String> enumValues,
        Category category,
        Risk risk,
        boolean requiresRestart,
        String docSuffix) {

    /** Value type of the entry. Stored in JSON as a native JSON type. */
    public enum Kind { BOOLEAN, INTEGER, DOUBLE, ENUM }

    /**
     * How much a user should trust this setting on unknown hardware. The UI shows
     * this and "Compatibility Safe" / presets use it to decide what may be enabled.
     */
    public enum Risk { SAFE, AGGRESSIVE, EXPERIMENTAL }

    /** UI grouping; also used to slice the diagnostics report. */
    public enum Category {
        RENDERING("rendering"),
        CULLING("culling"),
        CHUNKS("chunks"),
        CPU("cpu"),
        MEMORY("memory"),
        PARTICLES("particles"),
        ANDROID("android"),
        COMPAT("compat"),
        ADVANCED("advanced"),
        DIAGNOSTICS("diagnostics"),
        ADAPTIVE("adaptive");

        public final String id;

        Category(String id) {
            this.id = id;
        }
    }

    public SettingKey {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(risk, "risk");
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (kind == Kind.ENUM && enumValues.isEmpty()) {
            throw new IllegalArgumentException("ENUM setting needs allowed values: " + path);
        }
        if (kind != Kind.ENUM && min > max) {
            throw new IllegalArgumentException("bad range for " + path);
        }
        if (!enumValues.isEmpty() && !enumValues.contains(String.valueOf(defaultValue))) {
            throw new IllegalArgumentException("default not in allowed values for " + path);
        }
        if (kind == Kind.BOOLEAN && !(defaultValue instanceof Boolean)) {
            throw new IllegalArgumentException("boolean default must be Boolean: " + path);
        }
    }

    public static SettingKey bool(String path, boolean def, Category cat, Risk risk, boolean restart, String doc) {
        return new SettingKey(path, Kind.BOOLEAN, def, 0, 1, List.of(), cat, risk, restart, doc);
    }

    public static SettingKey intRange(String path, int def, int min, int max, Category cat, Risk risk, boolean restart, String doc) {
        return new SettingKey(path, Kind.INTEGER, def, min, max, List.of(), cat, risk, restart, doc);
    }

    public static SettingKey dblRange(String path, double def, double min, double max, Category cat, Risk risk, boolean restart, String doc) {
        return new SettingKey(path, Kind.DOUBLE, def, min, max, List.of(), cat, risk, restart, doc);
    }

    public static SettingKey enumOf(String path, String def, List<String> values, Category cat, Risk risk, boolean restart, String doc) {
        return new SettingKey(path, Kind.ENUM, def, 0, 0, values, cat, risk, restart, doc);
    }

    public String translationKey(String suffix) {
        return "blockboost.setting." + path.replace('.', '_') + "." + suffix;
    }

    public String nameKey() {
        return translationKey("name");
    }

    public String tooltipKey() {
        return translationKey("tooltip");
    }

    public String noteKey() {
        return translationKey("note");
    }

    public boolean isInteger() {
        return kind == Kind.INTEGER;
    }

    /** Clamps and coerces a raw value into a valid value for this key. */
    public Object sanitize(Object raw) {
        if (raw == null) {
            return defaultValue;
        }
        switch (kind) {
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                if (raw instanceof Number n) {
                    return n.intValue() != 0;
                }
                if (raw instanceof String s) {
                    if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on") || s.equalsIgnoreCase("yes")) {
                        return Boolean.TRUE;
                    }
                    if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off") || s.equalsIgnoreCase("no")) {
                        return Boolean.FALSE;
                    }
                }
                return defaultValue;
            }
            case INTEGER -> {
                double d = asDouble(raw, Double.NaN);
                if (Double.isNaN(d)) {
                    return defaultValue;
                }
                long clamped = Math.round(Math.max(min, Math.min(max, d)));
                return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, clamped));
            }
            case DOUBLE -> {
                double d = asDouble(raw, Double.NaN);
                if (Double.isNaN(d)) {
                    return defaultValue;
                }
                return Math.max(min, Math.min(max, d));
            }
            case ENUM -> {
                String s = String.valueOf(raw);
                if (enumValues.contains(s)) {
                    return s;
                }
                // Tolerate the legacy `MODID:Value` style and case differences so an
                // older config file never becomes a hard error.
                for (String allowed : enumValues) {
                    if (allowed.equalsIgnoreCase(s)) {
                        return allowed;
                    }
                }
                return defaultValue;
            }
            default -> {
                return defaultValue;
            }
        }
    }

    private static double asDouble(Object raw, double fallback) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public int enumIndex(String value) {
        int i = enumValues.indexOf(value);
        return i < 0 ? enumValues.indexOf(String.valueOf(defaultValue)) : i;
    }

    public boolean inRange(double v) {
        return kind == Kind.ENUM || kind == Kind.BOOLEAN || (v >= min && v <= max);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SettingKey other && other.path.equals(path) && other.kind == kind
                && Objects.equals(other.defaultValue, defaultValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, kind);
    }

    @Override
    public String toString() {
        return path + "=" + defaultValue + (kind == Kind.ENUM ? Arrays.toString(enumValues.toArray()) : "[" + min + ".." + max + "]");
    }
}
