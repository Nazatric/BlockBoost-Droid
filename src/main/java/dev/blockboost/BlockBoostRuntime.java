package dev.blockboost;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.blockboost.compat.ModCompatibility;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.ConfigStore;
import dev.blockboost.core.FeatureRegistry;
import dev.blockboost.device.DeviceCapabilities;

/**
 * Shared runtime state for the mod: configuration, capabilities, the feature
 * registry and the single writer thread used for saving.
 *
 * <p>Common code (this class) is kept free of Minecraft client types so the
 * mod still initializes on a dedicated server and so the unit tests can drive
 * the whole config lifecycle without a game.
 */
public final class BlockBoostRuntime {

    private static final BlockBoostRuntime INSTANCE = new BlockBoostRuntime();

    private volatile ConfigStore.LoadResult loadResult;
    private volatile Settings settings;
    private volatile FeatureRegistry registry;
    private volatile ModCompatibility compatibility;
    private volatile DeviceCapabilities device;
    private volatile Path configPath;
    private volatile ExecutorService saveThread;
    private volatile boolean started;
    private volatile boolean clientSide;
    private volatile long lastSaveMs;
    private volatile int saveCount;
    private volatile List<String> startupNotes = List.of();

    private BlockBoostRuntime() {
    }

    public static BlockBoostRuntime get() {
        return INSTANCE;
    }

    /**
     * Loads configuration and prepares the registries. Safe to call once; a
     * second call is a no-op so a client entrypoint cannot double-initialize.
     */
    public synchronized void start(boolean clientSide) {
        if (started) {
            this.clientSide |= clientSide;
            return;
        }
        this.clientSide = clientSide;
        this.configPath = ConfigStore.defaultPath();
        this.loadResult = ConfigStore.load(configPath);
        this.settings = loadResult.settings();
        this.registry = new FeatureRegistry();
        FeatureRegistry.install(registry);
        this.compatibility = new ModCompatibility();
        this.compatibility.scan();
        this.device = DeviceCapabilities.detect();
        this.startupNotes = summarizeLoad(loadResult);

        for (String note : startupNotes) {
            if (note.startsWith("!!")) {
                BlockBoost.LOG.warn("[BlockBoost] {}", note.substring(2).trim());
            } else {
                BlockBoost.logInfo(note);
            }
        }

        this.saveThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "blockboost-config-saver");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        this.settings.onChanged(this::requestSave);
        this.started = true;
    }

    private static List<String> summarizeLoad(ConfigStore.LoadResult r) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (r.fromVersion() != BlockBoost.CONFIG_VERSION) {
            out.add("config loaded from version " + r.fromVersion() + " -> current " + BlockBoost.CONFIG_VERSION
                    + (r.migrated() ? " (migrated)" : ""));
        }
        for (String n : r.notes()) {
            out.add("   " + n);
        }
        for (String c : r.clamped()) {
            out.add("!! clamped " + c);
        }
        if (r.recovered()) {
            out.add("!! config recovered from defaults after a read/parse failure");
        }
        out.add("settings: " + ConfigSchema.count() + " keys, preset=" + r.settings().presetName());
        return List.copyOf(out);
    }

    /**
     * Debounced, off-thread save. Slider dragging on a phone generates dozens of
     * changes per second and storage writes are comparatively brutal there, so
     * writes are coalesced and never happen on the client thread.
     */
    public void requestSave() {
        if (!started || saveThread == null || saveThread.isShutdown()) {
            return;
        }
        long now = System.currentTimeMillis();
        long debounce = Math.max(50, settings.integer(ConfigSchema.ADV_AUTOSAVE_MS));
        if (now - lastSaveMs < debounce) {
            return;
        }
        lastSaveMs = now;
        final Settings snapshot = settings;
        saveThread.submit(() -> {
            try {
                ConfigStore.save(configPath, snapshot);
                saveCount++;
            } catch (Exception e) {
                BlockBoost.LOG.error("[BlockBoost] could not write {} - settings stay in memory for this session",
                        configPath, e);
            }
        });
    }

    /** Blocking save used at shutdown and by the "save now" button. */
    public void saveNow() {
        if (settings == null) {
            return;
        }
        try {
            ConfigStore.save(configPath == null ? ConfigStore.defaultPath() : configPath, settings);
            saveCount++;
        } catch (Exception e) {
            BlockBoost.LOG.error("[BlockBoost] final config save failed", e);
        }
    }

    public void shutdown() {
        saveNow();
        ExecutorService ex = saveThread;
        if (ex != null) {
            ex.shutdown();
            try {
                ex.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        started = false;
    }

    public Settings settings() {
        return settings;
    }

    public FeatureRegistry registry() {
        return registry;
    }

    public ModCompatibility compatibility() {
        return compatibility;
    }

    public DeviceCapabilities device() {
        return device;
    }

    public Path configPath() {
        return configPath;
    }

    public ConfigStore.LoadResult loadResult() {
        return loadResult;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isClientSide() {
        return clientSide;
    }

    public int saveCount() {
        return saveCount;
    }

    public List<String> startupNotes() {
        return startupNotes;
    }
}
