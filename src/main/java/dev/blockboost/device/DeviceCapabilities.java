package dev.blockboost.device;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.management.OperatingSystemMXBean;

import dev.blockboost.BlockBoost;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Settings;

/**
 * The hardware/runtime snapshot every subsystem reads.
 *
 * <p>Collected once, at client start, plus a dynamic part (heap, load) that is
 * only refreshed when someone asks for a report. Nothing here runs per frame.
 *
 * <p>What is deliberately <b>not</b> here: SoC model, thermal state, GPU clock,
 * driver version, per-cluster core speeds. A Fabric mod launched from a phone
 * launcher cannot read those reliably, so BlockBoost reports "unknown" instead of
 * guessing, and derives its tier from what the JVM genuinely knows: core count,
 * heap ceiling, architecture and the GL strings the context will hand out.
 */
public final class DeviceCapabilities {

    private static volatile DeviceCapabilities instance;

    private final int cores;
    private final long maxHeapBytes;
    private final String osName;
    private final String osArch;
    private final String osVersion;
    private final String vmName;
    private final String vmVendor;
    private final String javaVersion;
    private final boolean mobileArch;
    private final boolean androidEnv;
    private final List<String> envHints;
    private final String jvmFlags;
    private final double systemLoad;

    private volatile RendererProfile rendererProfile = RendererProfile.UNKNOWN;
    private volatile GpuInfo gpu = GpuInfo.UNAVAILABLE;
    private volatile DeviceClass deviceClass = DeviceClass.UNKNOWN;
    private volatile String profileSource = "not resolved yet";
    private volatile boolean gpuAttempted;

