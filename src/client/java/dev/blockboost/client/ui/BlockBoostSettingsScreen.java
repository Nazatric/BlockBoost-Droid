package dev.blockboost.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.client.ClientServices;
import dev.blockboost.client.HotState;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Preset;
import dev.blockboost.config.SettingKey;
import dev.blockboost.config.Settings;

/**
 * BlockBoost's settings screen: vanilla widgets only, sized for a thumb, with every
 * setting describing itself before you accept it.
 *
 * <p>Two deliberate constraints.
 *
 * <p><b>No custom widget classes and no scroll containers.</b> The GUI layer is the
 * part of Minecraft that changes most between versions — sliders, cycle buttons, text
 * fields and scroll areas have each been renamed or rebuilt several times — and a
 * performance mod whose settings screen crashes is a performance mod you cannot
 * configure. Four {@code Button}s per row and a fixed page count is the whole
 * dependency surface, and it is the version that keeps opening after a game update.
 *
 * <p><b>Widgets are created once and updated in place.</b> Rebuilding the widget list
 * on every tap is what makes a touch UI feel laggy; here the same buttons are reused
 * and only their labels change. A "search box" would be nicer, but {@code EditBox}'s
 * constructor is exactly the kind of detail this file refuses to guess, so sections
 * and paging do the filtering instead.
 */
public final class BlockBoostSettingsScreen extends Screen {

    private static final SettingKey.Category[] CATEGORIES = SettingKey.Category.values();
    private static final int MAX_ROWS = 10;
    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private final BlockBoostRuntime runtime;
    private final ClientServices services;

    private final Button[] rowMain = new Button[MAX_ROWS];
    private final Button[] rowMinus = new Button[MAX_ROWS];
    private final Button[] rowPlus = new Button[MAX_ROWS];
    private final Button[] rowInfo = new Button[MAX_ROWS];
    private final SettingKey[] rowKey = new SettingKey[MAX_ROWS];

    private Button sectionBack;
    private Button sectionForward;
    private Button presetButton;
    private Button pageBack;
    private Button pageInfo;
    private Button pageForward;
    private Button reportButton;
    private Button resetButton;
    private Button doneButton;
    private Button adaptiveButton;
    private Button statusButton;

    private int categoryIndex;
    private int page;
    private List<SettingKey> visible = List.of();
    private boolean restartPending;

    public BlockBoostSettingsScreen(Screen parent, BlockBoostRuntime runtime, ClientServices services) {
        super(Component.translatable("blockboost.screen.title"));
        this.parent = parent;
        this.runtime = runtime;
        this.services = services;
    }

    /** Entry point used by Mod Menu. */
    public static Screen create(Screen parent) {
        return new BlockBoostSettingsScreen(parent, BlockBoostRuntime.get(),
                dev.blockboost.client.BlockBoostClient.services());
    }

