package dev.blockboost.device;

/**
 * What the GL context itself reported. Everything here is read once, on the
 * render thread, through LWJGL by reflection so that the class still loads when
 * a launcher swaps the GL bindings out.
 *
 * <p>{@link #available()} false means "we could not ask", and every consumer has
 * to treat that as unknown rather than as "no extensions".
 */
public record GpuInfo(String vendor, String renderer, String version, String glsl, int extensionCount,
                      boolean fromContext, String failure) {

    public static final GpuInfo UNAVAILABLE =
            new GpuInfo(null, null, null, null, -1, false, "GL context was not reachable");

    public boolean available() {
        return fromContext && renderer != null;
    }

    public boolean supports(String... needles) {
        String hay = ((vendor == null ? "" : vendor) + " " + (renderer == null ? "" : renderer) + " "
                + (version == null ? "" : version) + " " + (glsl == null ? "" : glsl))
                .toLowerCase(java.util.Locale.ROOT);
        for (String n : needles) {
            if (hay.contains(n.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public boolean looksLikeOpenGLES() {
        String v = version == null ? "" : version.toLowerCase(java.util.Locale.ROOT);
        return v.contains("es 2.") || v.contains("es 3.");
    }

    public int glMajorVersion() {
        String v = version == null ? "" : v0(version);
        int dot = v.indexOf('.');
        try {
            return dot > 0 ? Integer.parseInt(v.substring(0, dot).trim()) : Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

private static String v0(String version) {
    int i = version.indexOf(' ');
    String head = i > 0 ? version.substring(0, i) : version;
    int dash = head.indexOf('-');
    return dash > 0 ? head.substring(0, dash) : head;
}

    public String describe() {
        if (!available()) {
            return "unavailable (" + failure + ")";
        }
        return (renderer == null ? "?" : renderer) + " | " + (vendor == null ? "?" : vendor)
                + " | " + (version == null ? "?" : version)
                + (extensionCount >= 0 ? " | " + extensionCount + " extensions" : "");
    }
}
