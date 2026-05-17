package com.hudifine.client.ui;

import com.hudifine.client.hud.HudScriptManager;
import com.hudifine.client.hud.HudWidgetInstance;
import com.hudifine.client.script.HudAst;
import com.hudifine.client.script.HudExpressionEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WidgetSettingsScreen extends Screen {
    private final Screen parent;
    private final HudScriptManager manager;
    private final HudWidgetInstance widget;

    private final List<Row> rows = new ArrayList<>();

    public WidgetSettingsScreen(Screen parent, HudScriptManager manager, HudWidgetInstance widget) {
        super(Component.translatable("hudifine.settings.title"));
        this.parent = parent;
        this.manager = manager;
        this.widget = widget;
    }

    @Override
    protected void init() {
        rows.clear();

        int y = 40;
        int controlX = width / 2;
        int controlWidth = Math.max(120, width / 2 - 40);

        for (HudAst.SettingDefinition definition : widget.document.settings) {
            rows.add(new Row(definition, y));

            switch (definition.type) {
                case TOGGLE -> addRenderableWidget(buildToggleButton(definition, controlX, y - 2, controlWidth));
                case SELECT -> addRenderableWidget(buildSelectButton(definition, controlX, y - 2, controlWidth));
                case SLIDER -> addRenderableWidget(buildSlider(definition, controlX, y - 2, controlWidth));
                case COLOR, TEXT, KEYBIND -> addRenderableWidget(buildTextField(definition, controlX, y - 2, controlWidth));
            }

            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), ignored -> onClose())
            .bounds(width - 100, height - 32, 76, 20)
            .build());

        addRenderableWidget(Button.builder(Component.literal("Delete Widget"), ignored -> {
                manager.removeWidget(widget.id);
                onClose();
            })
            .bounds(24, height - 32, 110, 20)
            .build());
    }

    private Button buildToggleButton(HudAst.SettingDefinition definition, int x, int y, int width) {
        return Button.builder(toggleText(definition), ignored -> {
                Object current = manager.getSettingValue(widget, definition);
                boolean next = !HudExpressionEngine.asBoolean(current);
                manager.setSettingValue(widget, definition.id, next);
                ignored.setMessage(toggleText(definition));
            })
            .bounds(x, y, width, 20)
            .build();
    }

    private Component toggleText(HudAst.SettingDefinition definition) {
        boolean value = HudExpressionEngine.asBoolean(manager.getSettingValue(widget, definition));
        return Component.literal(value ? "On" : "Off");
    }

    private Button buildSelectButton(HudAst.SettingDefinition definition, int x, int y, int width) {
        List<String> options = new ArrayList<>();
        for (String option : definition.optionRaws) {
            options.add(HudExpressionEngine.normalizeRawValue(option));
        }
        if (options.isEmpty()) {
            options.add("default");
        }

        return Button.builder(Component.literal(String.valueOf(manager.getSettingValue(widget, definition))), ignored -> {
                String current = String.valueOf(manager.getSettingValue(widget, definition));
                int index = options.indexOf(current);
                int nextIndex = (index + 1) % options.size();
                String next = options.get(nextIndex);
                manager.setSettingValue(widget, definition.id, next);
                ignored.setMessage(Component.literal(next));
            })
            .bounds(x, y, width, 20)
            .build();
    }

    private AbstractSliderButton buildSlider(HudAst.SettingDefinition definition, int x, int y, int width) {
        double min = HudExpressionEngine.asDouble(HudExpressionEngine.eval(definition.minRaw, emptyResolver()), 0.0);
        double max = HudExpressionEngine.asDouble(HudExpressionEngine.eval(definition.maxRaw, emptyResolver()), 1.0);
        double step = HudExpressionEngine.asDouble(HudExpressionEngine.eval(definition.stepRaw, emptyResolver()), 0.05);

        double current = HudExpressionEngine.asDouble(manager.getSettingValue(widget, definition), min);
        if (max <= min) {
            max = min + 1.0;
        }

        double normalized = (current - min) / (max - min);
        normalized = Math.max(0.0, Math.min(1.0, normalized));

        return new SettingSliderWidget(definition, x, y, width, min, max, step, normalized);
    }

    private EditBox buildTextField(HudAst.SettingDefinition definition, int x, int y, int width) {
        EditBox field = new EditBox(font, x, y, width, 20, Component.empty());
        field.setValue(String.valueOf(manager.getSettingValue(widget, definition)));

        if (definition.type == HudAst.SettingType.TEXT && !definition.maxLengthRaw.isBlank()) {
            int maxLength = (int) Math.round(HudExpressionEngine.asDouble(HudExpressionEngine.eval(definition.maxLengthRaw, emptyResolver()), 64));
            field.setMaxLength(Math.max(1, maxLength));
        } else {
            field.setMaxLength(256);
        }

        field.setResponder(value -> manager.setSettingValue(widget, definition.id, value));
        return field;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        context.fillGradient(0, 0, width, height, 0xDD101727, 0xDD0A101A);
        context.text(font, title, 24, 16, 0xFFFFFFFF, true);

        for (Row row : rows) {
            String description = row.setting.description == null ? "" : row.setting.description;
            context.text(font, row.setting.label, 24, row.y, 0xFFD4E2F1);
            if (!description.isBlank()) {
                context.text(font, description, 24, row.y + 10, 0xFF8FA6BC);
            }
        }

        super.extractRenderState(context, mouseX, mouseY, deltaTicks);
    }

    private HudExpressionEngine.Resolver emptyResolver() {
        return new HudExpressionEngine.Resolver() {
            @Override
            public Object getData(String path) {
                return "";
            }

            @Override
            public Object getSetting(String id) {
                return "";
            }
        };
    }

    private record Row(HudAst.SettingDefinition setting, int y) {
    }

    private final class SettingSliderWidget extends AbstractSliderButton {
        private final HudAst.SettingDefinition definition;
        private final double min;
        private final double max;
        private final double step;

        private SettingSliderWidget(HudAst.SettingDefinition definition, int x, int y, int width, double min, double max, double step, double value) {
            super(x, y, width, 20, Component.empty(), value);
            this.definition = definition;
            this.min = min;
            this.max = max;
            this.step = Math.max(0.0001, step);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            double currentValue = getCurrentValue();
            setMessage(Component.literal(String.format(Locale.ROOT, "%.2f", currentValue)));
        }

        @Override
        protected void applyValue() {
            manager.setSettingValue(widget, definition.id, getCurrentValue());
        }

        private double getCurrentValue() {
            double raw = min + this.value * (max - min);
            double snapped = Math.round(raw / step) * step;
            return Math.max(min, Math.min(max, snapped));
        }
    }
}