    @Override
    protected void init() {
        int margin = 6;
        int width = this.width - margin * 2;
        int third = width / 3;

        sectionBack = Button.builder(Component.literal("<"), b -> {
            categoryIndex = Math.floorMod(categoryIndex - 1, CATEGORIES.length);
            page = 0;
            refresh();
        }).bounds(margin, margin, 24, 20).build();
        sectionForward = Button.builder(Component.literal(">"), b -> {
            categoryIndex = (categoryIndex + 1) % CATEGORIES.length;
            page = 0;
            refresh();
        }).bounds(margin + width - 24, margin, 24, 20).build();
        Button sectionName = Button.builder(Component.literal(""), b -> {
            categoryIndex = (categoryIndex + 1) % CATEGORIES.length;
            page = 0;
            refresh();
        }).bounds(margin + 28, margin, width - 56 - 220, 20).build();
        this.sectionNameButton = sectionName;
        presetButton = Button.builder(Component.literal(""), b -> confirmPreset())
                .bounds(margin + width - 190, margin, 100, 20).build();
        statusButton = Button.builder(Component.literal(""), b -> refresh())
                .bounds(margin + width - 88, margin, 88, 20).build();

        // A real filter field. EditBox's 4-argument constructor is the shape that has
        // existed longest and is unambiguous about what means what; position comes from
        // setX/setY afterwards rather than from guessing the argument order.
        search = new net.minecraft.client.gui.components.EditBox(this.font,
                Math.max(60, width - 2 * margin - 64), 18,
                Component.translatable("blockboost.screen.search"));
        search.setX(margin + 32);
        search.setY(margin + 22);
        search.setMaxLength(48);
        search.setHint(Component.literal("filter settings"));
        search.setResponder(value -> {
            filter = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
            page = 0;
            refresh();
        });
        this.addRenderableWidget(search);
        clearSearch = Button.builder(Component.literal("x"), b -> {
            search.setValue("");
            filter = "";
            page = 0;
            refresh();
        }).bounds(margin + width - 30, margin + 22, 24, 18).build();
        this.addRenderableWidget(clearSearch);

        for (int i = 0; i < MAX_ROWS; i++) {
            final int index = i;
            rowMain[i] = Button.builder(Component.literal(""), b -> cycle(index, 1))
                    .bounds(margin, 0, 10, 20).build();
            rowMinus[i] = Button.builder(Component.literal("-"), b -> adjust(index, -1))
                    .bounds(margin, 0, 10, 20).build();
            rowPlus[i] = Button.builder(Component.literal("+"), b -> adjust(index, 1))
                    .bounds(margin, 0, 10, 20).build();
            rowInfo[i] = Button.builder(Component.literal("?"), b -> explain(index))
                    .bounds(margin, 0, 10, 20).build();
            this.addRenderableWidget(rowMain[i]);
            this.addRenderableWidget(rowMinus[i]);
            this.addRenderableWidget(rowPlus[i]);
            this.addRenderableWidget(rowInfo[i]);
        }

        int bottom = this.height - margin - 20;
        int small = Math.max(40, width / 6);
        pageBack = Button.builder(Component.literal("<"), b -> {
            page = Math.max(0, page - 1);
            refresh();
        }).bounds(margin, bottom, small, 20).build();
        pageInfo = Button.builder(Component.literal(""), b -> {
            page = Math.min(pages() - 1, page + 1);
            refresh();
        }).bounds(margin + small + 3, bottom, small, 20).build();
        pageForward = Button.builder(Component.literal(">"), b -> {
            page = Math.min(pages() - 1, page + 1);
            refresh();
        }).bounds(margin + (small + 3) * 2, bottom, small, 20).build();
        reportButton = Button.builder(Component.translatable("blockboost.screen.report"), b -> writeReport())
                .bounds(margin + (small + 3) * 3, bottom, small + 40, 20).build();
        adaptiveButton = Button.builder(Component.literal(""), b -> cycleAdaptive())
                .bounds(margin + (small + 3) * 3, bottom - 22, small + 40, 20).build();
        resetButton = Button.builder(Component.translatable("blockboost.screen.reset_section"), b -> confirmReset())
                .bounds(margin, bottom - 22, small * 3 + 6, 20).build();
        doneButton = Button.builder(Component.translatable("blockboost.screen.done"), b -> onClose())
                .bounds(margin, bottom - 44, small * 3 + 6, 20).build();

        this.addRenderableWidget(sectionBack);
        this.addRenderableWidget(sectionForward);
        this.addRenderableWidget(sectionName);
        this.addRenderableWidget(presetButton);
        this.addRenderableWidget(statusButton);
        this.addRenderableWidget(pageBack);
        this.addRenderableWidget(pageInfo);
        this.addRenderableWidget(pageForward);
        this.addRenderableWidget(reportButton);
        this.addRenderableWidget(adaptiveButton);
        this.addRenderableWidget(resetButton);
        this.addRenderableWidget(doneButton);
        this.rowYStart = 20 + margin + 6 + 22;
        this.rowHeight = ROW_HEIGHT;
        this.rowAreaWidth = width;
        refresh();
    }