    private DeviceCapabilities() {
        Runtime rt = Runtime.getRuntime();
        this.cores = Math.max(1, rt.availableProcessors());
        this.maxHeapBytes = rt.maxMemory();
        this.osName = prop("os.name", "unknown");
        this.osArch = prop("os.arch", "unknown");
        this.osVersion = prop("os.version", "unknown");
        this.vmName = prop("java.vm.name", "unknown");
        this.vmVendor = prop("java.vm.vendor", "unknown");
        this.javaVersion = prop("java.version", "unknown");
        this.mobileArch = osArch.toLowerCase(java.util.Locale.ROOT).contains("aarch64")
                || osArch.toLowerCase(java.util.Locale.ROOT).contains("arm");
        boolean android = osName.toLowerCase(java.util.Locale.ROOT).contains("android")
                || System.getenv("ANDROID_ROOT") != null
                || System.getenv("ANDROID_DATA") != null
                || System.getenv("TERMUX_VERSION") != null;
        this.androidEnv = android;
        this.envHints = collectHints();
        this.jvmFlags = String.join(" ", java.lang.management.ManagementFactory
                .getRuntimeMXBean().getInputArguments());
        double load = -1;
        try {
            java.lang.management.OperatingSystemMXBean os =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof OperatingSystemMXBean ext) {
                double v = ext.getCpuLoad();
                if (v >= 0) {
                    load = v;
                }
            }
            if (load < 0) {
                load = os.getSystemLoadAverage();
            }
        } catch (Throwable ignored) {
            // Not available on every JVM; absence is reported, not guessed.
        }
        this.systemLoad = load;
    }

    public static DeviceCapabilities detect() {
        DeviceCapabilities c = new DeviceCapabilities();
        instance = c;
        return c;
    }

    public static DeviceCapabilities instance() {
        DeviceCapabilities c = instance;
        return c == null ? new DeviceCapabilities() : c;
    }

    public void resolveFromSettings(Settings settings) {
        DeviceClass cls;
        String id = settings.string(ConfigSchema.ANDROID_DEVICE_CLASS);
        if ("auto".equals(id)) {
            cls = DeviceClass.infer(cores, maxHeapBytes, mobileArch || androidEnv);
            profileSource = "inferred";
        } else {
            cls = DeviceClass.fromId(id);
            profileSource = "manual";
        }
        this.deviceClass = cls;

        String manual = settings.string(ConfigSchema.ANDROID_RENDERER);
        if (!"auto".equals(manual)) {
            this.rendererProfile = RendererProfile.fromId(manual);
        } else {
            GpuInfo info = this.gpu;
            this.rendererProfile = info.available()
                    ? RendererProfile.classify(info.vendor(), info.renderer(), info.version())
                    : RendererProfile.UNKNOWN;
        }
    }

    /**
     * Called once from the render thread so the GL query happens with a current
     * context. Subsequent calls are free.
     */
    public void tryReadGpuOnce() {
        if (gpuAttempted) {
            return;
        }
        gpuAttempted = true;
        GpuInfo info = GLStringReader.readOnce();
        this.gpu = info;
        if (!info.available()) {
            BlockBoost.logWarn("GL capability query unavailable (" + info.failure()
                    + "); using the conservative renderer profile");
        }
    }

    /** Worker threads for a given usage, honouring the profile caps and config. */
    public int workerThreads(int requested, int hardCap, boolean bigLittle) {
        int base = requested <= 0 ? deviceClass.workerThreads : requested;
        int cap = Math.min(hardCap, Math.max(1, rendererProfile.maxRecommendedWorkers()));
        if (bigLittle && mobileArch) {
            // Efficiency cores are not free: leave them to the game and the driver.
            cap = Math.max(1, Math.min(cap, Math.max(1, cores / 2)));
        }
        return Math.max(1, Math.min(base, cap));
    }

    public int cores() {
        return cores;
    }

    public long maxHeapBytes() {
        return maxHeapBytes;
    }

    public DeviceClass deviceClass() {
        return deviceClass;
    }

    public RendererProfile rendererProfile() {
        return rendererProfile;
    }

    public GpuInfo gpu() {
        return gpu;
    }

    public String profileSource() {
        return profileSource;
    }

    public boolean mobileArch() {
        return mobileArch;
    }

    public boolean androidEnvironment() {
        return androidEnv;
    }

    public double systemLoad() {
        return systemLoad;
    }

    public long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return Math.max(0, rt.totalMemory() - rt.freeMemory());
    }

    public String heapSummary() {
        long mb = 1024 * 1024;
        Runtime rt = Runtime.getRuntime();
        return "heap " + (usedHeapBytes() / mb) + " MB used of " + (maxHeapBytes / mb)
                + " MB max (" + (rt.totalMemory() / mb) + " MB committed, " + (rt.freeMemory() / mb) + " MB free in pool)";
    }

    public List<String> envHints() {
        return List.copyOf(envHints);
    }

    /** Lines for the report; everything uncertain says so in words. */
    public List<String> reportLines() {
        List<String> out = new ArrayList<>();
        out.add("os=" + osName + " " + osVersion + "  arch=" + osArch + (mobileArch ? " (mobile ARM)" : ""));
        out.add("jvm=" + javaVersion + "  vm=" + vmName + "  vendor=" + vmVendor);
        out.add("cores reported to JVM=" + cores + "  system load="
                + (systemLoad >= 0 ? String.format(java.util.Locale.ROOT, "%.2f", systemLoad) : "unavailable"));
        out.add(heapSummary());
        out.add("device class=" + deviceClass.name() + " (" + profileSource + ")");
        out.add("renderer profile=" + rendererProfile.name() + " (" + rendererProfile.label() + ")"
                + (rendererProfile == RendererProfile.UNKNOWN ? " - UNVERIFIED, conservative defaults" : ""));
        out.add("gpu=" + gpu.describe());
        out.add("android environment=" + (androidEnv ? "detected" : "not detected"));
        for (String h : envHints) {
            out.add("  hint: " + h);
        }
        if (jvmFlags.contains("-Xmx")) {
            out.add("launcher heap flags: " + firstFlagWith("-Xmx"));
        } else {
            out.add("launcher heap flags: none -Xmx set (JVM default applies; BlockBoost never changes this)");
        }
        out.add("not readable from a Fabric mod, therefore never reported as a number: SoC model,"
                + " thermal throttling state, GPU clock, driver version, per-cluster core frequencies.");
        return out;
    }

    private String firstFlagWith(String needle) {
        for (String f : jvmFlags.split(" ")) {
            if (f.contains(needle)) {
                return f;
            }
        }
        return needle;
    }

    private static String prop(String key, String fallback) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? fallback : v;
        } catch (SecurityException e) {
            return fallback;
        }
    }

    /**
     * Launcher/translation hints from the environment only. Each entry is a raw
     * string the user can read; nothing here is turned into a capability claim.
     */
    private static List<String> collectHints() {
        List<String> hints = new ArrayList<>();
        addEnv(hints, "ANDROID_ROOT", "android root present");
        addEnv(hints, "ANDROID_DATA", "android data dir present");
        addEnv(hints, "TERMUX_VERSION", "termux environment");
        addEnv(hints, "PRIORITY", "scheduler priority set by launcher");
        String ldp = env("LD_PRELOAD");
        if (ldp != null) {
            hints.add("LD_PRELOAD=" + trim(ldp));
        }
        String glfw = prop("org.lwjgl.glfw.libname", null);
        if (glfw != null) {
            hints.add("org.lwjgl.glfw.libname=" + trim(glfw));
        }
        String libPath = prop("java.library.path", "");
        for (String marker : new String[]{"zalith", "foxglove", "mobileglues", "gl4es", "zink", "ltw", "minecraft"}) {
            if (libPath.toLowerCase(java.util.Locale.ROOT).contains(marker)) {
                hints.add("java.library.path mentions '" + marker + "'");
            }
        }
        for (String f : String.join(" ", java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments()).split(" ")) {
            String lower = f.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("mobileglues") || lower.contains("gl4es") || lower.contains("zink")
                    || lower.contains("ltw") || lower.startsWith("-XX:+Use") || lower.contains("maxgcpause")) {
                hints.add("jvm flag " + trim(f));
            }
        }
        return hints;
    }

    private static void addEnv(List<String> out, String name, String description) {
        if (env(name) != null) {
            out.add(description + " ($" + name + ")");
        }
    }

    private static String env(String name) {
        try {
            return System.getenv(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String trim(String s) {
        s = s.trim();
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    /** Best-effort, read-once, never fatal: used only for display. */
    public static String cpuInfoLine() {
        Path p = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(p)) {
            return "unavailable";
        }
        try {
            for (String line : Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.toLowerCase(java.util.Locale.ROOT).contains("hardware")
                        || line.toLowerCase(java.util.Locale.ROOT).contains("model name")) {
                    return trim(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            return "unreadable";
        }
        return "no hardware line";
    }
}
