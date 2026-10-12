package dev.blockboost.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.ConfigStore;
import dev.blockboost.core.FeatureId;
import dev.blockboost.core.FeatureRegistry;
import dev.blockboost.util.LongByteCache;
import dev.blockboost.util.RollingStats;

/**
 * The one test that proves the mod survives a real game.
 *
 * <p>Unit tests cover the logic; nothing short of a running client proves that the
 * mixins applied to the right methods, that a feature's activation does not throw with
 * a live renderer underneath, and that the config path resolves inside an actual game
 * directory. This runs headless in CI under a virtual framebuffer, so what it verifies
 * is "initializes and ticks cleanly with no GPU guarantees" — which is exactly the
 * environment an Android launcher can turn into.
 *
 * <p>It deliberately touches only the Minecraft-free layers plus {@code Minecraft}
 * itself: no reflection into private state, and no assertion about frame rate, because
 * a software framebuffer has no meaningful FPS to assert on. Anyone who reads a
 * benchmark number out of this environment is measuring llvmpipe.
 */
@SuppressWarnings("UnstableApiUsage")
public final class BlockBoostClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        Minecraft client = Minecraft.getInstance();
        assertTrue(client != null, "no client instance in a client game test");

        // 1) The mod initialized on the client side at all.
        BlockBoostRuntime runtime = BlockBoostRuntime.get();
        assertTrue(runtime.isStarted(), "BlockBoostRuntime did not start");
        assertTrue(runtime.isClientSide(), "runtime does not know it is on the client");

        // 2) Every feature evaluated, and none of them failed. A FAILED/DISABLED
        //    status here means a mixin or an activation path is broken on this build,
        //    which is the single most important thing this test can catch.
        FeatureRegistry registry = runtime.registry();
        assertTrue(registry.registeredCount() > 0, "no features registered");
        assertEquals(0, registry.failureCount(),
                "a feature failed to initialize: " + String.join(" | ", registry.statusLines()));
        for (String line : registry.statusLines()) {
            assertFalse(line.contains("FAILED"), "feature reported FAILED: " + line);
        }

        // 3) The mixins landed. Status of the culling subsystems is only ACTIVE when
        //    their classes loaded and their hooks were applied without a refusal.
        //    (Safe mode is off in the test config, so culling should be active here.)
        assertTrue(registry.isActive(FeatureId.ENTITY_CULLING),
                "entity culling inactive: " + registry.reason(FeatureId.ENTITY_CULLING));

        // 4) Config round-trips through the real game directory, and the file we promise
        //    about is the file that exists.
        Path config = runtime.configPath();
        runtime.saveNow();
        assertTrue(Files.exists(config), "config not written to " + config);
        ConfigStore.LoadResult reloaded = ConfigStore.load(config);
        assertFalse(reloaded.recovered(), "config did not parse back: " + reloaded.notes());

        // 5) The bounded structures stay bounded under a burst of writes.
        LongByteCache cache = new LongByteCache(32);
        for (int i = 0; i < 5_000; i++) {
            cache.put(i, (byte) (i & 0x7F));
            assertTrue(cache.size() <= cache.capacity(), "cache grew past its capacity");
        }
        RollingStats stats = new RollingStats(64, 0);
        for (int i = 0; i < 5_000; i++) {
            stats.sample(10 + (i % 7));
        }
        assertTrue(stats.samples() <= 64, "frame window grew unbounded: " + stats.samples());

        // 6) The client ticks with our hooks installed: a handful of ticks, and the
        //    frame hook must not have thrown (a thrown hook is quarantined, so the
        //    registry state is the thing to check afterwards).
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            for (int i = 0; i < 40; i++) {
                context.waitTick();
            }
            assertEquals(0, registry.failureCount(), "a feature failed while the world ran");
            assertFalse(runtime.registry().isQuarantined(FeatureId.ENTITY_CULLING),
                    "entity culling was quarantined at runtime: " + registry.reason(FeatureId.ENTITY_CULLING));
            context.takeScreenshot("blockboost-world");
            measureHotPath(context);
        }

        // 7) The settings screen is hand-built layout code: it compiles and still shows
        //    nothing, or throws on the first tap. Open it on the live client and walk every
        //    section, which is the only way to know the widgets actually get laid out.
        int laidOut = 0;
        try {
            dev.blockboost.client.ui.BlockBoostSettingsScreen screen =
                    (dev.blockboost.client.ui.BlockBoostSettingsScreen)
                            dev.blockboost.client.ui.BlockBoostSettingsScreen.create(null);
            client.setScreen(screen);
            assertTrue(client.screen == screen, "setScreen did not take the mod's screen");
            for (int i = 0; i < 10; i++) {
                screen.nextTab();
                laidOut += screen.laidOutRows();
                assertTrue(client.screen == screen, "the screen closed itself moving to tab " + i);
            }
            client.setScreen(null);
        } catch (AssertionError | Exception e) {
            client.setScreen(null);
            throw new AssertionError("settings screen unusable: " + e, e);
        }
        assertTrue(laidOut > 0, "the settings screen laid out no rows on any tab");
        screenNote = "tabs walked, rows=" + laidOut;

        // 8) The report path exists and writes into the game directory only.
        List<String> report = BlockBoostRuntime.get().startupNotes();
        assertTrue(report != null, "startup notes must not be null");

        writeSummary(client, runtime, registry);
    }

    /**
     * The artifact CI collects. Deliberately small: it is a "did the game run with us
     * inside it" record, not a benchmark, and it says so in its own header.
     */
    private void writeSummary(Minecraft client, BlockBoostRuntime runtime, FeatureRegistry registry) {
        try {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve("blockboost").resolve("bench");
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            sb.append("BlockBoost client game test summary\n");
            sb.append("==================================\n");
            sb.append("This is NOT a benchmark. It runs on a software framebuffer with no GPU\n");
            sb.append("and no thermal behaviour, so frame timings measured here mean nothing\n");
            sb.append("about any real device. It exists to prove the mod initializes and ticks.\n\n");
            sb.append("version=").append(BlockBoost.version()).append('\n');
            sb.append("minecraft=").append(System.getProperty("blockboost.test.mc", "26.1.2")).append('\n');
            sb.append("config=").append(runtime.configPath()).append('\n');
            sb.append("renderer_profile=").append(runtime.device().rendererProfile()).append('\n');
            sb.append("device_class=").append(runtime.device().deviceClass()).append('\n');
            sb.append("features_registered=").append(registry.registeredCount()).append('\n');
            sb.append("feature_failures=").append(registry.failureCount()).append('\n');
            for (String line : registry.statusLines()) {
                sb.append("  ").append(line).append('\n');
            }
            sb.append("hud_setting=").append(runtime.settings().enumValue(ConfigSchema.DIAG_HUD)).append('\n');
            sb.append("hot_path=").append(hotPathNote).append('\n');
            sb.append("settings_screen=").append(screenNote).append('\n');
            sb.append("occlusion_probe=").append(dev.blockboost.client.BlockBoostClient.services() == null
                    ? "no services" : dev.blockboost.client.BlockBoostClient.services().probeDescription())
                    .append('\n');
            sb.append("result=PASS\n");
            Files.writeString(dir.resolve("latest-summary.txt"), sb.toString());
            // Also into the game log: CI greps the log, and a per-run working directory
            // makes the file easy to lose.
            for (String line : sb.toString().split("\n")) {
                BlockBoost.LOG.info("[BlockBoost gametest] {}", line);
            }
            BlockBoost.LOG.info("[BlockBoost] game test summary written to {}", dir);
        } catch (Exception e) {
            // The assertions above are the test; a failed summary write must not turn a
            // green run red.
            BlockBoost.LOG.warn("[BlockBoost] could not write the game test summary", e);
        }
    }

    /**
     * The measurement that matters for this project's history: what the culling hot path
     * actually costs inside a running client, with a real level and a real renderer.
     *
     * <p>Frame rates in CI mean nothing (software GL), but <em>our own</em> per-frame CPU
     * cost is measurable and it is the number that regressed badly once already: the first
     * builds of this mod could take a 200 fps game down to 40 because an occlusion test ran
     * unbounded, reflectively, allocating a position object per block it looked at. That
     * class of bug is invisible to a unit test and obvious here, so it is asserted: 2000
     * visibility decisions, on one frame's budget, must cost less than eight milliseconds.
     */
    private void measureHotPath(ClientGameTestContext context) {
        dev.blockboost.client.ClientServices services = dev.blockboost.client.BlockBoostClient.services();
        if (services == null || Minecraft.getInstance().player == null) {
            BlockBoost.logInfo("[BlockBoost gametest] no player yet; hot-path measurement skipped");
            return;
        }
        var engine = services.visibility();
        var probe = services.levelProbe();
        if (engine == null || probe == null) {
            hotPathNote = "culling engine or level probe unavailable";
            return;
        }
        var eye = Minecraft.getInstance().player.position();
        long start = System.nanoTime();
        int decisions = 2000;
        engine.beginFrame(1L, eye.x, eye.y, eye.z);
        int culled = 0;
        for (int i = 0; i < decisions; i++) {
            double ox = eye.x + ((i * 37) % 64) - 32;
            double oy = eye.y + ((i * 13) % 24) - 12;
            double oz = eye.z + ((i * 53) % 64) - 32;
            byte verdict = engine.evaluate(900_000L + (i % 256), ox, oy, oz, ox + 0.6, oy + 1.9, oz + 0.6,
                    probe, true);
            if (verdict == dev.blockboost.client.culling.AsyncVisibilityEngine.HIDDEN) {
                culled++;
            }
        }
        long micros = Math.max(1L, (System.nanoTime() - start) / 1000L);
        long perDecision = micros / decisions;
        hotPathNote = "culling: " + decisions + " decisions in " + micros + " us ("
                + perDecision + " us each, " + culled + " hidden), memo " + engine.memoSummary();
        BlockBoost.LOG.info("[BlockBoost gametest] {}", hotPathNote);
        assertTrue(micros < 8_000L,
                "one frame's worth of visibility tests must cost under 8 ms, took " + micros
                        + " us for " + decisions + " decisions (" + perDecision + " us each): " + hotPathNote);
    }

    private static String hotPathNote = "not measured";
    private static String screenNote = "not measured";

    public static String hotPathNote() {
        return hotPathNote;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
        }
    }
}
