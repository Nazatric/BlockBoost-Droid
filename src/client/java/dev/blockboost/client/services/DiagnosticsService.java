package dev.blockboost.client.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.client.ClientServices;
import dev.blockboost.compat.ModCompatibility;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;
import dev.blockboost.core.FeatureRegistry;
import dev.blockboost.core.FeatureRuntimeContext;
import dev.blockboost.device.DeviceCapabilities;

/**
 * Assembles everything the user can see about "what is this mod actually doing".
 *
 * <p>Everything here is measured, never estimated. When a number cannot be
 * obtained the line says so, because a report that invents a GPU timing is worse
 * than one that admits it has none. No telemetry, no network, no player data: the
 * only output is a file inside the game directory.
 */
public final class DiagnosticsService implements FeatureRuntimeContext.FeatureDiagnostics {

    private final Settings settings;
    private final DeviceCapabilities device;
    private final ModCompatibility mods;
    private final ClientServices services;
    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final List<String> notes = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicLong reportSeq = new AtomicLong();
    private volatile long lastReportAt;
    private volatile String lastReportPath = "(not yet written)";

    public DiagnosticsService(Settings settings, DeviceCapabilities device, ModCompatibility mods,
                              ClientServices services) {
        this.settings = settings;
        this.device = device;
        this.mods = mods;
        this.services = services;
    }

    @Override
    public void counter(String name, long value) {
        if (!settings.bool(ConfigSchema.DIAG_ENABLED)) {
            return;
        }
        counters.put(name, value);
    }

    @Override
    public void note(String message) {
        if (notes.size() > 64) {
            notes.remove(0);
        }
        notes.add(message);
    }

    public long counterValue(String name) {
        return counters.getOrDefault(name, 0L);
    }

    public Map<String, Long> counters() {
        return new LinkedHashMap<>(counters);
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    public String lastReportPath() {
        return lastReportPath;
    }

    public long reportCount() {
        return reportSeq.get();
    }

    /** The lines shown on the diagnostics page, refreshed at UI rate only. */
    public List<String> summaryLines() {
        List<String> out = new ArrayList<>();
        FrameSampler frames = services.frames();
        out.add("fps " + frames.hudFpsText() + "   frame " + frames.hudFrameText()
                + "   1% low " + (frames.low1PercentFps() < 0 ? "n/a"
                : (int) Math.round(frames.low1PercentFps()) + " fps")
                + "   worst " + String.format(java.util.Locale.ROOT, "%.1f ms", frames.worstMs()));
        out.add("samples " + frames.samples() + " / window   missed target " + frames.missedTargetFrames()
                + " frames   trend " + String.format(java.util.Locale.ROOT, "%+.1f%%", frames.trendPercent()));
        out.add(services.gc().hudLine());
        out.add(device.heapSummary());
        String chunk = services.chunkStats().hudLine();
        if (chunk != null) {
            out.add(chunk);
        }
        var pools = services.pools();
        out.add("blockboost workers: pools=" + pools.pools().size() + " queued=" + pools.queueTotal()
                + " dropped=" + pools.droppedTotal() + (pools.isPaused() ? " (paused: idle)" : ""));
        FeatureRegistry registry = BlockBoostRuntime.get().registry();
        if (registry != null) {
            out.add("features active: " + registry.statusLines().stream().filter(l -> l.contains("active")).count()
                    + "/" + registry.registeredCount() + "   failures: " + registry.failureCount());
        }
        return out;
    }

    /** The full text report. Written to disk by {@link ReportWriter}. */
    public List<String> fullReport() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        lastReportAt = now;
        out.add("BlockBoost - Android Performance Engine");
        out.add("version " + modVersion() + "   report " + reportSeq.incrementAndGet()
                + "   generated " + new java.util.Date(now));
        out.add("minecraft/fabric: " + gameInfo());
        out.add("");
        out.add("== performance ==");
        out.addAll(services.frames().snapshotStats() == null ? List.of() : List.of(
                "frame window: " + services.frames().summarizeWindow()));
        out.addAll(reportFramePercentiles());
        out.add("lifetime mean frame: " + String.format(java.util.Locale.ROOT, "%.2f ms", services.frames().lifetimeMeanMs()));
        out.add("longest frame seen this session: "
                + String.format(java.util.Locale.ROOT, "%.1f ms", services.frames().longestEverMs()));
        out.add("");
        out.add("== device & renderer ==");
        out.addAll(device.reportLines());
        out.add("cpuinfo line: " + DeviceCapabilities.cpuInfoLine());
        out.add("");
        out.add("== memory & gc ==");
        out.addAll(services.gc().reportLines());
        out.add("");
        out.add("== chunk pipeline ==");
        out.addAll(services.chunkStats().reportLines());
        out.add("");
        out.add("== features ==");
        FeatureRegistry registry = BlockBoostRuntime.get().registry();
        if (registry != null) {
            out.addAll(registry.statusLines());
            out.add("-- live state --");
            out.addAll(registry.liveLines());
        } else {
            out.add("(registry not installed)");
        }
        out.add("");
        out.add("== compatibility ==");
        out.addAll(mods.reportLines());
        out.add("");
        out.add("== configuration ==");
        for (String line : configDump()) {
            out.add(line);
        }
        out.add("");
        out.add("== counters ==");
        if (counters.isEmpty()) {
            out.add("(no per-feature counters recorded)");
        } else {
            counters.forEach((k, v) -> out.add(String.format("  %-42s %d", k, v)));
        }
        out.add("");
        out.add("== notes ==");
        out.add("config: " + (BlockBoostRuntime.get().configPath() == null ? "?" : BlockBoostRuntime.get().configPath()));
        for (String n : startupNotes()) {
            out.add("  " + n);
        }
        for (String n : notes) {
            out.add("  " + n);
        }
        out.add("");
        out.add("No data left this device. This file was written by BlockBoost into the game directory;");
        out.add("there is no telemetry, no network access and no player information in it.");
        return out;
    }