    private Button sectionNameButton;
    private Button clearSearch;
    private net.minecraft.client.gui.components.EditBox search;
    private String filter = "";
    private int rowYStart;
    private int rowHeight;
    private int rowAreaWidth;

    /** Relayouts and relabels the fixed widgets. Never allocates widgets. */
    private void refresh() {
        Settings settings = runtime.settings();
        visible = keysFor(CATEGORIES[categoryIndex]);
        if (!filter.isEmpty()) {
            java.util.List<dev.blockboost.config.SettingKey> matching = new java.util.ArrayList<>();
            for (dev.blockboost.config.SettingKey key : visible) {
                String doc = key.docSuffix() == null ? "" : key.docSuffix();
                if (key.path().toLowerCase(Locale.ROOT).contains(filter)
                        || displayLabel(key).toLowerCase(Locale.ROOT).contains(filter)
                        || doc.toLowerCase(Locale.ROOT).contains(filter)) {
                    matching.add(key);
                }
            }
            visible = matching;
        }
        int rows = Math.max(3, Math.min(MAX_ROWS, (this.height - rowYStart - 100) / rowHeight));
        int pages = pages();
        if (page >= pages) {
            page = pages - 1;
        }
        int start = page * rows;

        sectionNameButton.setMessage(Component.literal(CATEGORIES[categoryIndex].id.toUpperCase(Locale.ROOT)));
        presetButton.setMessage(Component.literal("preset: " + settings.presetName()));
        statusButton.setMessage(Component.literal(restartPending ? "restart needed"
                : "features: " + runtime.registry().registeredCount()));
        pageInfo.setMessage(Component.literal((page + 1) + "/" + pages));
        adaptiveButton.setMessage(Component.literal("adaptive: "
                + settings.enumValue(ConfigSchema.ADAPTIVE_MODE) + "  L"
                + (services == null ? 0 : services.adaptiveLevel())));

        for (int i = 0; i < MAX_ROWS; i++) {
            int index = start + i;
            boolean used = i < rows && index < visible.size();
            SettingKey key = used ? visible.get(index) : null;
            rowKey[i] = key;
            int y = rowYStart + i * rowHeight;
            int nameWidth = (int) (rowAreaWidth * 0.60);
            int step = Math.max(20, (rowAreaWidth - nameWidth - 8) / 3);
            for (Button b : new Button[] {rowMain[i], rowMinus[i], rowPlus[i], rowInfo[i]}) {
                b.visible = used;
                b.active = used;
            }
            rowMain[i].setX(marginLeft());
            rowMain[i].setY(y);
            rowMain[i].setWidth(nameWidth);
            rowMinus[i].setX(marginLeft() + nameWidth + 2);
            rowMinus[i].setY(y);
            rowMinus[i].setWidth(step);
            rowPlus[i].setX(marginLeft() + nameWidth + 2 + step + 2);
            rowPlus[i].setY(y);
            rowPlus[i].setWidth(step);
            rowInfo[i].setX(marginLeft() + nameWidth + 2 + (step + 2) * 2);
            rowInfo[i].setY(y);
            rowInfo[i].setWidth(step);
            if (!used) {
                continue;
            }
            rowMain[i].setMessage(Component.literal(displayLabel(key) + ": " + valueText(settings, key)));
            boolean discrete = key.kind() == SettingKey.Kind.BOOLEAN || key.kind() == SettingKey.Kind.ENUM;
            rowMinus[i].setMessage(Component.literal(discrete ? "<" : "-"));
            rowPlus[i].setMessage(Component.literal(discrete ? ">" : "+"));
        }
    }

    private int marginLeft() {
        return 6;
    }

    private int pages() {
        int rows = Math.max(3, Math.min(MAX_ROWS, (this.height - rowYStart - 100) / rowHeight));
        return Math.max(1, (visible.size() + rows - 1) / rows);
    }

