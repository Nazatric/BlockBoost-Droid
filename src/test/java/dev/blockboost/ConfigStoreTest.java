package dev.blockboost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.ConfigStore;
import dev.blockboost.config.Settings;

/**
 * The config layer is the one part of this mod that can silently destroy user data,
 * so these tests are about what happens at the edges: bad values, unreadable files,
 * older versions, and a save interrupted mid-way.
 */
public class ConfigStoreTest {

    @Test
    void roundTripsEverySetting(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blockboost.json");
        Settings settings = Settings.defaults();
        settings.set(ConfigSchema.RENDER_FRAME_CAP, 120);
        settings.set(ConfigSchema.CULL_ENTITY_MODE, "frustum_occlusion");
        settings.set(ConfigSchema.CULL_ENTITY_MAX_DISTANCE, 96);
        settings.set(ConfigSchema.ANDROID_RENDERER, "zink");
        ConfigStore.save(file, settings);

        ConfigStore.LoadResult loaded = ConfigStore.load(file);
        assertFalse(loaded.recovered(), "a clean file must not report recovery: " + loaded.notes());
        assertEquals(120, loaded.settings().integer(ConfigSchema.RENDER_FRAME_CAP));
        assertEquals("frustum_occlusion", loaded.settings().enumValue(ConfigSchema.CULL_ENTITY_MODE));
        assertEquals(96, loaded.settings().integer(ConfigSchema.CULL_ENTITY_MAX_DISTANCE));
        assertEquals("zink", loaded.settings().enumValue(ConfigSchema.ANDROID_RENDERER));
        assertTrue(Files.exists(file), "the config file itself must remain");
    }

    @Test
    void clampsValuesOutsideTheirRange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blockboost.json");
        // A hand-edited file with nonsense in it: we clamp and say so, never crash
        // and never silently keep an impossible value that a feature would trip on.
        Files.writeString(file, """
                {
                  "version": 3,
                  "settings": {
                    "render.framerate_cap": 999999,
                    "culling.entity_workers": -4,
                    "culling.entity_mode": "teleport_entities"
                  }
                }
                """);
        ConfigStore.LoadResult loaded = ConfigStore.load(file);
        Settings s = loaded.settings();
        int cap = s.integer(ConfigSchema.RENDER_FRAME_CAP);
        int workers = s.integer(ConfigSchema.CULL_ENTITY_WORKERS);
        assertTrue(cap <= ConfigSchema.RENDER_FRAME_CAP.max(), "cap clamped to range, got " + cap);
        assertTrue(cap >= ConfigSchema.RENDER_FRAME_CAP.min(), "cap above minimum, got " + cap);
        assertTrue(workers >= ConfigSchema.CULL_ENTITY_WORKERS.min(), "workers clamped up, got " + workers);
        assertTrue(s.enumValue(ConfigSchema.CULL_ENTITY_MODE) != null, "unknown enum falls back to a valid value");
        assertFalse(loaded.clamped().isEmpty(), "clamping must be reported to the user, not done in silence");
    }

    @Test
    void recoversFromCorruptFileAndKeepsIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blockboost.json");
        Files.writeString(file, "{ this is not json at all ]]]");
        ConfigStore.LoadResult loaded = ConfigStore.load(file);
        assertTrue(loaded.recovered(), "a parse failure must be reported");
        assertNotNull(loaded.settings());
        assertEquals(ConfigSchema.RENDER_FRAME_CAP.defaultValue(),
                loaded.settings().raw(ConfigSchema.RENDER_FRAME_CAP), "recovery means defaults");
        boolean kept = false;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (p.getFileName().toString().contains("corrupt")) {
                    kept = true;
                }
            }
        }
        assertTrue(kept, "the unreadable file must be preserved for the user to inspect");
    }

    @Test
    void preservesUnknownKeysAcrossSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blockboost.json");
        Files.writeString(file, """
                {
                  "version": 3,
                  "settings": { "render.framerate_cap": 60 },
                  "future_block_from_another_version": { "kept": true }
                }
                """);
        ConfigStore.LoadResult loaded = ConfigStore.load(file);
        ConfigStore.save(file, loaded.settings());
        String after = Files.readString(file);
        assertTrue(after.contains("future_block_from_another_version"),
                "a newer mod must not lose a key it does not understand: " + after);
    }

    @Test
    void backupIsWrittenBeforeOverwrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blockboost.json");
        Settings settings = Settings.defaults();
        ConfigStore.save(file, settings);
        ConfigStore.save(file, settings);
        assertTrue(Files.exists(dir.resolve("blockboost.json.bak")),
                "the previous file must survive a save so a crash cannot lose both copies");
    }
}
