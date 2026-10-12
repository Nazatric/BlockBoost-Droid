package dev.blockboost;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.SettingKey;
import dev.blockboost.config.Settings;
import dev.blockboost.device.DeviceClass;
import dev.blockboost.device.DeviceProfileAdvisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heuristics that decide what a phone gets without touching a single setting, tested
 * on the numbers themselves rather than on a device list.
 */
class DeviceProfileAdvisorTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void weakPhoneLandsOnLowAndStaysQuiet() {
        DeviceProfileAdvisor.Recommendation rec = DeviceProfileAdvisor.recommend(
                4, 1536 * MB, true, true, "ARM", "Mali-G52", true);
        assertEquals(DeviceClass.LOW, rec.deviceClass());
        assertEquals(1, rec.patch().get(ConfigSchema.CULL_ENTITY_WORKERS));
        assertEquals(false, rec.patch().get(ConfigSchema.CULL_ENTITY_ASYNC));
        assertEquals("frustum", rec.patch().get(ConfigSchema.CULL_ENTITY_MODE));
        assertEquals(false, rec.patch().get(ConfigSchema.CULL_BLOCK_ENTITY_OCCLUSION));
    }

    @Test
    void aGpuNameAloneNeverBuysAggressiveness() {
        // Same GPU, two very different hosts: the class must follow cores and heap.
        DeviceProfileAdvisor.Recommendation weak = DeviceProfileAdvisor.recommend(
                6, 2048 * MB, true, true, "ARM", "Mali-G720 MC7", true);
        DeviceProfileAdvisor.Recommendation strong = DeviceProfileAdvisor.recommend(
                8, 8192 * MB, false, false, "NVIDIA", "Mali-G720 MC7", false);
        assertTrue(weak.deviceClass().workerThreads <= strong.deviceClass().workerThreads,
                "a flagship GPU in a 2 GB heap must not out-schedule a desktop with 8 GB");
        assertEquals(DeviceClass.MID, weak.deviceClass(), "Android is capped below HIGH by design");
        assertEquals(DeviceClass.HIGH, strong.deviceClass());
    }

    @Test
    void neverChoosesAFrameRateOrVsyncState() {
        // A cap or vsync chosen by a heuristic is the classic "why is my game at 60" bug,
        // and this project has been bitten by exactly that. These keys must stay out of
        // the automatic patch entirely.
        for (boolean mobile : new boolean[] {true, false}) {
            for (int cores = 2; cores <= 16; cores *= 2) {
                for (long heap = 768; heap <= 16384; heap *= 2) {
                    DeviceProfileAdvisor.Recommendation rec = DeviceProfileAdvisor.recommend(
                            cores, heap * MB, mobile, mobile, null, null, false);
                    assertFalse(rec.patch().containsKey(ConfigSchema.RENDER_FRAME_CAP), "frame cap was chosen");
                    assertFalse(rec.patch().containsKey(ConfigSchema.RENDER_IDLE_FRAME_CAP), "idle cap was chosen");
                    // vsync may be written, but only to "auto" - which means "hands off".
                    Object vsync = rec.patch().get(ConfigSchema.RENDER_VSYNC);
                    assertTrue(vsync == null || "auto".equals(vsync), "vsync was forced to " + vsync);
                    assertEquals(Boolean.FALSE, rec.patch().get(ConfigSchema.RENDER_SOFT_PACING));
                }
            }
        }
    }

    @Test
    void everyRecommendationIsValidForTheSchema() {
        // Whatever the heuristics conclude, it must be a value the schema accepts as-is:
        // a mod that writes an out-of-range value at first launch has broken the one setup
        // step a user cannot see coming.
        long[][] cases = {{2, 1024}, {4, 1536}, {6, 2048}, {8, 4096}, {8, 8192}, {16, 16384}, {0, 0}};
        for (long[] c : cases) {
            for (boolean mobile : new boolean[] {true, false}) {
                DeviceProfileAdvisor.Recommendation rec = DeviceProfileAdvisor.recommend(
                        (int) c[0], c[1] * MB, mobile, mobile, "Qualcomm", "Adreno (TM) 640", true);
                Settings settings = Settings.defaults();
                int changed = settings.applyAll(rec.patch());
                assertTrue(changed > 0, "the patch wrote nothing");
                for (Map.Entry<SettingKey, Object> e : rec.patch().entrySet()) {
                    Object value = settings.raw(e.getKey());
                    assertNotNull(value, "key lost: " + e.getKey().path());
                    assertTrue(e.getKey().inRange(toDouble(value)),
                            "value out of range after apply: " + e.getKey().path() + "=" + value);
                }
                assertFalse(rec.isEmpty(), "empty recommendation for " + c[0] + " cores");
                assertFalse(rec.reasons().isEmpty(), "no explanation for " + c[0] + " cores");
                assertNotNull(rec.summary());
            }
        }
    }

    @Test
    void unknownCpuCountIsNotSilentlyOptimistic() {
        DeviceProfileAdvisor.Recommendation rec = DeviceProfileAdvisor.recommend(
                0, 0, false, false, null, null, false);
        assertEquals(DeviceClass.UNKNOWN, rec.deviceClass());
        assertEquals(1, rec.patch().get(ConfigSchema.CULL_ENTITY_WORKERS));
        assertEquals(false, rec.patch().get(ConfigSchema.CULL_ENTITY_ASYNC));
    }

    @Test
    void adaptiveControllerNeverActsWithoutBeingAsked() {
        DeviceProfileAdvisor.Recommendation rec = DeviceProfileAdvisor.recommend(
                8, 8192 * MB, false, false, null, null, false);
        assertEquals("recommend", rec.patch().get(ConfigSchema.ADAPTIVE_MODE));
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return 1; // strings and objects are range-checked by sanitize elsewhere
    }
}
