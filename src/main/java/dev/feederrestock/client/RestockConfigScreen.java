package dev.feederrestock.client;

import dev.feederrestock.config.RestockConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * In-game settings screen built from vanilla widgets only, so it works with no
 * extra dependency. Values are saved the moment they change; the toggle key and
 * the restocking session are never affected by simply editing the config.
 */
public final class RestockConfigScreen extends Screen {
    private enum Page {
        INTERACTION("interaction"), TAKING("taking"), TIMEOUTS("timeouts"), FEEDBACK("feedback");

        final String key;

        Page(String key) {
            this.key = key;
        }

        Text label() {
            return Text.translatable("screen.feederrestock.page." + key);
        }
    }

    private static final int ROW_HEIGHT = 24;
    private static final int COLUMN_WIDTH = 300;
    private static final int MAX_ROWS = 6;

    private final Screen parent;
    private final RestockConfig config;
    private Page page = Page.INTERACTION;
    private boolean dirty;

    public RestockConfigScreen(Screen parent, RestockConfig config) {
        super(Text.translatable("screen.feederrestock.settings"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        build();
    }

    /** Vanilla's own page-switch path: clears children, drops focus and re-runs init(). */
    private void rebuild() {
        saveIfDirty();
        clearAndInit();
    }

    private void build() {
        int columnWidth = Math.min(COLUMN_WIDTH, width - 40);
        int left = (width - columnWidth) / 2;
        int top = 40;

        Page[] pages = Page.values();
        int tabWidth = columnWidth / pages.length;
        for (int i = 0; i < pages.length; i++) {
            Page candidate = pages[i];
            ButtonWidget tab = ButtonWidget.builder(candidate.label(), button -> {
                page = candidate;
                rebuild();
            }).dimensions(left + i * tabWidth, top, tabWidth - 2, 20).build();
            tab.active = candidate != page;
            addDrawableChild(tab);
        }

        List<Row> rows = rows(page);
        int y = top + 32;
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) {
            rows.get(i).addTo(this, left, y + i * ROW_HEIGHT, columnWidth);
        }

        int bottom = height - 28;
        int half = (columnWidth - 4) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.feederrestock.reset"), button -> {
            config.resetToDefaults();
            dirty = true;
            rebuild();
        }).dimensions(left, bottom, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(left + half + 4, bottom, columnWidth - half - 4, 20).build());
    }

    @Override
    public void close() {
        saveIfDirty();
        client.setScreen(parent);
    }

    private void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        config.save();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1.21.11 renders the background before this method: Screen.renderWithTooltip
        // calls renderBackground and then render, and the blur may only run once per
        // frame, so calling renderBackground here crashes the client.
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 16, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    private List<Row> rows(Page page) {
        List<Row> rows = new ArrayList<>();
        switch (page) {
            case INTERACTION -> {
                rows.add(toggle("unobtrusiveInteraction", () -> config.unobtrusiveInteraction,
                        value -> config.unobtrusiveInteraction = value));
                rows.add(number("preOpenPauseTicks", 0, 20, () -> config.preOpenPauseTicks,
                        value -> config.preOpenPauseTicks = value));
                rows.add(number("printPauseTicks", 1, 40, () -> config.printPauseTicks,
                        value -> config.printPauseTicks = value));
                rows.add(number("visitPauseCapTicks", 5, 200, () -> config.visitPauseCapTicks,
                        value -> config.visitPauseCapTicks = value));
                rows.add(number("manualPauseTicks", 20, 600, () -> config.manualPauseTicks,
                        value -> config.manualPauseTicks = value));
            }
            case TAKING -> {
                rows.add(number("scanIntervalTicks", 1, 100, () -> config.scanIntervalTicks,
                        value -> config.scanIntervalTicks = value));
                rows.add(number("clickIntervalTicks", 1, 20, () -> config.clickIntervalTicks,
                        value -> config.clickIntervalTicks = value));
                rows.add(number("knowledgeExpireTicks", 100, 72000, () -> config.knowledgeExpireTicks,
                        value -> config.knowledgeExpireTicks = value));
                rows.add(number("containerRevisitTicks", 20, 6000, () -> config.containerRevisitTicks,
                        value -> config.containerRevisitTicks = value));
                rows.add(number("successCooldownTicks", 5, 600, () -> config.successCooldownTicks,
                        value -> config.successCooldownTicks = value));
                rows.add(number("emptyContainerCooldownTicks", 20, 1200,
                        () -> config.emptyContainerCooldownTicks, value -> config.emptyContainerCooldownTicks = value));
            }
            case TIMEOUTS -> {
                rows.add(number("openTimeoutTicks", 10, 200, () -> config.openTimeoutTicks,
                        value -> config.openTimeoutTicks = value));
                rows.add(number("syncTimeoutTicks", 20, 400, () -> config.syncTimeoutTicks,
                        value -> config.syncTimeoutTicks = value));
                rows.add(number("recountIntervalTicks", 100, 2400, () -> config.recountIntervalTicks,
                        value -> config.recountIntervalTicks = value));
            }
            case FEEDBACK -> {
                rows.add(toggle("highlightContainers", () -> config.highlightContainers,
                        value -> config.highlightContainers = value));
                rows.add(number("highlightRange", 8, 128, () -> config.highlightRange,
                        value -> config.highlightRange = value));
                rows.add(toggle("autoRecountMaterialList", () -> config.autoRecountMaterialList,
                        value -> config.autoRecountMaterialList = value));
                rows.add(toggle("chatNotifications", () -> config.chatNotifications,
                        value -> config.chatNotifications = value));
                rows.add(toggle("actionbarStatus", () -> config.actionbarStatus,
                        value -> config.actionbarStatus = value));
            }
        }
        return rows;
    }

    private Row toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
        return (screen, x, y, w) -> screen.addDrawableChild(CyclingButtonWidget
                .onOffBuilder(getter.getAsBoolean())
                .build(x, y, w, 20, Text.translatable("options.feederrestock." + key),
                        (button, value) -> {
                            setter.accept(value);
                            dirty = true;
                        }));
    }

    private Row number(String key, int min, int max, IntSupplier getter, IntConsumer setter) {
        return (screen, x, y, w) -> screen.addDrawableChild(new IntSlider(x, y, w,
                Text.translatable("options.feederrestock." + key), min, max, getter.getAsInt(),
                value -> {
                    setter.accept(value);
                    dirty = true;
                }));
    }

    /** One row of the settings page; the widget knows its own label and value. */
    private interface Row {
        void addTo(RestockConfigScreen screen, int x, int y, int width);
    }

    private static final class IntSlider extends SliderWidget {
        private final Text label;
        private final int min;
        private final int max;
        private final IntConsumer setter;
        private int applied;

        IntSlider(int x, int y, int width, Text label, int min, int max, int value, IntConsumer setter) {
            super(x, y, width, 20, label, fraction(value, min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.applied = value;
            updateMessage();
        }

        private static double fraction(int value, int min, int max) {
            if (max <= min) return 0.0;
            double clamped = Math.max(min, Math.min(max, value));
            return (clamped - min) / (double) (max - min);
        }

        private int current() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            // The superclass constructor calls this before our own fields exist.
            if (label == null) return;
            setMessage(Text.translatable("options.feederrestock.value", label, current()));
        }

        @Override
        protected void applyValue() {
            int value = current();
            if (value == applied) return;
            applied = value;
            setter.accept(value);
        }
    }
}