    private List<String> reportFramePercentiles() {
        List<String> out = new ArrayList<>();
        FrameSampler f = services.frames();
        if (f.samples() < 8) {
            out.add("(frame percentiles need at least 8 samples; only " + f.samples() + " available)");
            return out;
        }
        out.add(String.format(java.util.Locale.ROOT, "frame time percentiles: p50 %.2f  p75 %.2f  p95 %.2f  p99 %.2f ms",
                f.percentileMs(50), f.percentileMs(75), f.percentileMs(95), f.percentileMs(99)));
        double low = f.low1PercentFps();
        out.add("1% low fps: " + (low < 0 ? "insufficient data" : String.format(java.util.Locale.ROOT, "%.1f", low)));
        return out;
    }

    private List<String> startupNotes() {
        List<String> out = new ArrayList<>();
        try {
            out.addAll(BlockBoostRuntime.get().startupNotes());
        } catch (RuntimeException e) {
            out.add("(startup notes unavailable: " + e.getMessage() + ")");
        }
        return out;
    }

    private List<String> configDump() {
        List<String> out = new ArrayList<>();
        if (settings == null) {
            return out;
        }
        settings.snapshot().forEach((k, v) -> out.add(String.format("  %-42s %s", k, v)));
        return out;
    }

    private static String modVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(BlockBoost.MOD_ID)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String gameInfo() {
        try {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            String mc = loader.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
            String fl = loader.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("?");
            boolean fabricApi = loader.isModLoaded("fabric-api");
            boolean dev = loader.isDevelopmentEnvironment();
            return "Minecraft " + mc + ", Fabric Loader " + fl + ", Fabric API "
                    + (fabricApi ? "present" : "ABSENT") + (dev ? ", development environment" : "")
                    + ", Java " + System.getProperty("java.version");
        } catch (RuntimeException e) {
            return "unavailable (" + e.getMessage() + ")";
        }
    }

    public String hudText(boolean advanced) {
        StringBuilder sb = new StringBuilder();
        FrameSampler f = services.frames();
        sb.append(f.hudFpsText()).append("  ").append(f.hudFrameText()).append(" ms");
        double low = f.low1PercentFps();
        if (low > 0) {
            sb.append("\n1% low ").append((int) Math.round(low)).append(" fps");
        }
        if (!advanced) {
            return sb.toString();
        }
        sb.append('\n').append(services.gc().hudLine());
        String chunk = services.chunkStats().hudLine();
        if (chunk != null) {
            sb.append('\n').append(chunk);
        }
        Map<String, Long> c = counters;
        sb.append("\nculled e=").append(c.getOrDefault("entity.culled", 0L))
                .append(" be=").append(c.getOrDefault("blockentity.culled", 0L))
                .append("  kept e=").append(c.getOrDefault("entity.visible", 0L))
                .append(" be=").append(c.getOrDefault("blockentity.visible", 0L));
        sb.append("\nparticles dropped=").append(c.getOrDefault("particle.dropped", 0L));
        sb.append('\n').append(device.heapSummary());
        var a = services.adaptive();
        if (a != null) {
            String line = a.describe();
            if (line != null) {
                sb.append('\n').append(line);
            }
        }
        return sb.toString();
    }

    public void markReportWritten(String path) {
        this.lastReportPath = path;
    }
}
