package dev.blockboost.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.client.ClientServices;
import dev.blockboost.client.HotState;
import dev.blockboost.client.features.VanillaOptions;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Preset;
import dev.blockboost.config.SettingKey;
import dev.blockboost.config.Settings;
import dev.blockboost.util.RollingStats;

/**
 * The whole of BlockBoost's user interface, in one screen.
 *
 * <p><b>Why it is built this way.</b> Every widget here is a vanilla {@link Button} or
 * {@link EditBox} created once in {@link #init()}, and every piece of text and every
 * progress bar is drawn in {@link #extractRenderState} from strings prepared during
 * {@link #refresh()}. That split is deliberate:
 *
 * <ul>
 *   <li>Nothing is allocated per frame. A settings screen that builds components in its
 *       draw loop is a garbage generator, and this mod's whole argument is about not
 *       adding work to the render thread - including its own UI.</li>
 *   <li>The widget set is the smallest one that exists. Minecraft has renamed slider and
 *       cycle-button constructors more than once, and a mod whose settings screen fails
 *       to construct is a mod that cannot be configured at all. Buttons with a message
 *       and a bounds have been stable for a decade.</li>
 *   <li>Layout is computed here rather than inherited from a layout container, because
 *       26.1.2 has no {@code net.minecraft.client.gui.layout} package at all - the dump
 *       says so - so any "modern" layout class would be a guess.</li>
 * </ul>
 *
 * <p><b>Touch first.</b> Rows are 24-30 px tall with the whole row as a hit target, the
 * fine controls are at the right edge where a thumb reaches, and the description of every
 * setting sits permanently visible under its row instead of hiding in a tooltip a finger
 * cannot hover. Section tabs are one tap apart, and a tap on a row opens a detail screen
 * with the full explanation, the side effects and the apply/reset actions, so nothing that
 * matters requires precision.
 */
public final class BlockBoostSettingsScreen extends Screen {

    /** Tabs, in the order a person should meet them. */
    private enum Tab {
        OVERVIEW("Performance overview",
                "What is running, what was detected, and what it costs.",
                new SettingKey.Category[0]),
        SMART("Smart optimization",
                "Defaults chosen from your device's actual limits, and the presets built on them.",
                new SettingKey.Category[0]),
        RENDERING("Rendering & visuals",
                "Frame submission, culling and particles. Everything here trades cost, not content.",
                new SettingKey.Category[] {SettingKey.Category.RENDERING, SettingKey.Category.CULLING,
                        SettingKey.Category.PARTICLES}),
        CHUNKS("Chunks & world",
                "Background chunk work and the CPU that serves it.",
                new SettingKey.Category[] {SettingKey.Category.CHUNKS, SettingKey.Category.CPU}),
        MEMORY("Memory & stability",
                "Caches, allocation pressure and the switches that make the mod back off.",
                new SettingKey.Category[] {SettingKey.Category.MEMORY, SettingKey.Category.COMPAT}),
        ADVANCED("Advanced",
                "The knobs that only matter when something is being investigated.",
                new SettingKey.Category[] {SettingKey.Category.ADVANCED}),
        DIAGNOSTICS("Diagnostics",
                "The overlay, sampling and the report this project asks you to paste with a bug.",
                new SettingKey.Category[] {SettingKey.Category.DIAGNOSTICS, SettingKey.Category.ADAPTIVE});

        final String title;
        final String blurb;
        final SettingKey.Category[] categories;

        Tab(String title, String blurb, SettingKey.Category[] categories) {
            this.title = title;
            this.blurb = blurb;
            this.categories = categories;
        }
    }

    private static final Tab[] TABS = Tab.values();
    private static final int MAX_ROWS = 14;
    private static final int ROW_BODY = 20;
    private static final int ROW_NOTE = 11;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_DIM = 0xFF9A9A9A;
    private static final int COLOR_VALUE = 0xFFBFF0A0;
    private static final int COLOR_WARN = 0xFFFFC46B;
    private static final int COLOR_PANEL = 0x80101010;
    private static final int COLOR_BAR = 0xFF57A64A;
    private static final int COLOR_BAR_BACK = 0x60000000;

    private final BlockBoostRuntime runtime = BlockBoostRuntime.get();

    private final Button[] tabButtons = new Button[TABS.length];
    private final Button[] rowBody = new Button[MAX_ROWS];
    private final Button[] rowMinus = new Button[MAX_ROWS];
    private final Button[] rowPlus = new Button[MAX_ROWS];
    private final Button[] rowInfo = new Button[MAX_ROWS];
    private final Button[] presetButtons = new Button[Preset.ALL.size()];

