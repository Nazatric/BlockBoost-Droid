package dev.blockboost.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.core.FeatureId;
import dev.blockboost.client.adaptive.AdaptiveController;
import dev.blockboost.client.features.BlockEntityCullingFeature;
import dev.blockboost.client.features.VanillaOptions;
import dev.blockboost.client.features.ChunkPipelineFeature;
import dev.blockboost.client.features.DiagnosticsFeature;
import dev.blockboost.client.features.EntityCullingFeature;
import dev.blockboost.client.features.FramePacingFeature;
import dev.blockboost.client.features.MemoryHygieneFeature;
import dev.blockboost.client.features.ParticleBudgetFeature;
import dev.blockboost.client.features.VideoOverrideFeature;
import dev.blockboost.client.features.WorkerTuningFeature;
import dev.blockboost.client.ui.PerformanceHud;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.Feature;
import dev.blockboost.core.FeatureRegistry;
import dev.blockboost.core.FeatureRuntimeContext;

/**
 * Client bootstrap: owns the service graph, registers the features, and installs
 * the two thin integrations that must survive being absent (HUD and keybind).
 *
 * <p>Nothing here can take the game down. Every optional integration is wrapped,
 * because a mod that optimizes a phone launcher must be more robust than the
 * launcher it runs inside, not less.
 */
public final class BlockBoostClient implements ClientModInitializer {

    private static ClientServices services;
    private static FeatureRuntimeContext context;
    private static AdaptiveController adaptive;
    private static KeyMapping settingsKey;
    private static KeyMapping hudKey;
    private static final AtomicBoolean HUD_VISIBLE = new AtomicBoolean(true);
    private static final AtomicBoolean NOTIFY_DONE = new AtomicBoolean();
    private static final AtomicBoolean SETTINGS_HINT_SHOWN = new AtomicBoolean();
    private final AtomicBoolean settingsShown = new AtomicBoolean();
    private static VideoOverrideFeature videoOverride;
    private static FramePacingFeature framePacing;
    private static ParticleBudgetFeature particleBudget;
    private static WorkerTuningFeature workerTuning;
    private static ChunkPipelineFeature chunkPipeline;
    private static long benchDeadlineNanos;
    private static long benchFrames;

    public static ClientServices services() {
        return services;
    }

    public static FeatureRuntimeContext context() {
        return context;
    }

    public static AdaptiveController adaptive() {
        return adaptive;
    }

    public static boolean hudVisible() {
        return HUD_VISIBLE.get();
    }

    /** Cycles the overlay the way a touch user expects: off, basic, detailed. */
    public static void toggleHud() {
        Settings settings = BlockBoostRuntime.get().settings();
        String now = settings.enumValue(ConfigSchema.DIAG_HUD);
        String next = switch (now == null ? "off" : now) {
            case "off" -> "basic";
            case "basic" -> "advanced";
            default -> "off";
        };
        settings.set(ConfigSchema.DIAG_HUD, next);
        HUD_VISIBLE.set(!"off".equals(next));
        BlockBoostRuntime.get().requestSave();
    }

