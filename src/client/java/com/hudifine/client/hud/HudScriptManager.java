package com.hudifine.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hudifine.client.script.HudAst;
import com.hudifine.client.script.HudExpressionEngine;
import com.hudifine.client.script.HudScriptParser;
import com.hudifine.client.ui.WidgetSettingsScreen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

public final class HudScriptManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_SCRIPT = """
        meta {
          name: "Status HUD"
          author: "hudifine"
          version: "1.0.0"
        }

        settings {
          toggle "Show Coordinates" { id: showCoords default: true }
          color "Health Color" { id: healthColor default: #ff5555 }
          slider "Opacity" { id: opacity min: 0.2 max: 1.0 step: 0.05 default: 0.85 }
        }

        widget {
          anchor: bottom-left
          offsetX: 10
          offsetY: -60
          background: #0b1320cc
          border: 1 #6dd3ff66
          padding: 8
          gap: 5
          direction: column
          opacity: setting(opacity)

          text {
            value: "HP {round(get(player.health))}/{round(get(player.maxHealth))}"
            color: setting(healthColor)
            fontSize: 12
            shadow: true
          }

          bar {
            value: get(player.health)
            max: get(player.maxHealth)
            width: 120
            height: 8
            fillColor: setting(healthColor)
            backgroundColor: #ffffff22
          }

          if setting(showCoords) {
            text {
              value: "X {floor(get(world.x))} Y {floor(get(world.y))} Z {floor(get(world.z))}"
              color: #b8c5d6
              fontSize: 10
            }
          }
        }
        """;

    private final Minecraft client;
    private final HudDataSource dataSource;
    private final HudRenderer renderer;
    private final Path configPath;

    private final List<HudWidgetInstance> widgets = new ArrayList<>();

    private HudWidgetInstance draggingWidget;
    private double dragOffsetX;
    private double dragOffsetY;

    private String lastError = "";
    private String lastInfo = "";

    public HudScriptManager(Minecraft client) {
        this.client = client;
        this.dataSource = new HudDataSource(client);
        this.renderer = new HudRenderer(client, dataSource);

        Path configDirectory = FabricLoader.getInstance().getConfigDir().resolve("hudifine");
        this.configPath = configDirectory.resolve("widgets.json");

        try {
            Files.createDirectories(configDirectory);
        } catch (IOException ignored) {
        }
    }

    public void initialize() {
        load();
    }

    public void tick() {
        dataSource.tick();
    }

    public void beginFrame() {
        dataSource.beginFrame();
    }

    public void renderHud(GuiGraphicsExtractor context) {
        if (client.getWindow() == null) {
            return;
        }

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        List<HudWidgetInstance> sorted = new ArrayList<>(widgets);
        sorted.sort(Comparator.comparingInt(widget -> (int) HudExpressionEngine.asDouble(
            HudExpressionEngine.eval(widget.document.widget.props.getOrDefault("zIndex", "0"), new ResolverAdapter(widget)),
            0.0
        )));

        int mouseX = (int) Math.round(client.mouseHandler.getScaledXPos(client.getWindow()));
        int mouseY = (int) Math.round(client.mouseHandler.getScaledYPos(client.getWindow()));

        for (HudWidgetInstance widget : sorted) {
            renderer.renderWidget(context, widget, width, height, mouseX, mouseY, false);
        }
    }

    public void renderEditorOverlay(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (client.getWindow() == null) {
            return;
        }

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        for (HudWidgetInstance widget : widgets) {
            renderer.renderWidget(context, widget, width, height, mouseX, mouseY, true);
        }

        if (!lastError.isEmpty()) {
            context.text(client.font, "Hudifine error: " + lastError, 8, 8, 0xFFFF6B6B, true);
        } else if (!lastInfo.isEmpty()) {
            context.text(client.font, lastInfo, 8, 8, 0xFF6DD3FF, true);
        }
    }

    public boolean handleMouseClick(Screen currentScreen, double mouseX, double mouseY, int button) {
        if (button == 0) {
            return beginDraggingAt(mouseX, mouseY);
        }

        HudWidgetInstance hovered = getTopMostWidget(mouseX, mouseY);
        if (hovered == null) {
            return false;
        }

        if (button == 1) {
            client.setScreen(new WidgetSettingsScreen(currentScreen, this, hovered));
            return true;
        }

        return false;
    }

    public boolean handleMouseDragged(double mouseX, double mouseY) {
        if (draggingWidget == null) {
            return false;
        }

        draggingWidget.hasManualPosition = true;
        draggingWidget.manualX = mouseX - dragOffsetX;
        draggingWidget.manualY = mouseY - dragOffsetY;
        return true;
    }

    public boolean handleMouseReleased() {
        if (draggingWidget == null) {
            return false;
        }

        draggingWidget = null;
        lastInfo = "";
        save();
        return true;
    }

    public boolean beginDraggingAt(double mouseX, double mouseY) {
        HudWidgetInstance hovered = getTopMostWidget(mouseX, mouseY);
        if (hovered == null) {
            return false;
        }

        draggingWidget = hovered;
        dragOffsetX = mouseX - hovered.lastX;
        dragOffsetY = mouseY - hovered.lastY;
        lastInfo = "Dragging widget. Release mouse to place.";
        return true;
    }

    public boolean isDragging() {
        return draggingWidget != null;
    }

    public void recordGlobalClick(int button) {
        dataSource.recordClick(button);
    }

    public void applyScript(String script, HudWidgetInstance editingTarget) {
        try {
            HudAst.HudScriptDocument document = new HudScriptParser(script).parse();
            HudWidgetInstance widget = editingTarget == null ? new HudWidgetInstance(script, document) : editingTarget;

            if (editingTarget != null) {
                widget.document.meta.clear();
                widget.document.meta.putAll(document.meta);
                widget.document.settings.clear();
                widget.document.settings.addAll(document.settings);
                widget.document.widget = document.widget;
                widget.document.warnings.clear();
                widget.document.warnings.addAll(document.warnings);
            }

            if (editingTarget == null) {
                applyDefaultSettings(widget);
                widgets.add(widget);
            } else {
                mergeSettings(widget, document.settings);
            }

            lastError = "";
            lastInfo = "Script applied successfully.";
            save();
        } catch (Exception exception) {
            lastError = exception.getMessage() == null ? "Unknown parser error." : exception.getMessage();
        }
    }

    public void removeWidget(UUID widgetId) {
        widgets.removeIf(widget -> widget.id.equals(widgetId));
        save();
    }

    public List<HudWidgetInstance> getWidgets() {
        return widgets;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastInfo() {
        return lastInfo;
    }

    public void setLastInfo(String message) {
        this.lastInfo = message == null ? "" : message;
    }

    public void setSettingValue(HudWidgetInstance widget, String settingId, Object value) {
        widget.settingsValues.put(settingId, value);
        save();
    }

    public Object getSettingValue(HudWidgetInstance widget, HudAst.SettingDefinition definition) {
        if (widget.settingsValues.containsKey(definition.id)) {
            return widget.settingsValues.get(definition.id);
        }

        return defaultValue(definition);
    }

    private void applyDefaultSettings(HudWidgetInstance widget) {
        for (HudAst.SettingDefinition setting : widget.document.settings) {
            widget.settingsValues.put(setting.id, defaultValue(setting));
        }
    }

    private void mergeSettings(HudWidgetInstance widget, List<HudAst.SettingDefinition> currentSettings) {
        List<String> validIds = new ArrayList<>();
        for (HudAst.SettingDefinition setting : currentSettings) {
            validIds.add(setting.id);
            widget.settingsValues.putIfAbsent(setting.id, defaultValue(setting));
        }

        widget.settingsValues.keySet().removeIf(id -> !validIds.contains(id));
    }

    private Object defaultValue(HudAst.SettingDefinition definition) {
        return switch (definition.type) {
            case TOGGLE -> HudExpressionEngine.evalBoolean(definition.defaultRaw, new ResolverAdapter(null), false);
            case SLIDER -> HudExpressionEngine.evalNumber(definition.defaultRaw, new ResolverAdapter(null), 0.0);
            case COLOR, SELECT, TEXT, KEYBIND -> HudExpressionEngine.normalizeRawValue(definition.defaultRaw);
        };
    }

    private HudWidgetInstance getTopMostWidget(double mouseX, double mouseY) {
        HudWidgetInstance selected = null;
        int bestZIndex = Integer.MIN_VALUE;

        for (HudWidgetInstance widget : widgets) {
            if (!widget.contains(mouseX, mouseY)) {
                continue;
            }

            int z = (int) Math.round(HudExpressionEngine.evalNumber(
                widget.document.widget.props.getOrDefault("zIndex", "0"),
                new ResolverAdapter(widget),
                0.0
            ));

            if (selected == null || z >= bestZIndex) {
                selected = widget;
                bestZIndex = z;
            }
        }

        return selected;
    }

    private void save() {
        JsonObject root = new JsonObject();

        var widgetArray = new com.google.gson.JsonArray();
        for (HudWidgetInstance widget : widgets) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", widget.id.toString());
            entry.addProperty("script", widget.sourceScript);
            entry.addProperty("manualX", widget.manualX);
            entry.addProperty("manualY", widget.manualY);
            entry.addProperty("hasManualPosition", widget.hasManualPosition);

            JsonObject settings = new JsonObject();
            for (Map.Entry<String, Object> setting : widget.settingsValues.entrySet()) {
                Object value = setting.getValue();
                if (value instanceof Boolean bool) {
                    settings.addProperty(setting.getKey(), bool);
                } else if (value instanceof Number number) {
                    settings.addProperty(setting.getKey(), number);
                } else {
                    settings.addProperty(setting.getKey(), String.valueOf(value));
                }
            }
            entry.add("settings", settings);

            widgetArray.add(entry);
        }

        root.add("widgets", widgetArray);

        try {
            Files.writeString(configPath, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void load() {
        widgets.clear();

        if (!Files.exists(configPath)) {
            return;
        }

        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return;
            }

            JsonObject root = parsed.getAsJsonObject();
            JsonElement widgetsElement = root.get("widgets");
            if (widgetsElement == null || !widgetsElement.isJsonArray()) {
                return;
            }

            for (JsonElement widgetElement : widgetsElement.getAsJsonArray()) {
                if (!widgetElement.isJsonObject()) {
                    continue;
                }
                JsonObject entry = widgetElement.getAsJsonObject();

                String script = entry.has("script") ? entry.get("script").getAsString() : "";
                if (script.isBlank()) {
                    continue;
                }

                try {
                    HudAst.HudScriptDocument document = new HudScriptParser(script).parse();
                    HudWidgetInstance instance = new HudWidgetInstance(script, document);

                    instance.hasManualPosition = entry.has("hasManualPosition") && entry.get("hasManualPosition").getAsBoolean();
                    instance.manualX = entry.has("manualX") ? entry.get("manualX").getAsDouble() : 0.0;
                    instance.manualY = entry.has("manualY") ? entry.get("manualY").getAsDouble() : 0.0;

                    applyDefaultSettings(instance);
                    if (entry.has("settings") && entry.get("settings").isJsonObject()) {
                        JsonObject settings = entry.getAsJsonObject("settings");
                        for (Map.Entry<String, JsonElement> settingEntry : settings.entrySet()) {
                            JsonElement value = settingEntry.getValue();
                            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                                instance.settingsValues.put(settingEntry.getKey(), value.getAsBoolean());
                            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                                instance.settingsValues.put(settingEntry.getKey(), value.getAsDouble());
                            } else {
                                instance.settingsValues.put(settingEntry.getKey(), value.getAsString());
                            }
                        }
                    }

                    widgets.add(instance);
                } catch (Exception parseError) {
                    lastError = "Failed to load one widget: " + parseError.getMessage();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private final class ResolverAdapter implements HudExpressionEngine.Resolver {
        private final HudWidgetInstance widget;

        private ResolverAdapter(HudWidgetInstance widget) {
            this.widget = widget;
        }

        @Override
        public Object getData(String path) {
            return dataSource.getValue(path);
        }

        @Override
        public Object getSetting(String id) {
            if (widget == null) {
                return "";
            }
            if (widget.settingsValues.containsKey(id)) {
                return widget.settingsValues.get(id);
            }
            HudAst.SettingDefinition definition = widget.document.findSetting(id);
            if (definition == null) {
                return "";
            }
            return defaultValue(definition);
        }
    }
}
