package dev.blockboost.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.client.ClientServices;
import dev.blockboost.client.HotState;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.device.DeviceCapabilities;
import dev.blockboost.config.Settings;

/**
 * The optional performance overlay.
 *
 * <p>It is written to be the cheapest thing on screen: no widget objects, no layout
 * pass, one fill for the backing panel and one text call per line, all strings built
 * at most four times a second (the sampler is read, never recomputed here). A
 * diagnostic overlay that costs 2 ms defeats its own purpose, especially on a phone
 * where the CPU is the constraint.
 *
 * <p>Everything shown is a measurement or a state we already track — culled counts,
 * queue depth, GC pauses, the resolved renderer profile. Nothing here is an estimate
 * dressed up as a number, and there is no graph: a graph needs a pose transform, and
 * pose transforms on a GL translator are not something a HUD should risk.
 */
public final class PerformanceHud {

    private static long lastBuildMs;
    private static List<String> cached = List.of("BlockBoost");
    private static int cachedWidth;

    private PerformanceHud() {
    }

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker tracker, ClientServices services) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || services == null) {
            return;
        }
        Settings settings = services.settings();
        String corner = settings.enumValue(ConfigSchema.DIAG_HUD_CORNER);
        if (corner == null) {
            corner = "top_left";
        }
        if ("hidden".equals(corner) || client.options == null) {
            return;
        }
        int scale = Math.max(50, Math.min(200, settings.integer(ConfigSchema.DIAG_HUD_SCALE)));
        Font font = client.font;
        if (font == null) {
            return;
        }
        boolean advanced = settings.is(ConfigSchema.DIAG_HUD, "advanced") || HotState.hudAdvanced;
        rebuild(services, advanced, client);

        int lineHeight = Math.max(9, Math.round(font.lineHeight * (scale / 100.0f)));
        int pad = Math.max(2, Math.round(3 * (scale / 100.0f)));
        int width = cachedWidth + pad * 2;
        int height = cached.size() * lineHeight + pad * 2;
        int screenW = graphicsWidth(client);
        int screenH = graphicsHeight(client);
        int x = corner.endsWith("right") ? screenW - width - 4 : 4;
        int y = corner.startsWith("bottom") ? screenH - height - 4 : 4;

        graphics.fill(x, y, x + width, y + height, 0xB0000000);
        int textY = y + pad;
        for (int i = 0; i < cached.size(); i++) {
            String line = cached.get(i);
            graphics.text(font, line, x + pad, textY, i == 0 ? 0xFFE0FFC0 : 0xFFFFFFFF, true);
            textY += lineHeight;
        }
    }

    private static void rebuild(ClientServices services, boolean advanced, Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastBuildMs < 250L) {
            return;
        }
        lastBuildMs = now;
        List<String> lines = new ArrayList<>(10);
        var frames = services.frames();
        lines.add("BlockBoost " + BlockBoost.version());
        lines.add(frames.hudFpsText() + "  " + frames.hudFrameText());
        if (advanced) {
            lines.add("p95 " + fmt(frames.percentileMs(95)) + " ms   worst " + fmt(frames.worstMs()) + " ms");
            String gc = services.gc().hudLine();
            if (gc != null && !gc.isBlank()) {
                lines.add(gc);
            }
            lines.add(services.device() == null ? "heap unknown" : services.device().heapSummary());
            lines.add("chunk " + services.chunkStats().hudLine());
            lines.add("cull " + HotState.culledEntities + " ent / " + HotState.culledBlockEntities + " be"
                    + "  parts " + HotState.suppressedParticles);
            String queue = services.poolSummary();
            if (queue != null && !queue.isBlank()) {
                lines.add(queue);
            }
        }
        DeviceCapabilities device = services.device();
        if (device != null) {
            lines.add("renderer " + device.rendererProfile() + " (" + device.profileSource() + ")");
        }
        if (services.adaptive() != null) {
            lines.add(services.adaptive().hudLine());
        }
        int widest = 0;
        Font font = client.font;
        for (String line : lines) {
            if (font != null) {
                widest = Math.max(widest, font.width(line));
            }
        }
        cached = List.copyOf(lines);
        cachedWidth = Math.max(60, widest);
    }

    /**
     * GUI-space size, derived from the framebuffer and the window's own scale factor.
     * Reading {@code getGuiScaledWidth()} would be nicer, but the framebuffer pair is
     * what the window layer guarantees, and dividing by a scale we did not choose keeps
     * the overlay aligned with vanilla HUD elements on any launcher DPI setting.
     */
    private static int graphicsWidth(Minecraft client) {
        return (int) (client.getWindow().getFramebufferWidth() / (double) client.getWindow().getGuiScale());
    }

    private static int graphicsHeight(Minecraft client) {
        return (int) (client.getWindow().getFramebufferHeight() / (double) client.getWindow().getGuiScale());
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(java.util.Locale.ROOT, "%.1f", v) : "-";
    }

    /** Used by the report and the keybind help line. */
    public static String helpLine() {
        return "BlockBoost hud: " + BlockBoostRuntime.get().configPath();
    }
}