    private Button clearSearch;
    private Button reportButton;
    private Button overlayButton;
    private Button autoButton;
    private Button resetTabButton;
    private Button pageBack;
    private Button pageForward;
    private Button doneButton;
    private EditBox search;

    private Tab tab = Tab.OVERVIEW;
    private int page;
    private final Screen parent;
    private String filter = "";
    private boolean restartPending;

    /** Prepared during refresh(), read during draw: never computed per frame. */
    private List<SettingKey> visible = List.of();
    private String[] rowLabel = new String[MAX_ROWS];
    private String[] rowValue = new String[MAX_ROWS];
    private String[] rowNote = new String[MAX_ROWS];
    private double[] rowFraction = new double[MAX_ROWS];
    private boolean[] rowHasBar = new boolean[MAX_ROWS];
    private String[] headerLines = {"BlockBoost", ""};
    private List<String> overviewLines = List.of();
    private List<String> smartLines = List.of();
    private String pageLabel = "";
    private int rowsShown;
    private int rowsTop;
    private int rowsLeft;
    private int rowsWidth;
    private int rowStride;

    private BlockBoostSettingsScreen(Screen parent) {
        super(Component.literal("BlockBoost"));
        this.parent = parent;
    }

    /**
     * Entry point for Mod Menu (which hands back the screen to return to) and for the key
     * bind. The key-bind path passes null because reading the game's current screen field
     * is not something this project has verified against 26.1.2, and closing to the game is
     * the right answer from a hot key anyway.
     */
    public static Screen create(Screen parent) {
        return new BlockBoostSettingsScreen(parent);
    }

    // ── construction ────────────────────────────────────────────────────────
    @Override
    protected void init() {
        int margin = 6;
        int width = this.width - margin * 2;

        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            // Tabs are laid out in init() and resized in refresh(): the row of seven has
            // to fit any width, including a phone at GUI scale 4.
            tabButtons[i] = Button.builder(Component.literal(""), b -> selectTab(index))
                    .bounds(0, 0, 10, ROW_BODY).build();
            this.addRenderableWidget(tabButtons[i]);
        }

        for (int i = 0; i < MAX_ROWS; i++) {
            final int index = i;
            rowBody[i] = Button.builder(Component.literal(""), b -> openRow(index)).bounds(0, 0, 10, ROW_BODY).build();
            rowMinus[i] = Button.builder(Component.literal("-"), b -> adjust(index, -1))
                    .bounds(0, 0, 22, ROW_BODY).build();
            rowPlus[i] = Button.builder(Component.literal("+"), b -> adjust(index, 1))
                    .bounds(0, 0, 22, ROW_BODY).build();
            rowInfo[i] = Button.builder(Component.literal("?"), b -> openRow(index))
                    .bounds(0, 0, 22, ROW_BODY).build();
            this.addRenderableWidget(rowBody[i]);
            this.addRenderableWidget(rowMinus[i]);
            this.addRenderableWidget(rowPlus[i]);
            this.addRenderableWidget(rowInfo[i]);
        }

        List<Preset> presets = Preset.ALL;
        for (int i = 0; i < presets.size(); i++) {
            final Preset preset = presets.get(i);
            presetButtons[i] = Button.builder(Component.literal(shortPresetName(preset)), b -> confirmPreset(preset))
                    .bounds(0, 0, 10, ROW_BODY).build();
            presetButtons[i].setTooltip(Tooltip.create(Component.literal(
                    preset.tooltipKey() + "\n\nTouches " + preset.patch().size() + " settings.")));
            this.addRenderableWidget(presetButtons[i]);
        }

        search = new EditBox(this.font, Math.max(60, 120), 18, Component.translatable("blockboost.screen.search"));
        search.setMaxLength(48);
        search.setHint(Component.literal("filter"));
        search.setResponder(value -> {
            filter = value == null ? "" : value.toLowerCase(Locale.ROOT);
            page = 0;
            refresh();
        });
        this.addRenderableWidget(search);
        clearSearch = Button.builder(Component.literal("x"), b -> {
            search.setValue("");
            filter = "";
            page = 0;
            refresh();
        }).bounds(0, 0, 20, 18).build();
        this.addRenderableWidget(clearSearch);

