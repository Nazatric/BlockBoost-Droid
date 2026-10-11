package dev.blockboost.core;

/**
 * Status of one subsystem, with a human-readable reason. The reason is not
 * decoration: the UI and the exported report are the only way a user can find
 * out that a feature is off because Sodium already does it, or because it threw
 * during activation, instead of "it is off, figure it out yourself".
 */
public enum FeatureStatus {
    ACTIVE("active"),
    OFF_BY_CONFIG("disabled in settings"),
    SUPPRESSED_BY_MOD("handled by another mod"),
    UNSUPPORTED("not supported by this renderer or build"),
    DISABLED_AT_RUNTIME("switched off after an error"),
    FAILED("failed to initialize"),
    SAFE_MODE("disabled by compatibility safe mode");

    public final String label;

    FeatureStatus(String label) {
        this.label = label;
    }
}