    /** Tapping the label cycles forward for booleans and enums; numbers step. */
    private void cycle(int index, int direction) {
        SettingKey key = rowKey[index];
        if (key == null) {
            return;
        }
        if (key.kind() == SettingKey.Kind.BOOLEAN || key.kind() == SettingKey.Kind.ENUM) {
            adjust(index, direction);
        } else {
            refresh();
        }
    }

    private void adjust(int index, int direction) {
        SettingKey key = rowKey[index];
        if (key == null) {
            return;
        }
        Settings settings = runtime.settings();
        switch (key.kind()) {
            case BOOLEAN -> settings.set(key, !settings.bool(key));
            case ENUM -> {
                List<String> values = key.enumValues();
                if (values.isEmpty()) {
                    break;
                }
                int next = Math.floorMod(settings.enumIndex(key) + direction, values.size());
                settings.set(key, values.get(next));
            }
            case INTEGER -> settings.set(key, clampInt(key, settings.integer(key) + direction * intStep(key)));
            case DOUBLE -> settings.set(key, clampDouble(key, settings.dbl(key) + direction * doubleStep(key)));
            default -> {
            }
        }
        if (key.requiresRestart()) {
            restartPending = true;
        }
        runtime.requestSave();
        refresh();
    }

    /**
     * A tenth of the range, at least 1: from the default to the edge of a range in
     * about ten taps, and never a jump you did not intend.
     */
    static int intStep(SettingKey key) {
        double span = key.max() - key.min();
        if (!(span > 0)) {
            return 1;
        }
        return (int) Math.max(1, Math.round(span / 10.0));
    }

    static double doubleStep(SettingKey key) {
        double span = key.max() - key.min();
        if (!(span > 0)) {
            return 0.1;
        }
        return Math.max(0.01, Math.round(span / 20.0 * 100.0) / 100.0);
    }

    static int clampInt(SettingKey key, int value) {
        return (int) Math.max(key.min(), Math.min(key.max(), value));
    }

    static double clampDouble(SettingKey key, double value) {
        return Math.max(key.min(), Math.min(key.max(), value));
    }