    @Override
    public void onInitializeClient() {
        BlockBoostRuntime runtime = BlockBoostRuntime.get();
        // Tell the runtime which side this is. start() is idempotent and OR-s the flag, so
        // this records "client" without re-initializing anything the common entrypoint built.
        // Without it BlockBoostRuntime.isClientSide() answered false in a running client -
        // harmless while nothing read it, and exactly the kind of flag a diagnostic or a
        // guarded mixin will trust later, so it is fixed where it starts rather than patched
        // at each reader. The client game test caught it: that assertion had never executed.
        runtime.start(true);
        Settings settings = runtime.settings();
        services = new ClientServices(settings, runtime.device(), runtime.compatibility());
        context = new FeatureRuntimeContext(settings, runtime.device(), runtime.compatibility(), services.pools(),
                services.scratch(), services, services.diagnostics());

        runtime.registry().bind(context, settings);
        registerFeatures(runtime.registry());
        runtime.registry().refresh();
        HotState.refresh();

        adaptive = new AdaptiveController(settings, runtime.device(), services, services.diagnostics());
        services.setAdaptive(adaptive);
        settings.onChanged(() -> {
            runtime.registry().refresh();
            HotState.refresh();
            if (adaptive != null) {
                adaptive.noteUserChange();
            }
            applyFeatureSettings();
        });

        registerKeybinds(settings);
        registerHud(settings);
        registerTickLoop(runtime, settings);
        parseBenchProperties(settings);

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> shutdown());
    }

    /**
     * Pushes config into the places that only react to a change (as opposed to the
     * features whose behaviour is read per frame through {@link HotState}).
     */
    private void applyFeatureSettings() {
        FeatureRegistry registry = BlockBoostRuntime.get().registry();
        if (videoOverride != null && registry.isActive(FeatureId.VIDEO_OVERRIDES)) {
            videoOverride.apply(context, false);
        }
        if (framePacing != null && registry.isActive(FeatureId.FRAME_PACING)) {
            framePacing.apply(context, false);
        }
        if (particleBudget != null && registry.isActive(FeatureId.PARTICLE_BUDGET)) {
            particleBudget.apply(context);
        }
        if (workerTuning != null && registry.isActive(FeatureId.WORKER_TUNING)) {
            workerTuning.apply(context);
        }
        HotState.refresh();
        VanillaOptions.persist(Minecraft.getInstance());
    }

    private void registerFeatures(FeatureRegistry registry) {
        List<Feature> features = List.of(
                new EntityCullingFeature(),
                new BlockEntityCullingFeature(),
                new ParticleBudgetFeature(),
                new ChunkPipelineFeature(),
                new FramePacingFeature(),
                new VideoOverrideFeature(),
                new WorkerTuningFeature(),
                new MemoryHygieneFeature(),
                new DiagnosticsFeature());
        for (Feature feature : features) {
            if (feature instanceof VideoOverrideFeature v) {
                videoOverride = v;
            } else if (feature instanceof FramePacingFeature f) {
                framePacing = f;
            } else if (feature instanceof ParticleBudgetFeature p) {
                particleBudget = p;
            } else if (feature instanceof WorkerTuningFeature w) {
                workerTuning = w;
            } else if (feature instanceof ChunkPipelineFeature c) {
                chunkPipeline = c;
            }
            try {
                registry.register(feature);
            } catch (RuntimeException e) {
                // A feature that cannot even register is a bug in this mod, not in the
                // user's setup: say so loudly and let the other ten run.
                BlockBoost.LOG.error("[BlockBoost] feature {} failed to register", feature.id(), e);
            }
        }
    }

    private void registerKeybinds(Settings settings) {
        try {
            KeyMapping.Category category = KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(BlockBoost.MOD_ID, "main"));
            settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "blockboost.key.settings", InputConstants.Type.KEYSYM, InputConstants.KEY_O, category));
            hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "blockboost.key.hud", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
        } catch (RuntimeException | LinkageError e) {
            BlockBoost.LOG.warn("[BlockBoost] keybind registration failed; the settings screen is still "
                    + "reachable through Mod Menu or options.txt editing", e);
            settingsKey = null;
            hudKey = null;
        }
    }

    private void registerHud(Settings settings) {
        try {
            HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath(BlockBoost.MOD_ID, "hud"),
                    BlockBoostClient::extractHud);
        } catch (RuntimeException | LinkageError e) {
            BlockBoost.LOG.warn("[BlockBoost] HUD layer unavailable ({}); continuing without the overlay",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * One-time chat hint. The settings screen is reachable by keybind and through Mod
     * Menu; a launcher with no keyboard and no Mod Menu is exactly the case where a
     * single line in chat telling you the file path beats a silent UI you cannot find.
     */
    private static void extractHud(GuiGraphicsExtractor graphics, DeltaTracker tracker) {
        ClientServices svc = services;
        if (svc == null || !HUD_VISIBLE.get() || svc.settings().is(ConfigSchema.DIAG_HUD, "off")) {
            return;
        }
        try {
            PerformanceHud.extract(graphics, tracker, svc);
        } catch (RuntimeException e) {
            // An overlay that throws must never take down a frame.
            BlockBoost.LOG.warn("[BlockBoost] HUD render failed; overlay disabled for this session", e);
            HUD_VISIBLE.set(false);
        }
    }

    private void registerTickLoop(BlockBoostRuntime runtime, Settings settings) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (settingsKey != null && settingsKey.consumeClick()) {
                    client.setScreen(dev.blockboost.client.ui.BlockBoostSettingsScreen.create(null));
                }
                while (hudKey != null && hudKey.consumeClick()) {
                    toggleHud();
                }
                if (NOTIFY_DONE.compareAndSet(false, true) && client.level != null) {
                    announceIfRecovered(runtime, client);
                }
                if (services != null) {
                    services.onClientTick(client);
                }
                if (chunkPipeline != null) {
                    chunkPipeline.tick(services, settings);
                }
                if (benchFrames > 0 && services != null) {
                    long done = services.frames().framesMeasured();
                    if (done >= benchFrames) {
                        finishBench(client);
                    }
                }
            } catch (RuntimeException e) {
                BlockBoost.LOG.warn("[BlockBoost] client tick hook failed (continuing)", e);
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (services != null) {
                services.onClientTickStart(client);
            }
        });
    }

    /**
     * A recovered or migrated config is the one thing the user must not miss: they
     * would otherwise keep playing on defaults and blame the mod for "losing" their
     * settings.
     */
    private void announceIfRecovered(BlockBoostRuntime runtime, Minecraft client) {
        List<String> notes = runtime.startupNotes();
        boolean warn = false;
        for (String n : notes) {
            if (n.startsWith("!!")) {
                warn = true;
                break;
            }
        }
        if (!warn && client.player != null && !settingsShown.getAndSet(true)) {
            // First run only: where the settings are, phrased for a phone where a
            // keybind may not be reachable without the launcher's keyboard.
            client.player.sendSystemMessage(Component.literal(
                    "[BlockBoost] settings: press O, or Mod Menu -> BlockBoost. Config: "
                            + runtime.configPath()));
            return;
        }
        if (client.player == null) {
            return;
        }
        for (String n : notes) {
            if (n.startsWith("!!")) {
                client.player.sendSystemMessage(Component.literal("[BlockBoost] " + n.substring(2).trim()));
            }
        }
        client.player.sendSystemMessage(Component.literal("[BlockBoost] config: " + runtime.configPath()));
    }

    // ── benchmark harness ─────────────────────────────────────────────────────
    private void parseBenchProperties(Settings settings) {
        String bench = System.getProperty("blockboost.bench");
        String exit = System.getProperty("blockboost.exitafter");
        if (bench != null) {
            try {
                benchFrames = Math.max(60, Long.parseLong(bench.trim()));
            } catch (NumberFormatException e) {
                BlockBoost.LOG.warn("[BlockBoost] ignoring blockboost.bench=\"{}\"", bench);
            }
        }
        if (exit != null) {
            try {
                long seconds = Math.max(5, Long.parseLong(exit.trim()));
                benchDeadlineNanos = System.nanoTime() + seconds * 1_000_000_000L;
            } catch (NumberFormatException e) {
                BlockBoost.LOG.warn("[BlockBoost] ignoring blockboost.exitafter=\"{}\"", exit);
            }
        }
    }

    private void finishBench(Minecraft client) {
        try {
            String path = services.writeBenchReport();
            BlockBoost.LOG.info("[BlockBoost] benchmark written: {}", path);
        } catch (RuntimeException e) {
            BlockBoost.LOG.warn("[BlockBoost] benchmark write failed", e);
        } finally {
            benchFrames = -1;
            if (benchDeadlineNanos != 0) {
                client.stop();
            }
        }
    }

    /** Called by the frame hook when the wall-clock bench limit expires. */
    public static boolean benchExpired(Minecraft client) {
        if (services == null) {
            return false;
        }
        if (benchDeadlineNanos != 0 && System.nanoTime() >= benchDeadlineNanos) {
            benchDeadlineNanos = 0;
            try {
                String path = services.writeBenchReport();
                BlockBoost.LOG.info("[BlockBoost] timed benchmark written: {}", path);
            } catch (RuntimeException e) {
                BlockBoost.LOG.warn("[BlockBoost] timed benchmark write failed", e);
            }
            // Deliberately a plain exit rather than Minecraft#stop(): the shutdown
            // path the game uses touches the renderer and the window, and in a
            // headless or driver-broken session that can hang a benchmark run.
            System.exit(0);
            return true;
        }
        return false;
    }

    private void shutdown() {
        try {
            if (adaptive != null) {
                adaptive.close();
            }
            if (services != null) {
                services.close();
            }
        } catch (RuntimeException e) {
            BlockBoost.LOG.warn("[BlockBoost] shutdown problem (harmless)", e);
        } finally {
            services = null;
            adaptive = null;
            BlockBoostRuntime.get().shutdown();
        }
    }
}
