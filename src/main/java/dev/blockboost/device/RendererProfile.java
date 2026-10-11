package dev.blockboost.device;

/**
 * Which OpenGL path the client is actually drawing through.
 *
 * <p>This is a heuristic label, not a promise: it changes how aggressive
 * BlockBoost is allowed to be, it never upgrades a driver and never claims a
 * capability the GPU does not have. When the renderer string cannot be read,
 * {@link #UNKNOWN} behaves like the most conservative entry on purpose.
 */
public enum RendererProfile {
    /** MobileGlues: desktop GL calls translated onto OpenGL ES. */
    MOBILE_GLUES(true, false, 2, "MobileGlues translation layer"),
    /** GL4ES: desktop GL 2/3 emulation over ES 1/2. Very old device support. */
    GL4ES(true, false, 1, "GL4ES emulation layer"),
    /** Like The Windows (LTW) style ANGLE/GL-on-Vulkan translation. */
    LTW(true, false, 2, "LTW translation layer"),
    /** Zink: OpenGL on top of Vulkan. */
    ZINK(true, true, 3, "Zink (OpenGL over Vulkan)"),
    /** Native desktop GL, no translation in between. */
    DESKTOP_GL(false, true, 4, "desktop OpenGL"),
    /** Could not be determined; conservative behaviour. */
    UNKNOWN(true, false, 2, "unknown renderer");

    private final boolean translating;
    private final boolean vsyncReliable;
    private final int maxRecommendedWorkers;
    private final String label;

    RendererProfile(boolean translating, boolean vsyncReliable, int maxRecommendedWorkers, String label) {
        this.translating = translating;
        this.vsyncReliable = vsyncReliable;
        this.maxRecommendedWorkers = maxRecommendedWorkers;
        this.label = label;
    }

    /** True when every GL call crosses a translation layer, so CPU-side savings matter more than GL tricks. */
    public boolean translating() {
        return translating;
    }

    /**
     * Whether a swap-interval change is expected to be honoured. On translated
     * stacks it frequently is not, so BlockBoost keeps its own cap active
     * instead of trusting vsync alone.
     */
    public boolean vsyncReliable() {
        return vsyncReliable;
    }

    public int maxRecommendedWorkers() {
        return maxRecommendedWorkers;
    }

    public String label() {
        return label;
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static RendererProfile fromId(String id) {
        if (id == null) {
            return UNKNOWN;
        }
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "mobileglues", "mobile-glues", "glues" -> MOBILE_GLUES;
            case "gl4es" -> GL4ES;
            case "ltw" -> LTW;
            case "zink" -> ZINK;
            case "desktop_gl", "desktop", "opengl" -> DESKTOP_GL;
            default -> UNKNOWN;
        };
    }

    /** Best-effort classification of a raw GL_RENDERER / GL_VENDOR pair. */
    public static RendererProfile classify(String vendor, String renderer, String version) {
        String hay = ((vendor == null ? "" : vendor) + " " + (renderer == null ? "" : renderer)
                + " " + (version == null ? "" : version)).toLowerCase(java.util.Locale.ROOT);
        if (hay.contains("mobileglues") || hay.contains("mobile glues")) {
            return MOBILE_GLUES;
        }
        if (hay.contains("gl4es")) {
            return GL4ES;
        }
        if (hay.contains("ltw") || hay.contains("like the windows")) {
            return LTW;
        }
        if (hay.contains("zink")) {
            return ZINK;
        }
        // An ES context reached through a translator still reports "ES" here.
        if (hay.contains("opengl es") || hay.contains("es 3.") || hay.contains("es 2.")) {
            return UNKNOWN;
        }
        if (hay.contains("mali") || hay.contains("adreno") || hay.contains("powervr") || hay.contains("vivante")) {
            // Mobile GPU behind a plain (untranslated) ES context.
            return UNKNOWN;
        }
        if (hay.isBlank()) {
            return UNKNOWN;
        }
        return DESKTOP_GL;
    }
}
