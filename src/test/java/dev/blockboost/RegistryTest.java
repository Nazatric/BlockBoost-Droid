package dev.blockboost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.blockboost.compat.ModCompatibility;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Preset;
import dev.blockboost.config.SettingKey;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureEnvironment;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRegistry;
import dev.blockboost.core.FeatureRuntimeContext;
import dev.blockboost.core.FeatureStatus;
import dev.blockboost.util.ScratchBuffers;
import dev.blockboost.util.TaskPools;

/**
 * The registry is what turns "a feature threw" into "the game still starts, and the
 * user can see why". That behaviour is the difference between an optimization mod and
 * a crash generator, so it is tested directly, with a fake feature per case.
 */
public class RegistryTest {

    private static final class Fake implements Feature {
        private final FeatureId id;
        private final boolean enabled;
        private final Runnable onActivate;
        volatile boolean activated;
        volatile boolean deactivated;

        Fake(FeatureId id, boolean enabled) {
            this(id, enabled, null);
        }

        Fake(FeatureId id, boolean enabled, Runnable onActivate) {
            this.id = id;
            this.enabled = enabled;
            this.onActivate = onActivate;
        }

        @Override
        public FeatureId id() {
            return id;
        }

        @Override
        public boolean enabledByConfig(Settings settings) {
            return enabled;
        }

        @Override
        public void onActivate(FeatureRuntimeContext ctx) {
            activated = true;
            if (onActivate != null) {
                onActivate.run();
            }
        }

        @Override
        public void onDeactivate(FeatureRuntimeContext ctx) {
            deactivated = true;
        }
    }

    private static FeatureRuntimeContext ctx(Settings settings) {
        FeatureEnvironment env = new FeatureEnvironment() {
            @Override
            public boolean hasActiveLevel() {
                return false;
            }
        };
        return new FeatureRuntimeContext(settings, null, new ModCompatibility(), new TaskPools(),
                new ScratchBuffers(64), env, new FeatureRuntimeContext.FeatureDiagnostics() {
                    @Override
                    public void counter(String name, long value) {
                    }

                    @Override
                    public void note(String message) {
                    }
                });
    }

    @Test
    void activatingAndDeactivatingFollowsConfig() {
        Settings settings = Settings.defaults();
        FeatureRegistry registry = new FeatureRegistry();
        Fake feature = new Fake(FeatureId.ENTITY_CULLING, true);
        registry.register(feature);
        registry.bind(ctx(settings), settings);
        registry.refresh();
        assertTrue(registry.isActive(FeatureId.ENTITY_CULLING));
        assertTrue(feature.activated);
        assertEquals(FeatureStatus.ACTIVE, registry.status(FeatureId.ENTITY_CULLING));

        settings.set(ConfigSchema.COMPAT_SAFE_MODE, true);
        registry.refresh();
        assertFalse(registry.isActive(FeatureId.ENTITY_CULLING),
                "safe mode must take over from a config-enabled aggressive feature");
        assertTrue(feature.deactivated);
        assertEquals(FeatureStatus.SAFE_MODE, registry.status(FeatureId.ENTITY_CULLING));
        assertNotNull(registry.reason(FeatureId.ENTITY_CULLING));
    }

    @Test
    void aThrowingFeatureIsIsolatedAndTheRestKeepRunning() {
        Settings settings = Settings.defaults();
        FeatureRegistry registry = new FeatureRegistry();
        Fake broken = new Fake(FeatureId.BLOCK_ENTITY_CULLING, true, () -> {
            throw new IllegalStateException("simulated mixin failure");
        });
        Fake healthy = new Fake(FeatureId.ENTITY_CULLING, true);
        registry.register(broken);
        registry.register(healthy);
        registry.bind(ctx(settings), settings);
        registry.refresh();

        assertFalse(registry.isActive(FeatureId.BLOCK_ENTITY_CULLING));
        assertTrue(registry.isActive(FeatureId.ENTITY_CULLING), "one failure must not take the mod down");
        assertTrue(registry.isQuarantined(FeatureId.BLOCK_ENTITY_CULLING),
                "isolation is on by default, so the broken feature stays off until restart");
        assertTrue(registry.reason(FeatureId.BLOCK_ENTITY_CULLING).contains("simulated mixin failure"),
                "the user must be able to read what happened: " + registry.reason(FeatureId.BLOCK_ENTITY_CULLING));
        assertEquals(1, registry.failureCount());

        // Quarantine survives further refreshes: it must not retry every config change.
        registry.refresh();
        assertFalse(registry.isActive(FeatureId.BLOCK_ENTITY_CULLING));
    }

    @Test
    void everyPresetOnlyWritesValidValues() {
        Settings settings = Settings.defaults();
        for (Preset preset : Preset.ALL) {
            Settings copy = Settings.defaults();
            int changed = copy.applyAll(preset.patch());
            assertTrue(changed >= 0);
            for (Map.Entry<SettingKey, Object> entry : preset.patch().entrySet()) {
                SettingKey key = entry.getKey();
                Object value = key.sanitize(entry.getValue());
                assertNotNull(value, key.path() + " sanitized to null for preset " + preset.id());
                switch (key.kind()) {
                    case INTEGER -> assertTrue(key.min() <= ((Number) value).doubleValue()
                                    && ((Number) value).doubleValue() <= key.max(),
                            preset.id() + " writes " + value + " outside " + key.path());
                    case DOUBLE -> assertTrue(key.inRange(((Number) value).doubleValue()),
                            preset.id() + " writes an out-of-range double to " + key.path());
                    case ENUM -> assertTrue(key.enumValues().contains(String.valueOf(value)),
                            preset.id() + " writes unknown enum value " + value + " to " + key.path());
                    case BOOLEAN -> assertTrue(value instanceof Boolean, key.path() + " must be boolean");
                    default -> throw new AssertionError("unhandled kind " + key.kind());
                }
            }
            // A preset must never be able to leave the config in an unsaveable state.
            String json = dev.blockboost.config.ConfigStore.toJson(copy);
            assertTrue(json.length() > 20, "preset " + preset.id() + " produced no config");
        }
        assertTrue(Preset.byId("does_not_exist").patch().isEmpty(), "unknown preset must mean custom/no changes");
        assertTrue(Preset.CUSTOM.patch().isEmpty());
        assertTrue(Preset.ALL.size() >= 7, "the seven documented presets must all exist");
        List<String> ids = Preset.ALL.stream().map(Preset::id).toList();
        assertEquals(ids.stream().distinct().count(), ids.size(), "preset ids must be unique");
    }
}