    private static String displayLabel(SettingKey key) {
        String path = key.path();
        int dot = path.lastIndexOf('.');
        String tail = dot >= 0 ? path.substring(dot + 1) : path;
        StringBuilder sb = new StringBuilder(tail.length());
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '_') {
                sb.append(' ');
            } else {
                sb.append(i == 0 ? Character.toUpperCase(c) : c);
            }
        }
        return sb.toString();
    }

    private static String valueText(Settings settings, SettingKey key) {
        return switch (key.kind()) {
            case BOOLEAN -> settings.bool(key) ? "ON" : "OFF";
            case INTEGER -> Integer.toString(settings.integer(key));
            case DOUBLE -> String.format(Locale.ROOT, "%.2f", settings.dbl(key));
            case ENUM -> settings.enumValue(key);
        };
    }

    private List<SettingKey> keysFor(SettingKey.Category category) {
        List<SettingKey> out = new ArrayList<>();
        for (SettingKey key : ConfigSchema.all()) {
            if (key.category() == category) {
                out.add(key);
            }
        }
        return out;
    }

    /** What it does, what it costs, the range, the default, and whether it needs a restart. */
    private void explain(int index) {
        SettingKey key = rowKey[index];
        if (key == null) {
            return;
        }
        Settings settings = runtime.settings();
        StringBuilder sb = new StringBuilder();
        String doc = key.docSuffix();
        sb.append(doc == null || doc.isBlank() ? "(no description)" : doc);
        sb.append("\n\nrange ").append(fmt(key.min())).append(" .. ").append(fmt(key.max()))
                .append("   default ").append(key.defaultValue())
                .append("   now ").append(valueText(settings, key));
        sb.append("\nrisk: ").append(key.risk())
                .append("   restart: ").append(key.requiresRestart() ? "needed for full effect" : "not needed");
        if (key.kind() == SettingKey.Kind.ENUM) {
            sb.append("\nvalues: ").append(String.join(" | ", key.enumValues()));
        }
        sb.append("\n\nCancel here resets this one setting to its default.");
        this.minecraft.setScreen(new InfoScreen(this, Component.literal(displayLabel(key)),
                Component.literal(sb.toString()), "Keep", "Reset to default",
                confirmed -> {
                    if (!confirmed) {
                        settings.set(key, key.defaultValue());
                        runtime.requestSave();
                    }
                    refresh();
                }));
    }

    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Integer.toString((int) v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void confirmReset() {
        List<SettingKey> keys = new ArrayList<>(visible);
        this.minecraft.setScreen(new InfoScreen(this,
                Component.translatable("blockboost.screen.reset_section"),
                Component.literal("Reset the " + CATEGORIES[categoryIndex].id + " section (" + keys.size()
                        + " settings) to their defaults? Other sections are untouched, and this is written "
                        + "to " + runtime.configPath() + " immediately."),
                "Reset", "Cancel", confirmed -> {
                    if (confirmed) {
                        for (SettingKey key : keys) {
                            runtime.settings().set(key, key.defaultValue());
                        }
                        runtime.requestSave();
                    }
                    refresh();
                }));
    }

    /**
     * Shows the exact key/value diff a preset would apply and writes it only after a
     * yes. A preset that silently overwrote someone's render distance would be the
     * most annoying thing this mod could do.
     */
    private void confirmPreset() {
        Settings settings = runtime.settings();
        List<String> ids = new ArrayList<>();
        for (Preset preset : Preset.ALL) {
            ids.add(preset.id());
        }
        int index = Math.max(0, ids.indexOf(settings.presetName()));
        Preset next = Preset.ALL.get((index + 1) % Preset.ALL.size());
        if (Preset.CUSTOM_ID.equals(next.id())) {
            next = Preset.ALL.get(0);
        }
        Preset chosen = next;
        StringBuilder diff = new StringBuilder();
        diff.append(chosen.tooltipKey().startsWith("blockboost.preset") ? "" : "").append("Preset ")
                .append(chosen.id()).append(":\n");
        for (var entry : chosen.patch().entrySet()) {
            SettingKey key = entry.getKey();
            diff.append("  ").append(displayLabel(key)).append(": ").append(valueText(settings, key))
                    .append("  ->  ").append(entry.getValue()).append('\n');
        }
        if (chosen.patch().isEmpty()) {
            diff.append("  (no changes)\n");
        }
        diff.append("\nAnything not listed above keeps your current value.");
        this.minecraft.setScreen(new InfoScreen(this, Component.literal("Preset: " + chosen.id()),
                Component.literal(diff.toString()), "Apply", "Cancel", confirmed -> {
                    if (confirmed) {
                        int changed = settings.applyAll(chosen.patch());
                        settings.markPreset(chosen.id());
                        runtime.requestSave();
                        runtime.registry().refresh();
                        HotState.refresh();
                        BlockBoost.logInfo("[BlockBoost] preset " + chosen.id() + " applied (" + changed
                                + " changes)");
                    }
                    refresh();
                }));
    }

    private void cycleAdaptive() {
        Settings settings = runtime.settings();
        List<String> modes = settings.raw(ConfigSchema.ADAPTIVE_MODE) instanceof String
                ? ConfigSchema.ADAPTIVE_MODE.enumValues()
                : List.of("off", "recommend", "active");
        int next = Math.floorMod(settings.enumIndex(ConfigSchema.ADAPTIVE_MODE) + 1, Math.max(1, modes.size()));
        settings.set(ConfigSchema.ADAPTIVE_MODE, modes.get(next));
        runtime.requestSave();
        refresh();
    }

    private void writeReport() {
        if (services == null) {
            return;
        }
        try {
            String path = services.writeBenchReport();
            if (this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(Component.literal("[BlockBoost] report: " + path));
            }
        } catch (RuntimeException e) {
            BlockBoost.LOG.warn("[BlockBoost] report failed", e);
            if (this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(
                        Component.literal("[BlockBoost] report failed: " + e.getClass().getSimpleName()));
            }
        }
    }

    @Override
    public void onClose() {
        runtime.saveNow();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

}
