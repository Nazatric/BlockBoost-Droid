package dev.blockboost.client.ui;

import java.util.function.Consumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A yes/no prompt with a body of text, used for preset diffs, resets and per-setting
 * explanations.
 *
 * <p>Why this exists instead of vanilla's confirm screen: the constructor of that
 * class is one of the things that has changed shape across Minecraft versions, and a
 * settings screen that crashes on launch is worse than a settings screen with two
 * plain buttons. Everything here is one {@code Button} call deep, which is the part
 * of the GUI layer mods have relied on for years.
 */
public final class InfoScreen extends Screen {

    private final Component body;
    private final String confirmLabel;
    private final String cancelLabel;
    private final Consumer<Boolean> onAnswer;
    private final Screen back;

    public InfoScreen(Screen back, Component title, Component body, String confirmLabel, String cancelLabel,
                      Consumer<Boolean> onAnswer) {
        super(title);
        this.back = back;
        this.body = body;
        this.confirmLabel = confirmLabel;
        this.cancelLabel = cancelLabel;
        this.onAnswer = onAnswer;
    }

    @Override
    protected void init() {
        int margin = 8;
        int buttonWidth = Math.min(150, (this.width - margin * 3) / 2);
        int y = this.height - margin - 20;
        this.addRenderableWidget(Button.builder(Component.literal(confirmLabel), b -> answer(true))
                .bounds(margin, y, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(cancelLabel), b -> answer(false))
                .bounds(margin * 2 + buttonWidth, y, buttonWidth, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("< back"), b -> answer(false))
                .bounds(this.width - margin - 70, y, 70, 20).build());

        // The body is drawn as disabled widget rows rather than by overriding the
        // screen's render method, because Screen's drawing entry point is exactly the
        // kind of thing that renames between versions - and a settings dialog that
        // fails to compile against a rename is a broken mod, not a cosmetic problem.
        int rowY = 24;
        for (String line : wrap(this.font, body.getString(), this.width - margin * 2)) {
            if (rowY > y - 26) {
                addRow("... (continued in the config file)", margin, rowY, this.width - margin * 2);
                break;
            }
            addRow(line, margin, rowY, this.width - margin * 2);
            rowY += Math.max(10, this.font.lineHeight);
        }
    }

    /** One flat, inert line of text: a disabled button is vanilla's cheapest label. */
    private void addRow(String text, int x, int y, int width) {
        Button row = Button.builder(Component.literal(text), b -> { })
                .bounds(x, y, width, 12).build();
        row.active = false;
        this.addRenderableWidget(row);
    }

    private void answer(boolean confirmed) {
        try {
            onAnswer.accept(confirmed);
        } finally {
            if (this.minecraft != null) {
                this.minecraft.setScreen(back);
            }
        }
    }

    /**
     * The body text is drawn by wrapping it into fixed-width lines and using the same
     * text call the game uses everywhere else. Wrapping happens here rather than in a
     * tooltip because a finger cannot hover.
     */
    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int y = 24;
        graphics.text(this.font, this.title, 8, 8, 0xFFFFE070, true);
        for (String line : wrap(this.font, body.getString(), this.width - 16)) {
            if (y > this.height - 40) {
                graphics.text(this.font, "… (see the config file for the rest)", 8, y, 0xFFB0B0B0, true);
                break;
            }
            graphics.text(this.font, line, 8, y, 0xFFFFFFFF, true);
            y += this.font.lineHeight;
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(back);
        }
    }

    /**
     * Word wrap measured with the game's own font metrics. No dependency on a text
     * layout class: those have been renamed more than once, and a settings screen has
     * to survive that.
     */
    static java.util.List<String> wrap(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (font.width(candidate) > maxWidth && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