        reportButton = Button.builder(Component.literal("write report"), b -> writeReport())
                .bounds(0, 0, 80, ROW_BODY).build();
        overlayButton = Button.builder(Component.literal(""), b -> cycleOverlay())
                .bounds(0, 0, 80, ROW_BODY).build();
        autoButton = Button.builder(Component.literal("apply recommended"), b -> applyRecommended())
                .bounds(0, 0, 100, ROW_BODY).build();
        autoButton.setTooltip(Tooltip.create(Component.literal(
                "Chooses settings from the core count, heap ceiling and GL stack this run can actually"
                        + " see, and lists every change it made. Never picks a frame cap or a vsync state.")));
        resetTabButton = Button.builder(Component.translatable("blockboost.screen.reset_section"), b -> confirmResetTab())
                .bounds(0, 0, 80, ROW_BODY).build();
        pageBack = Button.builder(Component.literal("<"), b -> {
            page = Math.max(0, page - 1);
            refresh();
        }).bounds(0, 0, 20, ROW_BODY).build();
        pageForward = Button.builder(Component.literal(">"), b -> {
            page = Math.min(pages() - 1, page + 1);
            refresh();
        }).bounds(0, 0, 20, ROW_BODY).build();
        doneButton = Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(0, 0, 60, ROW_BODY).build();
        this.addRenderableWidget(reportButton);
        this.addRenderableWidget(overlayButton);
        this.addRenderableWidget(autoButton);
        this.addRenderableWidget(resetTabButton);
        this.addRenderableWidget(pageBack);
        this.addRenderableWidget(pageForward);
        this.addRenderableWidget(doneButton);

