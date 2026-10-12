package dev.blockboost.device;

import java.lang.reflect.Method;

/**
 * Reads GL_VENDOR / GL_RENDERER / GL_VERSION / GL_SHADING_LANGUAGE_VERSION and
 * the extension count through LWJGL, reflectively.
 *
 * <p>Reflection is the point: it must be safe to load this class on a launcher
 * that does not ship {@code org.lwjgl.opengl.GL11} at all (some Android launchers
 * substitute their own bindings). A {@link NoClassDefFoundError} from a static
 * import would have taken the whole mod down; here it degrades to
 * {@link GpuInfo#UNAVAILABLE}.
 *
 * <p>Only ever call {@link #readOnce()} while the game is rendering, i.e. with
 * the context current on the render thread. There is no retry loop: if the first
 * read fails, the answer is "unknown" for the session.
 */
public final class GLStringReader {

    // Fixed OpenGL enum values; independent of any LWJGL version's constants.
    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;
    private static final int GL_EXTENSIONS = 0x1F03;
    private static final int GL_SHADING_LANGUAGE_VERSION = 0x8B8C;

    private static volatile boolean tried;
    private static volatile GpuInfo cached = GpuInfo.UNAVAILABLE;

    private GLStringReader() {
    }

    public static GpuInfo cached() {
        return cached;
    }

    public static GpuInfo readOnce() {
        if (tried) {
            return cached;
        }
        synchronized (GLStringReader.class) {
            if (tried) {
                return cached;
            }
            cached = doRead();
            tried = true;
            return cached;
        }
    }

    /** Test seam: a fake binding can be installed to exercise the parse path. */
    static GpuInfo doRead() {
        try {
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
            Method getString = gl11.getMethod("glGetString", int.class);
            String vendor = str(getString, GL_VENDOR);
            String renderer = str(getString, GL_RENDERER);
            String version = str(getString, GL_VERSION);
            String glsl = str(getString, GL_SHADING_LANGUAGE_VERSION);
            int count = -1;
            String ext = str(getString, GL_EXTENSIONS);
            if (ext != null) {
                count = ext.isBlank() ? 0 : ext.trim().split("\s+").length;
            }
            boolean ok = vendor != null || renderer != null || version != null;
            if (!ok) {
                return new GpuInfo(null, null, null, null, -1, false,
                        "glGetString returned nothing (no current context?)");
            }
            return new GpuInfo(safe(vendor), safe(renderer), safe(version), safe(glsl), count, true, null);
        } catch (Throwable t) {
            // ClassNotFound / NoSuchMethod / IllegalStateException(no context) all land here.
            return new GpuInfo(null, null, null, null, -1, false,
                    t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
        }
    }

    private static String safe(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > 220 ? t.substring(0, 220) : t;
    }

    private static String str(Method getString, int pname) {
        try {
            Object o = getString.invoke(null, pname);
            return o == null ? null : o.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
