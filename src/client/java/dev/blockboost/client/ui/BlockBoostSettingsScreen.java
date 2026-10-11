package dev.blockboost.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.blockboost.BlockBoost;
import dev.blockboost.BlockBoostRuntime;
import dev.blockboost.client.ClientServices;
import dev.blockboost.config.ConfigSchema;
import dev.blockboost.config.Preset;
import dev.blockboost.config.SettingKey;
import dev.blockboost.config.Settings;

/**
 * BlockBoost's own settings screen: built out of vanilla widgets only, sized for a
 * thumb rather than a mouse, and describing every setting in full.
 *
 * <p>Two choices here are deliberate. First, no custom rendering and no
 * version-sensitive widget classes: a phone launcher updates Minecraft sooner than
 * it updates a mod, so a screen built from {@code Button} and {@code ConfirmScreen}
 * is the version that keeps opening. Second, every row states what it does and what
 * it costs before you accept it — an optimization mod that changes your framerate
 * cap or render distance without saying so is just a surprise generator.
 *
 * <p>Rows are paged instead of scrolled: a scrollbar needs a scroll API that has
 * changed shape in every Minecraft version since 1.8, while four big buttons do not.
 */
public final class BlockBoostSettingsScreen extends Screen {

    private static final SettingKey.Category[] CATEGORIES = SettingKey.Category.values();
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final BlockBoostRuntime runtime;
    private final ClientServices services;

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
        rebuild();
    }

    private void rebuild() {
        this.clearWidgets();
        Settings settings = runtime.settings();
        int margin = 6;
        int width = this.width - margin * 2;
        int rows = Math.max(3, (this.height - 96) / ROW_HEIGHT);
        this.visible = keysFor(CATEGORIES[categoryIndex]);
        int pages = Math.max(1, (visible.size() + rows - 1) / rows);
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }

        int y = margin;
        // Top bar: section, presets, and where we are in the list.
        this.addRenderableWidget(Button.builder(Component.literal("< " + CATEGORIES[categoryIndex].id),
                        b -> {
                            categoryIndex = (categoryIndex + CATEGORIES.length - 1) % CATEGORIES.length;
                            page = 0;
                            rebuild();
                        })
                .bounds(margin, y, width / 3 - 2, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("> " + CATEGORIES[categoryIndex].id),
                        b -> {
                            categoryIndex = (categoryIndex + 1) % CATEGORIES.length;
                            page = 0;
                            rebuild();
                        })
                .bounds(margin + width / 3 + 2, y, width / 3 - 2, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("blockboost.screen.preset",
                        Component.literal(settings.presetName())),
                        b -> confirmPreset())
                .bounds(margin + (2 * width) / 3 + 2, y, width / 3 - 2, 20).build());
        y += 24;

        int start = page * rows;
        int end = Math.min(visible.size(), start + rows);
        for (int i = start; i < end; i++) {
            SettingKey key = visible.get(i);
            y = addRow(settings, key, margin, y, width);
        }
        for (int i = end; i < start + rows; i++) {
            y += ROW_HEIGHT;
        }

        // Bottom bar: paging, help, diagnostics, reset, done.
        int bottom = this.height - margin - 20;
        int small = width / 5 - 2;
        this.addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> {
                            page = Math.max(0, page - 1);
                            rebuild();
                        })
                .bounds(margin, bottom, small, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal((page + 1) + "/" + pages),
                        b -> {
                        })
                .bounds(margin + small + 3, bottom, small, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> {
                            page = Math.min(pages - 1, page + 1);
                            rebuild();
                        })
                .bounds(margin + (small + 3) * 2, bottom, small, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("blockboost.screen.report"),
                        b -> writeReport())
                .bounds(margin + (small + 3) * 3, bottom, small, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable(restartPending
                        ? "blockboost.screen.restart_pending" : "blockboost.screen.done"),
                        b -> this.minecraft == null ? this.close() : this.minecraft.setScreen(parent))
                .bounds(margin + (small + 3) * 4, bottom, Math.max(small, (2 * width) / 5 - 3), 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("blockboost.screen.reset_section"),
                        b -> confirmResetSection())
                .bounds(margin, bottom - 22, width, 20).build());
    }

    /** One setting: name and value, minus, plus, and a description button. */
    private int addRow(Settings settings, SettingKey key, int margin, int y, int width) {
        int nameWidth = (int) (width * 0.58);
        int step = (width - nameWidth - 6) / 3;
        String label = displayLabel(key) + ": " + valueText(settings, key);
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            cycle(settings, key, 1);
            rebuild();
        }).bounds(margin, y, nameWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            cycle(settings, key, -1);
            rebuild();
        }).bounds(margin + nameWidth + 2, y, step, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            cycle(settings, key, 1);
            rebuild();
        }).bounds(margin + nameWidth + 2 + step + 2, y, step, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("?"), b -> explain(key)).bounds(
                margin + nameWidth + 2 + (step + 2) * 2, y, step, 20).build());
        return y + ROW_HEIGHT;
    }

    private void cycle(Settings settings, SettingKey key, int direction) {
        switch (key.kind()) {
            case BOOLEAN -> {
                boolean current = settings.bool(key);
                settings.set(key, !current);
            }
            case ENUM -> {
                List<String> values = key.enumValues();
                if (values.isEmpty()) {
                    return;
                }
                int index = settings.enumIndex(key);
                int next = Math.floorMod(index + direction, values.size());
                settings.set(key, values.get(next));
            }
            case INTEGER -> {
                int step = intStep(key);
                settings.set(key, clampInt(key, settings.integer(key) + direction * step));
            }
            case DOUBLE -> {
                settings.set(key, clampDouble(key, settings.dbl(key) + direction * doubleStep(key)));
            }
            default -> {
            }
        }
        if (key.requiresRestart()) {
            restartPending = true;
        }
        runtime.requestSave();
    }



    /**
     * A tenth of the range, at least 1: a phone user should reach the edge of a
     * range in about ten taps, and a preset-sized jump should never be one tap away.
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

    /** The row's label, without a 200-character tooltip in it. */
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

    /** What the setting does, what it costs, and whether it needs a restart. */
    private void explain(SettingKey key) {
        Settings settings = runtime.settings();
        StringBuilder sb = new StringBuilder();
        String doc = key.docSuffix();
        sb.append(doc == null || doc.isBlank() ? "(no description)" : doc);
        sb.append("\n\nRange: ").append(fmt(key.min())).append(" - ").append(fmt(key.max()))
                .append("\nDefault: ").append(key.defaultValue())
                .append("\nCurrent: ").append(valueText(settings, key))
                .append("\nRestart: ").append(key.requiresRestart() ? "needed to take full effect" : "not needed")
                .append("\nRisk: ").append(key.risk());
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) {
                // "Cancel" on a row means: put this one setting back to its default.
                settings.set(key, key.defaultValue());
                runtime.requestSave();
            }
            this.minecraft.setScreen(this);
        }, Component.literal(displayLabel(key)), Component.literal(sb.toString())));
    }

    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return Integer.toString((int) v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private void confirmResetSection() {
        List<SettingKey> keys = visible;
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                for (SettingKey key : keys) {
                    runtime.settings().set(key, key.defaultValue());
                }
                runtime.requestSave();
            }
            this.minecraft.setScreen(this);
        }, Component.translatable("blockboost.screen.reset_section"),
                Component.translatable("blockboost.screen.reset_section.confirm",
                        CATEGORIES[categoryIndex].id, keys.size())));
    }

    /**
     * Shows the exact diff a preset would apply, and only writes it if the user says
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
        for (var entry : chosen.patch().entrySet()) {
            SettingKey key = entry.getKey();
            diff.append("  ").append(displayLabel(key)).append(": ").append(valueText(settings, key))
                    .append(" -> ").append(entry.getValue()).append("\n");
        }
        if (diff.length() == 0) {
            diff.append("  (no changes)\n");
        }
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                int changed = settings.applyAll(chosen.patch());
                settings.markPreset(chosen.id());
                runtime.requestSave();
                BlockBoost.logInfo("[BlockBoost] preset " + chosen.id() + " applied (" + changed + " changes)");
                this.runtime.registry().refresh();
                dev.blockboost.client.HotState.refresh();
            }
            this.minecraft.setScreen(this);
        }, Component.literal("Preset: " + chosen.id()),
                Component.literal("Apply " + chosen.patch().size() + " changes?\n" + diff)));
    }

    private void writeReport() {
        if (services == null) {
            return;
        }
        try {
            String path = services.writeBenchReport();
            if (this.minecraft.player != null) {
                this.minecraft.player.sendSystemMessage(
                        Component.literal("[BlockBoost] report written: " + path));
            }
        } catch (RuntimeException e) {
            BlockBoost.LOG.warn("[BlockBoost] report failed", e);
        }
    }

    @Override
    public void onClose() {
        runtime.saveNow();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        // The game must keep running: half of what this screen reports about frame
        // time is meaningless while the client is paused.
        return false;
    }
}