        refresh();
    }

    private void selectTab(int index) {
        this.tab = TABS[Math.floorMod(index, TABS.length)];
        this.page = 0;
        refresh();
    }

    // ── relayout ────────────────────────────────────────────────────────────
    /** Recomputes labels, geometry and the visible key list. Called on any change. */
    private void refresh() {
        Settings settings = runtime.settings();
        visible = keysFor(tab);
        if (page >= pages()) {
            page = Math.max(0, pages() - 1);
        }
        int margin = 6;
        int width = this.width - margin * 2;

        // Header + tabs.
        headerLines = new String[] {"BlockBoost " + BlockBoost.version(), statusLine()};
        int tabWidth = Math.max(48, (width - (TABS.length - 1) * 2) / TABS.length);
        int x = margin;
        for (int i = 0; i < TABS.length; i++) {
            tabButtons[i].setX(x);
            tabButtons[i].setY(margin + 22);
            tabButtons[i].setWidth(TABS[i] == tab ? tabWidth + (width - tabWidth * TABS.length - (TABS.length - 1) * 2)
                    : tabWidth);
            tabButtons[i].setMessage(Component.literal((TABS[i] == tab ? "> " : "") + TABS[i].title));
            tabButtons[i].active = TABS[i] != tab;
            x += tabButtons[i].getWidth() + 2;
        }

        // Bottom bar, right to left so nothing overflows at small widths.
        int bottom = this.height - margin - ROW_BODY;
        doneButton.setX(margin + width - 60);
        doneButton.setY(bottom);
        reportButton.setX(margin + width - 60 - 3 - 80);
        reportButton.setY(bottom);
        overlayButton.setX(margin + width - 60 - 3 - 80 - 3 - 80);
        overlayButton.setY(bottom);
        overlayButton.setMessage(Component.literal("overlay: " + settings.string(ConfigSchema.DIAG_HUD)));
        int controlY = bottom - ROW_BODY - 2;
        pageForward.setX(margin + width - 20);
        pageForward.setY(controlY);
        pageBack.setX(margin + width - 42);
        pageBack.setY(controlY);
        clearSearch.setX(margin + 2 + Math.min(140, width - 4) - 22);
        clearSearch.setY(bottom + ROW_BODY + 4 > this.height ? bottom : bottom);
        search.setWidth(Math.max(60, Math.min(150, width - 4 - 24)));
        search.setHeight(18);
        search.setX(margin + 2);
        search.setY(bottom);
        autoButton.setX(margin + Math.min(150, width - 4) + 6);
        autoButton.setY(bottom);
        autoButton.setWidth(Math.max(60, Math.min(110, width - Math.min(150, width - 4) - 190)));
        autoButton.active = true;
        resetTabButton.setWidth(Math.max(50, Math.min(96, width - Math.min(150, width - 4) - 6 - 110 - 200)));
        resetTabButton.setX(margin + Math.min(150, width - 4) + 6 + autoButton.getWidth() + 4);
        resetTabButton.setY(bottom);
        presetRowWidth = width;
        presetRowY = controlY - ROW_BODY - 4;
        int presetCount = Preset.ALL.size();
        int presetWidth = Math.max(40, (width - (presetCount - 1) * 2) / presetCount);
        int px = margin;
        for (int i = 0; i < presetCount; i++) {
            presetButtons[i].setX(px);
            presetButtons[i].setY(presetRowY);
            presetButtons[i].setWidth(i == 0 ? presetWidth + (width - presetWidth * presetCount - (presetCount - 1) * 2)
                    : presetWidth);
            px += presetButtons[i].getWidth() + 2;
        }

        // The rows.
        rowsTop = margin + 22 + ROW_BODY + 12;
        int rowsBottom = presetRowY - 8;
        rowStride = ROW_BODY + ROW_NOTE + 2;
        int available = Math.max(rowStride, rowsBottom - rowsTop);
        int capacity = Math.max(1, available / rowStride);
        rowsShown = Math.min(MAX_ROWS, Math.min(capacity, visible.size()));
        rowsLeft = margin;
        rowsWidth = width;

        int pageStart = page * rowsShown;
        for (int i = 0; i < MAX_ROWS; i++) {
            int source = pageStart + i;
            boolean show = source < visible.size() && !isInfoTab() && i < rowsShown;
            rowBody[i].visible = show;
            rowMinus[i].visible = show && canStep(visible, source);
            rowPlus[i].visible = show && canStep(visible, source);
            rowInfo[i].visible = show;
            if (!show) {
                continue;
            }
            SettingKey key = visible.get(source);
            int y = rowsTop + i * rowStride;
            int stepper = 22;
            int infoW = 20;
            int bodyWidth = rowsWidth - (stepper * 2 + infoW + 6);
            rowBody[i].setX(rowsLeft);
            rowBody[i].setY(y);
            rowBody[i].setWidth(bodyWidth);
            rowMinus[i].setX(rowsLeft + bodyWidth + 2);
            rowMinus[i].setY(y);
            rowMinus[i].setWidth(stepper);
            rowPlus[i].setX(rowsLeft + bodyWidth + 2 + stepper + 2);
            rowPlus[i].setY(y);
            rowPlus[i].setWidth(stepper);
            rowInfo[i].setX(rowsLeft + rowsWidth - infoW);
            rowInfo[i].setY(y);
            rowInfo[i].setWidth(infoW);

            rowLabel[i] = displayLabel(key);
            rowValue[i] = valueText(settings, key);
            rowNote[i] = noteFor(key);
            rowHasBar[i] = key.kind() != SettingKey.Kind.BOOLEAN && key.kind() != SettingKey.Kind.ENUM
                    && key.max() > key.min();
            rowFraction[i] = rowHasBar[i]
                    ? clamp01((number(settings, key) - key.min()) / (key.max() - key.min()))
                    : 0.0D;
            rowBody[i].setMessage(Component.literal(rowLabel[i]));
            rowBody[i].setTooltip(Tooltip.create(Component.literal(fullText(settings, key))));
        }

        pageLabel = isInfoTab() ? "" : ((page + 1) + "/" + Math.max(1, pages()));
        if (isInfoTab()) {
            overviewLines = tab == Tab.OVERVIEW ? overview() : List.of();
            smartLines = tab == Tab.SMART ? smart() : List.of();
        }
    }

    private int presetRowWidth;
    private int presetRowY;

    private boolean isInfoTab() {
        return tab == Tab.OVERVIEW || tab == Tab.SMART;
    }

    private int pages() {
        if (rowsShown <= 0) {
            return 1;
        }
        return Math.max(1, (visible.size() + rowsShown - 1) / rowsShown);
    }

    private List<SettingKey> keysFor(Tab tab) {
        List<SettingKey> out = new ArrayList<>();
        if (tab.categories.length == 0) {
            return out;
        }
        for (SettingKey key : ConfigSchema.all()) {
            for (SettingKey.Category cat : tab.categories) {
                if (key.category() == cat) {
                    out.add(key);
                    break;
                }
            }
        }
        if (!filter.isEmpty()) {
            List<SettingKey> matching = new ArrayList<>();
            for (SettingKey key : out) {
                String doc = key.docSuffix() == null ? "" : key.docSuffix();
                if (key.path().toLowerCase(Locale.ROOT).contains(filter)
                        || displayLabel(key).toLowerCase(Locale.ROOT).contains(filter)
                        || doc.toLowerCase(Locale.ROOT).contains(filter)) {
                    matching.add(key);
                }
            }
            return matching;
        }
        return out;
    }

    /** Call sites say "the key list changed"; the layout recomputes it from scratch. */
    private void reloadKeys() {
        refresh();
    }

    // ── drawing (reads what refresh() prepared; allocates nothing) ──────────
    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int margin = 6;
        int width = this.width - margin * 2;

        graphics.fill(margin, margin + 20, margin + width, margin + 20 + ROW_BODY + 4, COLOR_PANEL);
        graphics.text(this.font, headerLines[0], margin + 3, margin + 2, 0xFFFFD27A, true);
        graphics.text(this.font, headerLines[1], margin + 3, margin + 11, COLOR_DIM, false);

        int tabsBottom = margin + 22 + ROW_BODY + 2;
        graphics.text(this.font, tab.title + " - " + tab.blurb, margin + 3, tabsBottom, COLOR_TEXT, false);

        if (isInfoTab()) {
            List<String> lines = tab == Tab.OVERVIEW ? overviewLines : smartLines;
            int y = tabsBottom + ROW_NOTE + 6;
            for (String line : lines) {
                if (y > presetRowY - ROW_NOTE) {
                    graphics.text(this.font, "... (see the report for the rest)", margin + 3, y, COLOR_WARN, false);
                    break;
                }
                int color = line.startsWith("!") ? COLOR_WARN : (line.startsWith("#") ? 0xFFFFD27A : COLOR_TEXT);
                graphics.text(this.font, line, margin + 3, y, color, false);
                y += this.font.lineHeight;
            }
            return;
        }

        for (int i = 0; i < rowsShown; i++) {
            if (!rowBody[i].visible) {
                continue;
            }
            int y = rowsTop + i * rowStride + ROW_BODY + 1;
            if (rowNote[i] != null) {
                graphics.text(this.font, rowNote[i], rowsLeft + 2, y, COLOR_DIM, false);
            }
            if (rowHasBar[i]) {
                int barX = rowsLeft + 2;
                int barW = Math.max(20, rowsWidth / 3);
                int barY = y + this.font.lineHeight + 1;
                graphics.fill(barX, barY, barX + barW, barY + 3, COLOR_BAR_BACK);
                graphics.fill(barX, barY, barX + (int) (barW * rowFraction[i]), barY + 3, COLOR_BAR);
                graphics.text(this.font, rowValue[i], barX + barW + 6, barY - 3, COLOR_VALUE, false);
            } else {
                graphics.text(this.font, rowValue[i], rowsLeft + rowsWidth - 70 - 22 - 20, y, COLOR_VALUE, false);
            }
        }
        if (!pageLabel.isEmpty()) {
            graphics.text(this.font, pageLabel + "  (" + visible.size() + " settings)",
                    this.width - margin - 96, presetRowY - 14, COLOR_DIM, false);
        }
    }

    // ── content helpers ─────────────────────────────────────────────────────
    private String statusLine() {
        ClientServices services = dev.blockboost.client.BlockBoostClient.services();
        String profile = runtime.device() == null ? "?" : runtime.device().rendererProfile().label();
        String cls = runtime.device() == null ? "?" : runtime.device().deviceClass().id();
        String fps = services == null ? "" : " | " + (int) services.frames().meanFps() + " fps avg, "
                + String.format(Locale.ROOT, "%.1f", services.frames().percentileMs(0.95)) + " ms p95";
        return profile + " | " + cls + " | " + runtime.registry().liveLines().size() + " systems active" + fps
                + (restartPending ? " | restart to finish" : "");
    }

    private List<String> overview() {
        List<String> out = new ArrayList<>();
        ClientServices services = dev.blockboost.client.BlockBoostClient.services();
        out.add("#Active optimizations");
        out.addAll(runtime.registry().liveLines());
        out.add("");
        out.add("#Systems not running, and why");
        for (String line : runtime.registry().statusLines()) {
            if (!line.contains("active")) {
                out.add("  " + line);
            }
        }
        out.add("");
        out.add("#Detected (once, not per frame)");
        if (runtime.device() != null) {
            out.addAll(runtime.device().reportLines());
        }
        if (services != null) {
            out.add("  occlusion query: " + services.probeDescription());
            out.add("  pools: " + services.poolSummary());
        }
        out.add("");
        out.add("#Frame times in the current window");
        if (services != null) {
            RollingStats s = services.frames().snapshotStats();
            out.add(String.format(Locale.ROOT, "  mean %.2f ms  median %.2f  p95 %.2f  p99 %.2f  spikes %d",
                    s.meanMs(), s.percentileMs(0.5), s.percentileMs(0.95), s.percentileMs(0.99),
                    s.countSpikes()));
            out.add("  1% low " + (int) services.frames().low1PercentFps() + " fps, missed target "
                    + services.frames().missedTargetFrames() + " frames");
        }
        out.add("");
        out.add("#Configuration");
        out.add("  file: " + runtime.configPath());
        out.add("  preset: " + runtime.settings().presetName() + "  (applied patch, not a saved profile)");
        if (runtime.loadResult() != null && !runtime.loadResult().notes().isEmpty()) {
            out.add("  notes: " + String.join("; ", runtime.loadResult().notes()));
        }
        for (String note : diagnosticsNotes()) {
            out.add("! " + note);
        }
        return out;
    }

    private List<String> diagnosticsNotes() {
        List<String> out = new ArrayList<>();
        ClientServices services = dev.blockboost.client.BlockBoostClient.services();
        if (services != null) {
            for (String note : services.diagnostics().notes()) {
                if (note.startsWith("!!") || note.contains("unavailable") || note.contains("failed")
                        || note.contains("suppressed")) {
                    out.add(note);
                }
            }
        }
        return out;
    }

    private List<String> smart() {
        List<String> out = new ArrayList<>();
        out.add("#How the automatic profile works");
        out.add("  Reads cores, the heap ceiling this JVM was given, whether the OS looks like");
        out.add("  Android, and - once a GL context exists - the renderer strings the driver");
        out.add("  reports. Those become a device class and a small set of settings.");
        out.add("  A GPU name alone never decides anything: it says nothing about the CPU cluster,");
        out.add("  the allowed heap or the thermal limit, which are what actually cap a phone.");
        out.add("");
        out.add("#What it will not do");
        out.add("  Never pick a frame cap, never enable vsync, never lower render distance,");
        out.add("  never disable gameplay particles, never act adaptively without being asked.");
        out.add("");
        out.add("#What it chose for this device");
        for (String line : lastAutoLines()) {
            out.add("  " + line);
        }
        out.add("");
        out.add("#Presets");
        out.add("  Each preset is a patch: only its own keys are written, so your other choices");
        out.add("  survive. Tapping one shows the exact diff before anything is applied.");
        for (Preset preset : Preset.ALL) {
            out.add("  - " + shortPresetName(preset) + ": " + preset.patch().size() + " keys");
        }
        return out;
    }

    private List<String> lastAutoLines() {
        ClientServices services = dev.blockboost.client.BlockBoostClient.services();
        if (services == null) {
            return List.of("not available yet");
        }
        return services.lastAutoProfileLines();
    }

    private static String shortPresetName(Preset preset) {
        String id = preset.id();
        return switch (id) {
            case "maximum_fps" -> "Max fps";
            case "balanced" -> "Balanced";
            case "smooth_gameplay" -> "Smooth";
            case "battery_cooler" -> "Battery";
            case "shaders" -> "Shaders";
            case "compatibility_safe" -> "Safe";
            default -> "Custom";
        };
    }

    /** The name a person reads: the setting's path, prettified, never a raw key. */
    private static String displayLabel(SettingKey key) {
        String tail = key.path();
        int dot = tail.indexOf('.');
        tail = dot >= 0 ? tail.substring(dot + 1) : tail;
        StringBuilder sb = new StringBuilder(tail.length() + 8);
        boolean upnext = true;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '_') {
                sb.append(' ');
                upnext = true;
                continue;
            }
            sb.append(upnext ? Character.toUpperCase(c) : c);
            upnext = false;
        }
        return sb.toString();
    }

    private static String noteFor(SettingKey key) {
        String doc = key.docSuffix();
        if (doc == null || doc.isBlank()) {
            return key.risk() == SettingKey.Risk.SAFE ? "" : ("risk: " + key.risk().name().toLowerCase(Locale.ROOT));
        }
        String first = doc.split("(?<=[.!?]) ", 2)[0].trim();
        return first.length() > 150 ? first.substring(0, 147) + "..." : first;
    }

    private static String fullText(Settings settings, SettingKey key) {
        StringBuilder sb = new StringBuilder();
        sb.append(displayLabel(key)).append('\n');
        sb.append("Path: ").append(key.path()).append('\n');
        if (key.docSuffix() != null && !key.docSuffix().isBlank()) {
            sb.append(key.docSuffix()).append('\n');
        }
        sb.append("Value: ").append(valueText(settings, key))
                .append("   default: ").append(textOf(key.defaultValue()));
        switch (key.kind()) {
            case INTEGER, DOUBLE -> sb.append("   range: ").append(textOf(key.min())).append("..")
                    .append(textOf(key.max()));
            case ENUM -> sb.append("   options: ").append(String.join(", ", key.enumValues()));
            case BOOLEAN -> sb.append("   on/off");
        }
        sb.append('\n');
        sb.append(key.risk() == SettingKey.Risk.SAFE ? "Safe to change at any time."
                : "Risk: " + key.risk().name().toLowerCase(Locale.ROOT) + ".");
        sb.append(key.requiresRestart() ? " Needs a restart to take full effect." : " Applies immediately.");
        return sb.toString();
    }

    private static String valueText(Settings settings, SettingKey key) {
        Object value = settings.raw(key);
        if (key.kind() == SettingKey.Kind.ENUM) {
            return String.valueOf(value);
        }
        if (value instanceof Boolean b) {
            return b ? "on" : "off";
        }
        return textOf(value);
    }

    private static String textOf(Object value) {
        if (value instanceof Double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }
        return String.valueOf(value);
    }

    private static double number(Settings settings, SettingKey key) {
        Object value = settings.raw(key);
        return value instanceof Number n ? n.doubleValue() : key.min();
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static boolean canStep(List<SettingKey> keys, int index) {
        if (index < 0 || index >= keys.size()) {
            return false;
        }
        SettingKey.Kind kind = keys.get(index).kind();
        return kind != SettingKey.Kind.ENUM;
    }

    // ── actions ─────────────────────────────────────────────────────────────
    private SettingKey keyAt(int row) {
        int source = page * rowsShown + row;
        return source >= 0 && source < visible.size() ? visible.get(source) : null;
    }

    private void adjust(int row, int direction) {
        SettingKey key = keyAt(row);
        if (key == null) {
            return;
        }
        Settings settings = runtime.settings();
        if (key.kind() == SettingKey.Kind.BOOLEAN) {
            settings.set(key, !settings.bool(key));
        } else if (key.kind() == SettingKey.Kind.ENUM) {
            stepEnum(settings, key, direction);
        } else {
            double span = key.max() - key.min();
            double step = Math.max(key.kind() == SettingKey.Kind.INTEGER ? 1 : 0.01D,
                    Math.abs(span) * (direction > 0 ? 0.1D : -0.1D));
            double next = number(settings, key) + step;
            if (key.kind() == SettingKey.Kind.INTEGER) {
                settings.set(key, (int) Math.round(Math.max(key.min(), Math.min(key.max(), next))));
            } else {
                settings.set(key, Math.max(key.min(), Math.min(key.max(), next)));
            }
        }
        touch(key);
    }

    private void cycle(int row, int direction) {
        SettingKey key = keyAt(row);
        if (key == null) {
            return;
        }
        Settings settings = runtime.settings();
        switch (key.kind()) {
            case BOOLEAN -> settings.set(key, !settings.bool(key));
            case ENUM -> stepEnum(settings, key, direction);
            case INTEGER -> settings.set(key, wrap(settings.integer(key), (int) key.min(), (int) key.max(), 1));
            case DOUBLE -> settings.set(key, settings.dbl(key) >= key.max() ? key.min()
                    : Math.min(key.max(), settings.dbl(key) + Math.max(0.05D, (key.max() - key.min()) / 10D)));
        }
        touch(key);
    }

    private static int wrap(int value, int min, int max, int step) {
        int next = value + step;
        return next > max ? min : Math.max(min, next);
    }

    private void stepEnum(Settings settings, SettingKey key, int direction) {
        List<String> values = key.enumValues();
        if (values.isEmpty()) {
            return;
        }
        int current = Math.max(0, key.enumIndex(settings.string(key)));
        int next = Math.floorMod(current + direction, values.size());
        settings.set(key, values.get(next));
    }

    /** Marks that something needs a restart, reloads the list and saves. */
    private void touch(SettingKey key) {
        if (key != null && key.requiresRestart()) {
            restartPending = true;
        }
        runtime.requestSave();
        reloadKeys();
    }

    private void openRow(int row) {
        SettingKey key = keyAt(row);
        if (key == null) {
            return;
        }
        Settings settings = runtime.settings();
        String text = fullText(settings, key);
        List<String> sideEffects = sideEffects(key);
        String body = text + (sideEffects.isEmpty() ? "" : ("\n\nSide effects\n- " + String.join("\n- ", sideEffects)));
        this.minecraft.setScreen(new InfoScreen(this, Component.literal(displayLabel(key)),
                Component.literal(body), "ok", () -> {
        }));
    }

    /**
     * What to expect when a setting moves, in the terms a player uses. Not exhaustive,
     * and it does not pretend to be: it is the short list of consequences that are known
     * from what the code actually does.
     */
    private static List<String> sideEffects(SettingKey key) {
        List<String> out = new ArrayList<>();
        String path = key.path();
        if (path.startsWith("render.")) {
            out.add("Changes how often frames are produced, so it changes heat and battery too.");
        }
        if (path.startsWith("culling.")) {
            out.add("Only ever decides whether to skip drawing something already off screen or fully"
                    + " behind terrain; when in doubt it draws.");
        }
        if (path.startsWith("particles.")) {
            out.add("Fewer particles can hide information (rain direction, crit indicators) unless"
                    + " 'keep gameplay particles' stays on.");
        }
        if (path.startsWith("chunks.") || path.startsWith("cpu.")) {
            out.add("Background work moves between threads; too few workers makes chunk load feel"
                    + " slow, too many steals time from the render thread.");
        }
        if (path.startsWith("memory.") || path.startsWith("diagnostics.")) {
            out.add("Caches and sampling cost memory and CPU instead of saving them; the overlay is"
                    + " the cheapest thing here and the detailed one is not meant to stay on.");
        }
        if (path.startsWith("android.")) {
            out.add("Changes which heuristics BlockBoost trusts, not what the driver does.");
        }
        if (key.requiresRestart()) {
            out.add("Needs a restart to take full effect.");
        }
        return out;
    }

    private void confirmPreset(Preset preset) {
        Settings settings = runtime.settings();
        Map<SettingKey, Object> diff = new LinkedHashMap<>();
        for (Map.Entry<SettingKey, Object> e : preset.patch().entrySet()) {
            if (!e.getValue().equals(settings.raw(e.getKey()))) {
                diff.put(e.getKey(), e.getValue());
            }
        }
        if (diff.isEmpty()) {
            this.minecraft.setScreen(new InfoScreen(this, Component.literal(shortPresetName(preset)),
                    Component.literal("Everything this preset touches is already set that way."), "ok", () -> {
                    }));
            return;
        }
        StringBuilder sb = new StringBuilder("Apply " + shortPresetName(preset) + "? " + diff.size()
                + " of its " + preset.patch().size() + " keys differ from what is set now.\n\n");
        for (Map.Entry<SettingKey, Object> e : diff.entrySet()) {
            sb.append("  ").append(displayLabel(e.getKey())).append(": ").append(textOf(e.getValue()));
            if (e.getKey().requiresRestart()) {
                sb.append("  (restart)");
            }
            sb.append('\n');
        }
        sb.append("\nOnly these keys change. Everything else you set stays as it is.");
        this.minecraft.setScreen(new InfoScreen(this, Component.literal(shortPresetName(preset)),
                Component.literal(sb.toString()), "apply", () -> {
                    int changed = settings.applyAll(preset.patch());
                    if (changed > 0) {
                        VanillaOptions.persist(Minecraft.getInstance());
                    }
                    runtime.requestSave();
                    reloadKeys();
                }));
    }

    private void confirmResetTab() {
        final List<SettingKey> keys = visible.isEmpty() ? keysFor(tab) : new ArrayList<>(visible);
        StringBuilder sb = new StringBuilder("Reset " + keys.size() + " settings in " + tab.title
                + " to their defaults?\n\n");
        int shown = 0;
        for (SettingKey key : keys) {
            if (shown++ > 18) {
                sb.append("  ... and ").append(keys.size() - shown).append(" more\n");
                break;
            }
            sb.append("  ").append(displayLabel(key)).append(" -> ").append(textOf(key.defaultValue())).append('\n');
        }
        this.minecraft.setScreen(new InfoScreen(this, Component.literal("Reset this section"),
                Component.literal(sb.toString()), "reset", () -> {
                    Map<SettingKey, Object> defaults = new LinkedHashMap<>();
                    for (SettingKey key : keys) {
                        defaults.put(key, key.defaultValue());
                    }
                    runtime.settings().applyAll(defaults);
                    runtime.requestSave();
                    reloadKeys();
                }));
    }

    private void applyRecommended() {
        ClientServices services = dev.blockboost.client.BlockBoostClient.services();
        if (services == null) {
            return;
        }
        List<String> lines = services.applyRecommendedProfile(true);
        reloadKeys();
        String body = lines.isEmpty()
                ? "Nothing was applied. If a device class is set by hand, that choice wins: clear"
                        + " android.device_class (Advanced) to let the automatic profile speak."
                : String.join("\n", lines);
        this.minecraft.setScreen(new InfoScreen(this, Component.literal("Recommended for this device"),
                Component.literal(body), "ok", () -> {
                }));
    }

    private void cycleOverlay() {
        Settings settings = runtime.settings();
        int next = (Math.max(0, settings.enumIndex(ConfigSchema.DIAG_HUD)) + 1) % 3;
        settings.set(ConfigSchema.DIAG_HUD, List.of("off", "basic", "advanced").get(next));
        settings.set(ConfigSchema.DIAG_ENABLED, next != 0);
        runtime.requestSave();
        HotState.refresh();
        refresh();
    }

    private void writeReport() {
        ClientServices services = dev.blockboost.client.BlockBoostClient.services();
        if (services == null) {
            return;
        }
        String path = services.reports().writeReport(services.diagnostics().fullReport());
        services.diagnostics().markReportWritten(path);
        this.minecraft.setScreen(new InfoScreen(this, Component.literal("Report written"),
                Component.literal(path), "ok", () -> {
                }));
    }

    @Override
    public void onClose() {
        runtime.saveNow();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
