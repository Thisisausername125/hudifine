package com.hudifine.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hudifine.client.script.HudAst;
import com.hudifine.client.script.HudExpressionEngine;
import com.hudifine.client.script.HudScriptParser;
import com.hudifine.client.ui.WidgetSettingsScreen;
import dev.hudifine.api.hud.HudifineHudRegistry;
import dev.hudifine.api.provider.HudifineProviderRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.sounds.SoundEvents;

public final class HudScriptManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int RESIZE_HANDLE_RADIUS = 2;
    public static final int RESIZE_HANDLE_HIT_RADIUS = 8;
    private static final double MIN_RESIZE_WIDTH = 24.0;
    private static final double MIN_RESIZE_HEIGHT = 18.0;
    private static final double MIN_MANUAL_SCALE = 0.25;
    private static final double MAX_MANUAL_SCALE = 6.0;
    private static final double MAX_SCREEN_AREA_RATIO = 0.30;
    private static final double SNAP_DISTANCE = 6.0;
    private static final int CONTEXT_MENU_PADDING = 4;
    private static final int CONTEXT_MENU_ROW_HEIGHT = 16;
    private static final int CONTEXT_MENU_MIN_WIDTH = 122;
    private static final int CONTEXT_MENU_ICON_SIZE = 8;
    private static final int CONTEXT_MENU_ICON_TEXT_GAP = 5;
    private static final String DEFAULT_SCRIPT = """
        meta {
          name: "Status HUD"
          author: "hudifine"
                    version: "2.0.0-beta"
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
    private final List<HudWidgetInstance> selectedWidgets = new ArrayList<>();
    private final List<DragState> dragStates = new ArrayList<>();
    private final List<ResizeState> resizeStates = new ArrayList<>();
    private final List<HudWidgetInstance> deleteConfirmTargets = new ArrayList<>();
    private final Set<String> dismissedExtensionKeys = new LinkedHashSet<>();
    private final List<ContextMenuEntry> contextMenuEntries = new ArrayList<>();
    private final List<HudWidgetInstance> contextMenuTargets = new ArrayList<>();
    private final List<UUID> copiedWidgetIds = new ArrayList<>();

    private HudWidgetInstance draggingWidget;
    private double dragStartMouseX;
    private double dragStartMouseY;
    private HudWidgetInstance resizingWidget;
    private ResizeCorner resizingCorner;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;
    private double resizeStartScale;
    private boolean selectionBoxActive;
    private boolean selectionBoxAdditive;
    private double selectionBoxStartX;
    private double selectionBoxStartY;
    private double selectionBoxCurrentX;
    private double selectionBoxCurrentY;
    private final List<HudWidgetInstance> selectionBoxBaseSelection = new ArrayList<>();

    private boolean contextMenuOpen;
    private int contextMenuX;
    private int contextMenuY;
    private int contextMenuWidth;
    private int contextMenuHeight;
    private HudWidgetInstance contextMenuSettingsTarget;
    private Screen contextMenuParentScreen;
    private boolean deleteConfirmOpen;

    private String lastError = "";
    private String lastInfo = "";

    private enum ResizeCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private enum ContextMenuAction {
        SETTINGS,
        DUPLICATE,
        COPY_CODE,
        HIDE,
        DELETE
    }

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
        boolean configExists = Files.exists(configPath);
        load();

        boolean changed = installMissingExtensionWidgets();
        if (!configExists && ensureDefaultWidgetIfEmpty()) {
            changed = true;
        }
        if (changed) {
            save();
        }
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

        List<RenderOrderEntry> sorted = new ArrayList<>(widgets.size());
        for (int i = 0; i < widgets.size(); i++) {
            HudWidgetInstance widget = widgets.get(i);
            int zIndex = (int) HudExpressionEngine.asDouble(
                HudExpressionEngine.eval(widget.document.widget.props.getOrDefault("zIndex", "0"), new ResolverAdapter(widget)),
                0.0
            );
            sorted.add(new RenderOrderEntry(widget, zIndex, i));
        }

        sorted.sort(
            Comparator.comparingInt(RenderOrderEntry::zIndex)
                .thenComparingInt(RenderOrderEntry::insertionIndex)
        );

        int mouseX = (int) Math.round(client.mouseHandler.getScaledXPos(client.getWindow()));
        int mouseY = (int) Math.round(client.mouseHandler.getScaledYPos(client.getWindow()));

        for (RenderOrderEntry entry : sorted) {
            HudWidgetInstance widget = entry.widget();
            if (!widget.userVisible) {
                continue;
            }
            renderer.renderWidget(context, widget, width, height, mouseX, mouseY, false, false, false, false);
        }
    }

    public void renderEditorOverlay(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (client.getWindow() == null) {
            return;
        }

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        HudWidgetInstance hovered = getTopMostWidget(mouseX, mouseY);
        for (HudWidgetInstance widget : widgets) {
            boolean selected = isSelected(widget);
            boolean hoverOnly = hovered == widget && !selected;
            renderer.renderWidget(context, widget, width, height, mouseX, mouseY, true, selected, hoverOnly, !widget.userVisible);
        }

        renderSelectionBox(context);

        renderContextMenu(context, mouseX, mouseY, width, height);
        renderDeleteConfirm(context, mouseX, mouseY, width, height);

        if (!lastError.isEmpty()) {
            context.text(client.font, "Hudifine error: " + lastError, 8, 8, 0xFFFF6B6B, true);
        }
    }

    public boolean handleMouseClick(Screen currentScreen, double mouseX, double mouseY, int button, boolean shiftDown) {
        if (deleteConfirmOpen) {
            if (button == 0) {
                return handleDeleteConfirmLeftClick(mouseX, mouseY);
            }
            return true;
        }

        if (button == 0) {
            if (handleContextMenuLeftClick(currentScreen, mouseX, mouseY)) {
                return true;
            }

            HudWidgetInstance hovered = getTopMostWidget(mouseX, mouseY);
            if (hovered == null) {
                closeContextMenu();
                return beginSelectionBox(mouseX, mouseY, shiftDown);
            }

            closeContextMenu();

            if (shiftDown) {
                toggleSelection(hovered);
                return true;
            }

            boolean hoveredWasSelected = isSelected(hovered);
            ResizeCorner corner = getResizeCorner(hovered, mouseX, mouseY);
            if (!(corner != null && hoveredWasSelected && selectedWidgets.size() > 1)) {
                selectOnly(hovered);
            }

            if (corner != null) {
                return beginResizingAt(hovered, corner);
            }

            return beginDraggingWidget(hovered, mouseX, mouseY);
        }

        if (button == 1) {
            HudWidgetInstance hovered = getTopMostWidget(mouseX, mouseY, true);
            if (hovered == null) {
                closeContextMenu();
                return false;
            }

            if (shiftDown) {
                if (!isSelected(hovered)) {
                    selectedWidgets.add(hovered);
                }
            } else if (!isSelected(hovered)) {
                selectOnly(hovered);
            }

            List<HudWidgetInstance> targets = new ArrayList<>();
            if (isSelected(hovered) && selectedWidgets.size() > 1) {
                targets.addAll(selectedWidgets);
            } else {
                targets.add(hovered);
            }

            openContextMenu(currentScreen, mouseX, mouseY, hovered, targets);
            return true;
        }

        return false;
    }

    public boolean handleMouseDragged(double mouseX, double mouseY) {
        if (selectionBoxActive) {
            selectionBoxCurrentX = mouseX;
            selectionBoxCurrentY = mouseY;
            updateSelectionFromSelectionBox();
            return true;
        }

        if (resizingWidget != null) {
            return handleResizeDrag(mouseX, mouseY);
        }

        if (draggingWidget == null || dragStates.isEmpty()) {
            return false;
        }

        if (client.getWindow() == null) {
            return false;
        }

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        double requestedDx = mouseX - dragStartMouseX;
        double requestedDy = mouseY - dragStartMouseY;

        double minDx = Double.NEGATIVE_INFINITY;
        double maxDx = Double.POSITIVE_INFINITY;
        double minDy = Double.NEGATIVE_INFINITY;
        double maxDy = Double.POSITIVE_INFINITY;

        for (DragState state : dragStates) {
            minDx = Math.max(minDx, -state.startX());
            maxDx = Math.min(maxDx, screenWidth - (state.startX() + state.width()));
            minDy = Math.max(minDy, -state.startY());
            maxDy = Math.min(maxDy, screenHeight - (state.startY() + state.height()));
        }

        double dx = clamp(requestedDx, minDx, maxDx);
        double dy = clamp(requestedDy, minDy, maxDy);

        SnapDelta snap = computeSnapDelta(dx, dy);
        dx = clamp(dx + snap.deltaX(), minDx, maxDx);
        dy = clamp(dy + snap.deltaY(), minDy, maxDy);

        for (DragState state : dragStates) {
            state.widget().hasManualPosition = true;
            state.widget().hasManualAnchor = false;
            state.widget().manualX = state.startX() + dx;
            state.widget().manualY = state.startY() + dy;
        }
        return true;
    }

    public boolean handleMouseReleased() {
        closeContextMenu();

        if (selectionBoxActive) {
            updateSelectionFromSelectionBox();
            clearSelectionBoxState();
            return true;
        }

        if (resizingWidget != null) {
            if (!resizeStates.isEmpty()) {
                for (ResizeState state : resizeStates) {
                    autoAnchorWidget(state.widget(), true);
                }
            } else {
                autoAnchorWidget(resizingWidget, true);
            }
            resizingWidget = null;
            resizingCorner = null;
            resizeStates.clear();
            save();
            return true;
        }

        if (draggingWidget == null) {
            return false;
        }

        if (!dragStates.isEmpty()) {
            for (DragState state : dragStates) {
                autoAnchorWidget(state.widget(), true);
            }
        } else {
            autoAnchorWidget(draggingWidget, true);
        }

        draggingWidget = null;
        dragStates.clear();
        save();
        return true;
    }

    public boolean beginDraggingAt(double mouseX, double mouseY) {
        HudWidgetInstance hovered = getTopMostWidget(mouseX, mouseY);
        if (hovered == null) {
            return false;
        }

        return beginDraggingWidget(hovered, mouseX, mouseY);
    }

    private boolean beginDraggingWidget(HudWidgetInstance hovered, double mouseX, double mouseY) {
        closeContextMenu();
        clearSelectionBoxState();
        resizingWidget = null;
        resizingCorner = null;
        resizeStates.clear();

        if (!isSelected(hovered)) {
            selectOnly(hovered);
        }

        draggingWidget = hovered;
        dragStartMouseX = mouseX;
        dragStartMouseY = mouseY;

        dragStates.clear();
        for (HudWidgetInstance selected : selectedWidgets) {
            dragStates.add(new DragState(
                selected,
                selected.manualX,
                selected.manualY,
                Math.max(1.0, selected.lastWidth),
                Math.max(1.0, selected.lastHeight)
            ));
        }

        if (dragStates.isEmpty()) {
            dragStates.add(new DragState(
                hovered,
                hovered.manualX,
                hovered.manualY,
                Math.max(1.0, hovered.lastWidth),
                Math.max(1.0, hovered.lastHeight)
            ));
        }
        return true;
    }

    public boolean isDragging() {
        return draggingWidget != null || resizingWidget != null || selectionBoxActive;
    }

    public void closeTransientUi() {
        closeContextMenu();
        closeDeleteConfirmation();
        clearSelectionBoxState();
    }

    private boolean beginSelectionBox(double mouseX, double mouseY, boolean additive) {
        clearSelectionBoxState();

        draggingWidget = null;
        dragStates.clear();
        resizingWidget = null;
        resizingCorner = null;
        resizeStates.clear();

        selectionBoxActive = true;
        selectionBoxAdditive = additive;
        selectionBoxStartX = mouseX;
        selectionBoxStartY = mouseY;
        selectionBoxCurrentX = mouseX;
        selectionBoxCurrentY = mouseY;

        if (additive) {
            selectionBoxBaseSelection.addAll(selectedWidgets);
        }

        updateSelectionFromSelectionBox();
        return true;
    }

    private void clearSelectionBoxState() {
        selectionBoxActive = false;
        selectionBoxAdditive = false;
        selectionBoxBaseSelection.clear();
    }

    private void updateSelectionFromSelectionBox() {
        double left = Math.min(selectionBoxStartX, selectionBoxCurrentX);
        double right = Math.max(selectionBoxStartX, selectionBoxCurrentX);
        double top = Math.min(selectionBoxStartY, selectionBoxCurrentY);
        double bottom = Math.max(selectionBoxStartY, selectionBoxCurrentY);

        selectedWidgets.clear();
        if (selectionBoxAdditive) {
            selectedWidgets.addAll(selectionBoxBaseSelection);
        }

        for (HudWidgetInstance widget : widgets) {
            if (widget.lastWidth <= 0 || widget.lastHeight <= 0) {
                continue;
            }
            if (!rectanglesIntersect(left, top, right, bottom, widget.lastX, widget.lastY, widget.lastX + widget.lastWidth, widget.lastY + widget.lastHeight)) {
                continue;
            }
            if (!selectedWidgets.contains(widget)) {
                selectedWidgets.add(widget);
            }
        }
    }

    private void renderSelectionBox(GuiGraphicsExtractor context) {
        if (!selectionBoxActive) {
            return;
        }

        int left = (int) Math.floor(Math.min(selectionBoxStartX, selectionBoxCurrentX));
        int right = (int) Math.ceil(Math.max(selectionBoxStartX, selectionBoxCurrentX));
        int top = (int) Math.floor(Math.min(selectionBoxStartY, selectionBoxCurrentY));
        int bottom = (int) Math.ceil(Math.max(selectionBoxStartY, selectionBoxCurrentY));

        right = Math.max(right, left + 1);
        bottom = Math.max(bottom, top + 1);

        context.fill(left, top, right, bottom, 0x3377A2C2);
        context.fill(left, top, right, top + 1, 0xCCEAF6FF);
        context.fill(left, bottom - 1, right, bottom, 0xCCEAF6FF);
        context.fill(left, top, left + 1, bottom, 0xCCEAF6FF);
        context.fill(right - 1, top, right, bottom, 0xCCEAF6FF);
    }

    private static boolean rectanglesIntersect(
        double leftA,
        double topA,
        double rightA,
        double bottomA,
        double leftB,
        double topB,
        double rightB,
        double bottomB
    ) {
        return rightA >= leftB && rightB >= leftA && bottomA >= topB && bottomB >= topA;
    }

    public boolean handleDeleteSelectionShortcut() {
        if (deleteConfirmOpen) {
            return true;
        }

        if (selectedWidgets.isEmpty()) {
            return false;
        }

        openDeleteConfirmation(new ArrayList<>(selectedWidgets));
        return true;
    }

    public boolean handleCopySelectionShortcut() {
        if (deleteConfirmOpen) {
            return true;
        }

        if (selectedWidgets.isEmpty()) {
            return false;
        }

        copiedWidgetIds.clear();
        for (HudWidgetInstance widget : selectedWidgets) {
            if (widget == null) {
                continue;
            }
            if (!copiedWidgetIds.contains(widget.id)) {
                copiedWidgetIds.add(widget.id);
            }
        }

        if (copiedWidgetIds.isEmpty()) {
            return false;
        }

        int count = copiedWidgetIds.size();
        setLastInfo(count == 1 ? "Copied 1 element." : "Copied " + count + " elements.");
        return true;
    }

    public boolean handlePasteSelectionShortcut() {
        if (deleteConfirmOpen) {
            return true;
        }

        if (copiedWidgetIds.isEmpty()) {
            return false;
        }

        List<HudWidgetInstance> sources = new ArrayList<>();
        for (UUID copiedId : copiedWidgetIds) {
            HudWidgetInstance widget = findWidgetById(copiedId);
            if (widget != null) {
                sources.add(widget);
            }
        }

        if (sources.isEmpty()) {
            return false;
        }

        duplicateWidgets(sources);
        save();

        int count = sources.size();
        setLastInfo(count == 1 ? "Pasted 1 element." : "Pasted " + count + " elements.");
        return true;
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
            save();
        } catch (Exception exception) {
            lastError = exception.getMessage() == null ? "Unknown parser error." : exception.getMessage();
        }
    }

    public void removeWidget(UUID widgetId) {
        for (HudWidgetInstance widget : widgets) {
            if (widget.id.equals(widgetId)) {
                markExtensionWidgetDismissed(widget);
                break;
            }
        }

        widgets.removeIf(widget -> widget.id.equals(widgetId));
        selectedWidgets.removeIf(widget -> widget.id.equals(widgetId));
        deleteConfirmTargets.removeIf(widget -> widget.id.equals(widgetId));
        dragStates.removeIf(state -> state.widget().id.equals(widgetId));
        resizeStates.removeIf(state -> state.widget().id.equals(widgetId));
        contextMenuTargets.removeIf(widget -> widget.id.equals(widgetId));
        if (contextMenuSettingsTarget != null && contextMenuSettingsTarget.id.equals(widgetId)) {
            contextMenuSettingsTarget = null;
        }
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

    public boolean isWidgetVisible(HudWidgetInstance widget) {
        return widget != null && widget.userVisible;
    }

    public void setWidgetVisible(HudWidgetInstance widget, boolean visible) {
        if (widget == null) {
            return;
        }
        widget.userVisible = visible;
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

    private boolean isSelected(HudWidgetInstance widget) {
        return selectedWidgets.contains(widget);
    }

    private HudWidgetInstance findWidgetById(UUID id) {
        if (id == null) {
            return null;
        }

        for (HudWidgetInstance widget : widgets) {
            if (widget.id.equals(id)) {
                return widget;
            }
        }

        return null;
    }

    private void clearSelection() {
        selectedWidgets.clear();
    }

    private void selectOnly(HudWidgetInstance widget) {
        selectedWidgets.clear();
        if (widget != null) {
            selectedWidgets.add(widget);
        }
    }

    private void toggleSelection(HudWidgetInstance widget) {
        if (widget == null) {
            return;
        }

        if (selectedWidgets.contains(widget)) {
            selectedWidgets.remove(widget);
            return;
        }

        selectedWidgets.add(widget);
    }

    private void openContextMenu(
        Screen parentScreen,
        double mouseX,
        double mouseY,
        HudWidgetInstance settingsTarget,
        List<HudWidgetInstance> targets
    ) {
        closeContextMenu();

        if (settingsTarget == null || targets == null || targets.isEmpty() || client.getWindow() == null) {
            return;
        }

        contextMenuParentScreen = parentScreen;
        contextMenuSettingsTarget = settingsTarget;
        contextMenuTargets.addAll(targets);

        int count = contextMenuTargets.size();
        String noun = count == 1 ? "element" : "elements";
        boolean canCopyCode = count == 1;
        boolean canOpenSettings = canOpenSettingsForTargets(contextMenuSettingsTarget, contextMenuTargets);
        boolean anyVisible = contextMenuTargets.stream().anyMatch(widget -> widget.userVisible);
        boolean mixedVisibility = count > 1 && hasMixedVisibility(contextMenuTargets);
        String visibilityLabel = mixedVisibility ? "Hide/Unhide " + noun : (anyVisible ? "Hide " : "Unhide ") + noun;

        contextMenuEntries.add(new ContextMenuEntry("Settings", ContextMenuAction.SETTINGS, canOpenSettings));
        contextMenuEntries.add(new ContextMenuEntry("Duplicate " + noun, ContextMenuAction.DUPLICATE, true));
        contextMenuEntries.add(new ContextMenuEntry("Copy code", ContextMenuAction.COPY_CODE, canCopyCode));
        contextMenuEntries.add(new ContextMenuEntry(visibilityLabel, ContextMenuAction.HIDE, !mixedVisibility));
        contextMenuEntries.add(new ContextMenuEntry("Delete " + noun, ContextMenuAction.DELETE, true));

        int maxTextWidth = 0;
        for (ContextMenuEntry entry : contextMenuEntries) {
            maxTextWidth = Math.max(maxTextWidth, client.font.width(entry.label()));
        }

        int rowContentWidth = CONTEXT_MENU_ICON_SIZE + CONTEXT_MENU_ICON_TEXT_GAP + maxTextWidth;
        contextMenuWidth = Math.max(CONTEXT_MENU_MIN_WIDTH, rowContentWidth + CONTEXT_MENU_PADDING * 2 + 8);
        contextMenuHeight = CONTEXT_MENU_PADDING * 2 + contextMenuEntries.size() * CONTEXT_MENU_ROW_HEIGHT;

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        int desiredX = (int) Math.round(mouseX) + 2;
        int desiredY = (int) Math.round(mouseY) + 2;
        contextMenuX = (int) clamp(desiredX, 4.0, Math.max(4.0, screenWidth - contextMenuWidth - 4.0));
        contextMenuY = (int) clamp(desiredY, 4.0, Math.max(4.0, screenHeight - contextMenuHeight - 4.0));
        contextMenuOpen = true;
        playUiClickSound();
    }

    private void closeContextMenu() {
        contextMenuOpen = false;
        contextMenuX = 0;
        contextMenuY = 0;
        contextMenuWidth = 0;
        contextMenuHeight = 0;
        contextMenuSettingsTarget = null;
        contextMenuParentScreen = null;
        contextMenuEntries.clear();
        contextMenuTargets.clear();
    }

    private boolean handleContextMenuLeftClick(Screen currentScreen, double mouseX, double mouseY) {
        if (!contextMenuOpen) {
            return false;
        }

        int hitIndex = contextMenuIndexAt(mouseX, mouseY);
        if (hitIndex >= 0 && hitIndex < contextMenuEntries.size()) {
            ContextMenuEntry entry = contextMenuEntries.get(hitIndex);
            if (!entry.enabled()) {
                return true;
            }

            playUiClickSound();
            executeContextMenuAction(currentScreen, entry.action());
            return true;
        }

        boolean insideMenu = contains(mouseX, mouseY, contextMenuX, contextMenuY, contextMenuWidth, contextMenuHeight);
        closeContextMenu();
        return insideMenu;
    }

    private int contextMenuIndexAt(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY, contextMenuX, contextMenuY, contextMenuWidth, contextMenuHeight)) {
            return -1;
        }

        int startY = contextMenuY + CONTEXT_MENU_PADDING;
        int relativeY = (int) Math.floor(mouseY) - startY;
        if (relativeY < 0) {
            return -1;
        }

        int index = relativeY / CONTEXT_MENU_ROW_HEIGHT;
        if (index < 0 || index >= contextMenuEntries.size()) {
            return -1;
        }

        return index;
    }

    private void renderContextMenu(GuiGraphicsExtractor context, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!contextMenuOpen || contextMenuEntries.isEmpty()) {
            return;
        }

        contextMenuX = (int) clamp(contextMenuX, 4.0, Math.max(4.0, screenWidth - contextMenuWidth - 4.0));
        contextMenuY = (int) clamp(contextMenuY, 4.0, Math.max(4.0, screenHeight - contextMenuHeight - 4.0));

        int panelX1 = contextMenuX;
        int panelY1 = contextMenuY;
        int panelX2 = contextMenuX + contextMenuWidth;
        int panelY2 = contextMenuY + contextMenuHeight;

        context.fill(panelX1 + 2, panelY1 + 2, panelX2 + 2, panelY2 + 2, 0x66000000);
        context.fillGradient(panelX1, panelY1, panelX2, panelY2, 0xEF162331, 0xEF0E1823);
        context.fill(panelX1, panelY1, panelX2, panelY1 + 1, 0xFFBFD3E6);
        context.fill(panelX1, panelY2 - 1, panelX2, panelY2, 0x77425A70);
        context.fill(panelX1, panelY1, panelX1 + 1, panelY2, 0xA4BFD3E6);
        context.fill(panelX2 - 1, panelY1, panelX2, panelY2, 0x77425A70);

        for (int i = 0; i < contextMenuEntries.size(); i++) {
            int rowY = contextMenuY + CONTEXT_MENU_PADDING + i * CONTEXT_MENU_ROW_HEIGHT;
            int rowTop = rowY;
            int rowBottom = rowY + CONTEXT_MENU_ROW_HEIGHT;
            ContextMenuEntry entry = contextMenuEntries.get(i);
            boolean hovered = entry.enabled() && contains(mouseX, mouseY, contextMenuX + 2, rowTop, contextMenuWidth - 4, CONTEXT_MENU_ROW_HEIGHT);

            if (hovered) {
                context.fill(contextMenuX + 2, rowTop, contextMenuX + contextMenuWidth - 2, rowBottom, 0x5577A2C2);
            }

            if (i < contextMenuEntries.size() - 1) {
                context.fill(contextMenuX + 4, rowBottom - 1, contextMenuX + contextMenuWidth - 4, rowBottom, 0x1FFFFFFF);
            }

            int textY = rowTop + (CONTEXT_MENU_ROW_HEIGHT - client.font.lineHeight) / 2;
            int rowLeft = contextMenuX + CONTEXT_MENU_PADDING + 3;
            int iconY = rowTop + (CONTEXT_MENU_ROW_HEIGHT - CONTEXT_MENU_ICON_SIZE) / 2;
            int iconColor;
            if (!entry.enabled()) {
                iconColor = 0xFF6F8090;
            } else {
                iconColor = hovered ? 0xFFF5FBFF : 0xFFD3E2F0;
            }
            drawContextMenuIcon(context, entry.action(), rowLeft, iconY, iconColor);

            int color;
            if (!entry.enabled()) {
                color = 0xFF8193A4;
            } else {
                color = hovered ? 0xFFFFFFFF : 0xFFE8F0F8;
            }
            int textX = rowLeft + CONTEXT_MENU_ICON_SIZE + CONTEXT_MENU_ICON_TEXT_GAP;
            context.text(client.font, entry.label(), textX, textY, color, false);
        }
    }

    private void drawContextMenuIcon(
        GuiGraphicsExtractor context,
        ContextMenuAction action,
        int x,
        int y,
        int color
    ) {
        int size = CONTEXT_MENU_ICON_SIZE;

        switch (action) {
            case SETTINGS -> {
                int knob = y + 1;
                context.fill(x, knob, x + size, knob + 1, color);
                context.fill(x + 2, knob - 1, x + 4, knob + 2, color);

                knob = y + (size / 2);
                context.fill(x, knob, x + size, knob + 1, color);
                context.fill(x + 5, knob - 1, x + 7, knob + 2, color);

                knob = y + size - 2;
                context.fill(x, knob, x + size, knob + 1, color);
                context.fill(x + 3, knob - 1, x + 5, knob + 2, color);
            }
            case DUPLICATE -> {
                drawRectOutline(context, x + 2, y + 1, size - 2, size - 3, color);
                drawRectOutline(context, x, y + 3, size - 2, size - 3, color);
            }
            case COPY_CODE -> {
                drawRectOutline(context, x + 1, y + 2, size - 2, size - 2, color);
                context.fill(x + 3, y, x + size - 1, y + 2, color);
                context.fill(x + 4, y + 1, x + size - 2, y + 2, 0xFF1C2B38);
            }
            case HIDE -> {
                context.fill(x + 1, y + 2, x + size - 1, y + 3, color);
                context.fill(x, y + 3, x + size, y + 5, color);
                context.fill(x + 1, y + 5, x + size - 1, y + 6, color);

                for (int i = 0; i < size; i++) {
                    int px = x + i;
                    int py = y + size - 1 - i;
                    context.fill(px, py, px + 1, py + 1, 0xFF263645);
                    context.fill(px, py + 1, px + 1, py + 2, color);
                }
            }
            case DELETE -> {
                drawRectOutline(context, x + 1, y + 2, size - 2, size - 2, color);
                context.fill(x, y + 1, x + size, y + 2, color);
                context.fill(x + 2, y, x + size - 2, y + 1, color);
                context.fill(x + 3, y + 4, x + 4, y + size - 1, color);
                context.fill(x + 5, y + 4, x + 6, y + size - 1, color);
            }
        }
    }

    private static void drawRectOutline(GuiGraphicsExtractor context, int x, int y, int width, int height, int color) {
        if (width <= 1 || height <= 1) {
            context.fill(x, y, x + Math.max(1, width), y + Math.max(1, height), color);
            return;
        }

        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }

    private void executeContextMenuAction(Screen currentScreen, ContextMenuAction action) {
        List<HudWidgetInstance> targets = new ArrayList<>(contextMenuTargets);
        HudWidgetInstance settingsTarget = contextMenuSettingsTarget;
        Screen parent = contextMenuParentScreen == null ? currentScreen : contextMenuParentScreen;

        closeContextMenu();

        if (targets.isEmpty()) {
            return;
        }

        switch (action) {
            case SETTINGS -> {
                if (!canOpenSettingsForTargets(settingsTarget, targets)) {
                    return;
                }
                HudWidgetInstance target = settingsTarget == null ? targets.get(0) : settingsTarget;
                client.setScreen(new WidgetSettingsScreen(parent, this, target));
            }
            case DUPLICATE -> {
                duplicateWidgets(targets);
                save();
            }
            case COPY_CODE -> {
                if (targets.size() != 1) {
                    return;
                }

                copyWidgetCodeToClipboard(targets.getFirst());
            }
            case HIDE -> {
                if (targets.size() > 1 && hasMixedVisibility(targets)) {
                    return;
                }

                boolean anyVisible = targets.stream().anyMatch(widget -> widget.userVisible);
                boolean nextVisible = !anyVisible;
                for (HudWidgetInstance widget : targets) {
                    widget.userVisible = nextVisible;
                }
                save();
            }
            case DELETE -> {
                openDeleteConfirmation(targets);
            }
        }
    }

    private void deleteWidgets(List<HudWidgetInstance> targets) {
        if (targets.isEmpty()) {
            return;
        }

        for (HudWidgetInstance widget : targets) {
            markExtensionWidgetDismissed(widget);
        }

        List<UUID> ids = new ArrayList<>();
        for (HudWidgetInstance widget : targets) {
            ids.add(widget.id);
        }

        widgets.removeIf(widget -> ids.contains(widget.id));
        selectedWidgets.removeIf(widget -> ids.contains(widget.id));
        deleteConfirmTargets.removeIf(widget -> ids.contains(widget.id));
        dragStates.removeIf(state -> ids.contains(state.widget().id));
        resizeStates.removeIf(state -> ids.contains(state.widget().id));
        contextMenuTargets.removeIf(widget -> ids.contains(widget.id));
        if (contextMenuSettingsTarget != null && ids.contains(contextMenuSettingsTarget.id)) {
            contextMenuSettingsTarget = null;
        }
        if (draggingWidget != null && ids.contains(draggingWidget.id)) {
            draggingWidget = null;
        }
        if (resizingWidget != null && ids.contains(resizingWidget.id)) {
            resizingWidget = null;
            resizingCorner = null;
        }

        save();
    }

    private void copyWidgetCodeToClipboard(HudWidgetInstance widget) {
        if (widget == null || client == null || client.keyboardHandler == null) {
            return;
        }

        client.keyboardHandler.setClipboard(widget.sourceScript == null ? "" : widget.sourceScript);
        setLastInfo("Copied code to clipboard.");
    }

    private static boolean hasMixedVisibility(List<HudWidgetInstance> targets) {
        if (targets == null || targets.size() <= 1) {
            return false;
        }

        boolean firstVisibility = targets.getFirst().userVisible;
        for (HudWidgetInstance widget : targets) {
            if (widget.userVisible != firstVisibility) {
                return true;
            }
        }

        return false;
    }

    private static boolean canOpenSettingsForTargets(HudWidgetInstance settingsTarget, List<HudWidgetInstance> targets) {
        if (targets == null || targets.isEmpty()) {
            return false;
        }

        if (targets.size() > 1 && !hasUniformScript(targets)) {
            return false;
        }

        for (HudWidgetInstance widget : targets) {
            if (!hasWidgetSettings(widget)) {
                return false;
            }
        }

        return settingsTarget == null || hasWidgetSettings(settingsTarget);
    }

    private static boolean hasUniformScript(List<HudWidgetInstance> targets) {
        if (targets == null || targets.size() <= 1) {
            return true;
        }

        HudWidgetInstance first = targets.getFirst();
        String script = normalizeScript(first == null ? null : first.sourceScript);
        for (int i = 1; i < targets.size(); i++) {
            HudWidgetInstance widget = targets.get(i);
            String otherScript = normalizeScript(widget == null ? null : widget.sourceScript);
            if (!script.equals(otherScript)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasWidgetSettings(HudWidgetInstance widget) {
        return widget != null
            && widget.document != null
            && widget.document.settings != null
            && !widget.document.settings.isEmpty();
    }

    private static String normalizeScript(String script) {
        return script == null ? "" : script.trim();
    }

    private void openDeleteConfirmation(List<HudWidgetInstance> targets) {
        deleteConfirmTargets.clear();
        for (HudWidgetInstance widget : targets) {
            if (widget != null && !deleteConfirmTargets.contains(widget)) {
                deleteConfirmTargets.add(widget);
            }
        }

        if (deleteConfirmTargets.isEmpty()) {
            return;
        }

        closeContextMenu();
        deleteConfirmOpen = true;
    }

    private void closeDeleteConfirmation() {
        deleteConfirmOpen = false;
        deleteConfirmTargets.clear();
    }

    private boolean handleDeleteConfirmLeftClick(double mouseX, double mouseY) {
        if (!deleteConfirmOpen || client.getWindow() == null) {
            return false;
        }

        DeleteConfirmLayout layout = buildDeleteConfirmLayout(
            client.getWindow().getGuiScaledWidth(),
            client.getWindow().getGuiScaledHeight()
        );

        if (contains(mouseX, mouseY, layout.confirmX(), layout.buttonY(), layout.buttonWidth(), layout.buttonHeight())) {
            playUiClickSound();
            deleteWidgets(new ArrayList<>(deleteConfirmTargets));
            closeDeleteConfirmation();
            return true;
        }

        if (contains(mouseX, mouseY, layout.cancelX(), layout.buttonY(), layout.buttonWidth(), layout.buttonHeight())) {
            playUiClickSound();
            closeDeleteConfirmation();
            return true;
        }

        if (!contains(mouseX, mouseY, layout.x(), layout.y(), layout.width(), layout.height())) {
            closeDeleteConfirmation();
            return true;
        }

        return true;
    }

    private void renderDeleteConfirm(GuiGraphicsExtractor context, int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!deleteConfirmOpen || deleteConfirmTargets.isEmpty()) {
            return;
        }

        DeleteConfirmLayout layout = buildDeleteConfirmLayout(screenWidth, screenHeight);

        context.fill(0, 0, screenWidth, screenHeight, 0x52000000);
        context.fill(layout.x(), layout.y(), layout.x() + layout.width(), layout.y() + layout.height(), 0xF1182532);
        context.fill(layout.x(), layout.y(), layout.x() + layout.width(), layout.y() + 1, 0xFFBFD3E6);
        context.fill(layout.x(), layout.y() + layout.height() - 1, layout.x() + layout.width(), layout.y() + layout.height(), 0x77425A70);
        context.fill(layout.x(), layout.y(), layout.x() + 1, layout.y() + layout.height(), 0xA4BFD3E6);
        context.fill(layout.x() + layout.width() - 1, layout.y(), layout.x() + layout.width(), layout.y() + layout.height(), 0x77425A70);

        int count = deleteConfirmTargets.size();
        String noun = count == 1 ? "element" : "elements";
        String title = "Delete " + count + " " + noun + "?";
        String warning = "This action is irreversible.";

        int titleX = layout.x() + 10;
        int titleY = layout.y() + 10;
        context.text(client.font, title, titleX, titleY, 0xFFFFD3D3, true);
        context.text(client.font, warning, titleX, titleY + 12, 0xFFFF8C8C, false);

        boolean overDelete = contains(mouseX, mouseY, layout.confirmX(), layout.buttonY(), layout.buttonWidth(), layout.buttonHeight());
        boolean overCancel = contains(mouseX, mouseY, layout.cancelX(), layout.buttonY(), layout.buttonWidth(), layout.buttonHeight());

        int deleteBg = overDelete ? 0xAA8B3131 : 0x8A4F2626;
        int cancelBg = overCancel ? 0xAA385064 : 0x8A223746;
        int border = 0xCFE9F2FA;

        context.fill(layout.confirmX(), layout.buttonY(), layout.confirmX() + layout.buttonWidth(), layout.buttonY() + layout.buttonHeight(), deleteBg);
        context.fill(layout.confirmX(), layout.buttonY(), layout.confirmX() + layout.buttonWidth(), layout.buttonY() + 1, border);
        context.fill(layout.confirmX(), layout.buttonY() + layout.buttonHeight() - 1, layout.confirmX() + layout.buttonWidth(), layout.buttonY() + layout.buttonHeight(), border);
        context.fill(layout.confirmX(), layout.buttonY(), layout.confirmX() + 1, layout.buttonY() + layout.buttonHeight(), border);
        context.fill(layout.confirmX() + layout.buttonWidth() - 1, layout.buttonY(), layout.confirmX() + layout.buttonWidth(), layout.buttonY() + layout.buttonHeight(), border);

        context.fill(layout.cancelX(), layout.buttonY(), layout.cancelX() + layout.buttonWidth(), layout.buttonY() + layout.buttonHeight(), cancelBg);
        context.fill(layout.cancelX(), layout.buttonY(), layout.cancelX() + layout.buttonWidth(), layout.buttonY() + 1, border);
        context.fill(layout.cancelX(), layout.buttonY() + layout.buttonHeight() - 1, layout.cancelX() + layout.buttonWidth(), layout.buttonY() + layout.buttonHeight(), border);
        context.fill(layout.cancelX(), layout.buttonY(), layout.cancelX() + 1, layout.buttonY() + layout.buttonHeight(), border);
        context.fill(layout.cancelX() + layout.buttonWidth() - 1, layout.buttonY(), layout.cancelX() + layout.buttonWidth(), layout.buttonY() + layout.buttonHeight(), border);

        String deleteText = "Delete";
        String cancelText = "Cancel";
        int deleteTextX = layout.confirmX() + (layout.buttonWidth() - client.font.width(deleteText)) / 2;
        int cancelTextX = layout.cancelX() + (layout.buttonWidth() - client.font.width(cancelText)) / 2;
        int textY = layout.buttonY() + (layout.buttonHeight() - client.font.lineHeight) / 2;
        context.text(client.font, deleteText, deleteTextX, textY, 0xFFFFFFFF, false);
        context.text(client.font, cancelText, cancelTextX, textY, 0xFFFFFFFF, false);
    }

    private DeleteConfirmLayout buildDeleteConfirmLayout(int screenWidth, int screenHeight) {
        int width = 226;
        int height = 76;
        int x = Math.max(4, (screenWidth - width) / 2);
        int y = Math.max(4, (screenHeight - height) / 2);
        int buttonWidth = 88;
        int buttonHeight = 16;
        int buttonY = y + height - buttonHeight - 10;
        int confirmX = x + width - buttonWidth * 2 - 12;
        int cancelX = x + width - buttonWidth - 8;
        return new DeleteConfirmLayout(x, y, width, height, confirmX, cancelX, buttonY, buttonWidth, buttonHeight);
    }

    private void playUiClickSound() {
        if (client == null) {
            return;
        }

        client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private SnapDelta computeSnapDelta(double dx, double dy) {
        if (draggingWidget == null) {
            return new SnapDelta(0.0, 0.0);
        }

        DragState primary = findDragState(draggingWidget);
        if (primary == null) {
            return new SnapDelta(0.0, 0.0);
        }

        double movedLeft = primary.startX() + dx;
        double movedTop = primary.startY() + dy;
        double movedRight = movedLeft + primary.width();
        double movedBottom = movedTop + primary.height();

        Double snapDx = null;
        Double snapDy = null;

        for (HudWidgetInstance other : widgets) {
            if (other == null || other == primary.widget() || selectedWidgets.contains(other) || !other.userVisible) {
                continue;
            }
            if (other.lastWidth <= 0 || other.lastHeight <= 0) {
                continue;
            }

            double otherLeft = other.lastX;
            double otherTop = other.lastY;
            double otherRight = other.lastX + other.lastWidth;
            double otherBottom = other.lastY + other.lastHeight;

            if (rangesOverlap(movedTop, movedBottom, otherTop, otherBottom)) {
                double deltaToLeftSide = otherLeft - movedRight;
                if (Math.abs(deltaToLeftSide) <= SNAP_DISTANCE && (snapDx == null || Math.abs(deltaToLeftSide) < Math.abs(snapDx))) {
                    snapDx = deltaToLeftSide;
                }

                double deltaToRightSide = otherRight - movedLeft;
                if (Math.abs(deltaToRightSide) <= SNAP_DISTANCE && (snapDx == null || Math.abs(deltaToRightSide) < Math.abs(snapDx))) {
                    snapDx = deltaToRightSide;
                }
            }

            if (rangesOverlap(movedLeft, movedRight, otherLeft, otherRight)) {
                double deltaToTopSide = otherTop - movedBottom;
                if (Math.abs(deltaToTopSide) <= SNAP_DISTANCE && (snapDy == null || Math.abs(deltaToTopSide) < Math.abs(snapDy))) {
                    snapDy = deltaToTopSide;
                }

                double deltaToBottomSide = otherBottom - movedTop;
                if (Math.abs(deltaToBottomSide) <= SNAP_DISTANCE && (snapDy == null || Math.abs(deltaToBottomSide) < Math.abs(snapDy))) {
                    snapDy = deltaToBottomSide;
                }
            }
        }

        return new SnapDelta(snapDx == null ? 0.0 : snapDx, snapDy == null ? 0.0 : snapDy);
    }

    private DragState findDragState(HudWidgetInstance widget) {
        if (widget == null) {
            return null;
        }

        for (DragState state : dragStates) {
            if (state.widget() == widget) {
                return state;
            }
        }

        return null;
    }

    private static boolean rangesOverlap(double aStart, double aEnd, double bStart, double bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private void duplicateWidgets(List<HudWidgetInstance> sources) {
        if (sources.isEmpty()) {
            return;
        }

        List<HudWidgetInstance> created = new ArrayList<>();
        int screenWidth = client.getWindow() == null ? Integer.MAX_VALUE : client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow() == null ? Integer.MAX_VALUE : client.getWindow().getGuiScaledHeight();

        for (HudWidgetInstance source : sources) {
            try {
                HudAst.HudScriptDocument document = new HudScriptParser(source.sourceScript).parse();
                HudWidgetInstance duplicate = new HudWidgetInstance(source.sourceScript, document);
                duplicate.settingsValues.putAll(source.settingsValues);

                duplicate.hasManualPosition = source.hasManualPosition;
                duplicate.hasManualAnchor = source.hasManualAnchor;
                duplicate.manualAnchor = source.manualAnchor;
                duplicate.manualX = source.manualX;
                duplicate.manualY = source.manualY;
                duplicate.manualOffsetX = source.manualOffsetX;
                duplicate.manualOffsetY = source.manualOffsetY;
                duplicate.manualScaleX = source.manualScaleX;
                duplicate.manualScaleY = source.manualScaleY;
                duplicate.userVisible = source.userVisible;
                duplicate.extensionKey = "";

                double offset = 10.0;
                double width = Math.max(1.0, source.lastWidth);
                double height = Math.max(1.0, source.lastHeight);
                double maxX = Math.max(0.0, screenWidth - width);
                double maxY = Math.max(0.0, screenHeight - height);

                duplicate.manualX = clamp(source.manualX + offset, 0.0, maxX);
                duplicate.manualY = clamp(source.manualY + offset, 0.0, maxY);
                duplicate.manualOffsetX = source.manualOffsetX + offset;
                duplicate.manualOffsetY = source.manualOffsetY + offset;

                widgets.add(duplicate);
                created.add(duplicate);
            } catch (Exception exception) {
                lastError = exception.getMessage() == null ? "Failed to duplicate widget." : exception.getMessage();
            }
        }

        if (!created.isEmpty()) {
            selectedWidgets.clear();
            selectedWidgets.addAll(created);
        }
    }

    private static boolean contains(double x, double y, int left, int top, int width, int height) {
        if (left < 0 || top < 0 || width <= 0 || height <= 0) {
            return false;
        }
        return x >= left && x <= left + width && y >= top && y <= top + height;
    }

    private ResizeCorner getResizeCorner(HudWidgetInstance widget, double mouseX, double mouseY) {
        if (widget == null || widget.lastWidth <= 0 || widget.lastHeight <= 0) {
            return null;
        }

        int left = widget.lastX;
        int top = widget.lastY;
        int right = widget.lastX + widget.lastWidth - 1;
        int bottom = widget.lastY + widget.lastHeight - 1;

        ResizeCorner bestCorner = null;
        double bestDistance = Double.MAX_VALUE;

        bestCorner = pickCorner(bestCorner, ResizeCorner.TOP_LEFT, mouseX, mouseY, left, top, bestDistance);
        if (bestCorner != null) {
            bestDistance = squaredDistance(mouseX, mouseY, left, top);
        }

        ResizeCorner candidate = pickCorner(bestCorner, ResizeCorner.TOP_RIGHT, mouseX, mouseY, right, top, bestDistance);
        if (candidate != bestCorner) {
            bestCorner = candidate;
            bestDistance = squaredDistance(mouseX, mouseY, right, top);
        }

        candidate = pickCorner(bestCorner, ResizeCorner.BOTTOM_LEFT, mouseX, mouseY, left, bottom, bestDistance);
        if (candidate != bestCorner) {
            bestCorner = candidate;
            bestDistance = squaredDistance(mouseX, mouseY, left, bottom);
        }

        candidate = pickCorner(bestCorner, ResizeCorner.BOTTOM_RIGHT, mouseX, mouseY, right, bottom, bestDistance);
        if (candidate != bestCorner) {
            bestCorner = candidate;
        }

        return bestCorner;
    }

    private ResizeCorner pickCorner(
        ResizeCorner currentCorner,
        ResizeCorner candidateCorner,
        double mouseX,
        double mouseY,
        double cornerX,
        double cornerY,
        double currentBestDistance
    ) {
        double distance = squaredDistance(mouseX, mouseY, cornerX, cornerY);
        double hitRadiusSq = RESIZE_HANDLE_HIT_RADIUS * RESIZE_HANDLE_HIT_RADIUS;
        if (distance > hitRadiusSq) {
            return currentCorner;
        }
        if (distance < currentBestDistance) {
            return candidateCorner;
        }
        return currentCorner;
    }

    private boolean beginResizingAt(HudWidgetInstance widget, ResizeCorner corner) {
        if (widget == null || corner == null) {
            return false;
        }

        closeContextMenu();

        if (!isSelected(widget)) {
            selectOnly(widget);
        }

        draggingWidget = null;
        dragStates.clear();
        resizingWidget = widget;
        resizingCorner = corner;
        resizeStates.clear();

        for (HudWidgetInstance selected : selectedWidgets) {
            double startWidth = Math.max(MIN_RESIZE_WIDTH, selected.lastWidth);
            double startHeight = Math.max(MIN_RESIZE_HEIGHT, selected.lastHeight);
            double startScale = clamp((selected.manualScaleX + selected.manualScaleY) * 0.5, MIN_MANUAL_SCALE, MAX_MANUAL_SCALE);

            selected.hasManualPosition = true;
            selected.hasManualAnchor = false;
            selected.manualScaleX = startScale;
            selected.manualScaleY = startScale;

            resizeStates.add(new ResizeState(
                selected,
                selected.lastX,
                selected.lastY,
                startWidth,
                startHeight,
                startScale
            ));
        }

        ResizeState primary = findResizeState(widget);
        if (primary == null) {
            return false;
        }

        resizeStartX = primary.startX();
        resizeStartY = primary.startY();
        resizeStartWidth = primary.startWidth();
        resizeStartHeight = primary.startHeight();
        resizeStartScale = primary.startScale();
        return true;
    }

    private boolean handleResizeDrag(double mouseX, double mouseY) {
        if (resizingWidget == null || resizingCorner == null || client.getWindow() == null || resizeStates.isEmpty()) {
            return false;
        }

        double screenWidth = client.getWindow().getGuiScaledWidth();
        double screenHeight = client.getWindow().getGuiScaledHeight();

        ResizeState primary = findResizeState(resizingWidget);
        if (primary == null) {
            return false;
        }

        double left0 = primary.startX();
        double top0 = primary.startY();
        double right0 = primary.startX() + primary.startWidth();
        double bottom0 = primary.startY() + primary.startHeight();

        double ratioX = 1.0;
        double ratioY = 1.0;

        switch (resizingCorner) {
            case TOP_LEFT -> {
                ratioX = (right0 - mouseX) / primary.startWidth();
                ratioY = (bottom0 - mouseY) / primary.startHeight();
            }
            case TOP_RIGHT -> {
                ratioX = (mouseX - left0) / primary.startWidth();
                ratioY = (bottom0 - mouseY) / primary.startHeight();
            }
            case BOTTOM_LEFT -> {
                ratioX = (right0 - mouseX) / primary.startWidth();
                ratioY = (mouseY - top0) / primary.startHeight();
            }
            case BOTTOM_RIGHT -> {
                ratioX = (mouseX - left0) / primary.startWidth();
                ratioY = (mouseY - top0) / primary.startHeight();
            }
        }

        double safeRatioX = Math.max(0.001, ratioX);
        double safeRatioY = Math.max(0.001, ratioY);
        double chosenRatio = chooseUniformResizeRatio(safeRatioX, safeRatioY);

        double maxArea = Math.max(1.0, screenWidth * screenHeight * MAX_SCREEN_AREA_RATIO);
        double minRatio = 0.001;
        double maxRatio = Double.POSITIVE_INFINITY;

        for (ResizeState state : resizeStates) {
            double minRatioBySize = Math.max(MIN_RESIZE_WIDTH / state.startWidth(), MIN_RESIZE_HEIGHT / state.startHeight());
            double minRatioByScale = MIN_MANUAL_SCALE / state.startScale();
            double maxRatioByScale = MAX_MANUAL_SCALE / state.startScale();
            double maxRatioByArea = Math.sqrt(maxArea / Math.max(1.0, state.startWidth() * state.startHeight()));
            double maxRatioByBounds = maxRatioByBounds(state, resizingCorner, screenWidth, screenHeight);

            minRatio = Math.max(minRatio, Math.max(minRatioBySize, minRatioByScale));
            maxRatio = Math.min(maxRatio, Math.min(Math.min(maxRatioByScale, maxRatioByArea), maxRatioByBounds));
        }

        if (maxRatio < minRatio) {
            minRatio = maxRatio;
        }

        double appliedRatio = clamp(chosenRatio, minRatio, maxRatio);

        for (ResizeState state : resizeStates) {
            double newScale = clamp(state.startScale() * appliedRatio, MIN_MANUAL_SCALE, MAX_MANUAL_SCALE);
            double normalizedRatio = state.startScale() <= 0.0 ? 1.0 : (newScale / state.startScale());

            double newWidth = Math.max(MIN_RESIZE_WIDTH, state.startWidth() * normalizedRatio);
            double newHeight = Math.max(MIN_RESIZE_HEIGHT, state.startHeight() * normalizedRatio);

            double left = state.startX();
            double top = state.startY();
            switch (resizingCorner) {
                case TOP_LEFT -> {
                    left = state.startX() + state.startWidth() - newWidth;
                    top = state.startY() + state.startHeight() - newHeight;
                }
                case TOP_RIGHT -> {
                    left = state.startX();
                    top = state.startY() + state.startHeight() - newHeight;
                }
                case BOTTOM_LEFT -> {
                    left = state.startX() + state.startWidth() - newWidth;
                    top = state.startY();
                }
                case BOTTOM_RIGHT -> {
                    left = state.startX();
                    top = state.startY();
                }
            }

            left = clamp(left, 0.0, Math.max(0.0, screenWidth - newWidth));
            top = clamp(top, 0.0, Math.max(0.0, screenHeight - newHeight));

            state.widget().hasManualPosition = true;
            state.widget().hasManualAnchor = false;
            state.widget().manualX = left;
            state.widget().manualY = top;
            state.widget().manualScaleX = newScale;
            state.widget().manualScaleY = newScale;
        }

        return true;
    }

    private static double chooseUniformResizeRatio(double ratioX, double ratioY) {
        if (Math.abs(ratioX - 1.0) >= Math.abs(ratioY - 1.0)) {
            return ratioX;
        }
        return ratioY;
    }

    private static double maxRatioByBounds(ResizeState state, ResizeCorner corner, double screenWidth, double screenHeight) {
        double right = state.startX() + state.startWidth();
        double bottom = state.startY() + state.startHeight();
        return switch (corner) {
            case TOP_LEFT -> Math.min(right / state.startWidth(), bottom / state.startHeight());
            case TOP_RIGHT -> Math.min((screenWidth - state.startX()) / state.startWidth(), bottom / state.startHeight());
            case BOTTOM_LEFT -> Math.min(right / state.startWidth(), (screenHeight - state.startY()) / state.startHeight());
            case BOTTOM_RIGHT -> Math.min((screenWidth - state.startX()) / state.startWidth(), (screenHeight - state.startY()) / state.startHeight());
        };
    }

    private ResizeState findResizeState(HudWidgetInstance widget) {
        if (widget == null) {
            return null;
        }

        for (ResizeState state : resizeStates) {
            if (state.widget() == widget) {
                return state;
            }
        }

        return null;
    }

    private void autoAnchorWidget(HudWidgetInstance widget, boolean clampInsideWindow) {
        if (widget == null || client.getWindow() == null) {
            return;
        }

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        double widgetWidth = Math.max(1.0, widget.lastWidth);
        double widgetHeight = Math.max(1.0, widget.lastHeight);

        double anchoredX = widget.manualX;
        double anchoredY = widget.manualY;
        if (clampInsideWindow) {
            double maxX = Math.max(0.0, screenWidth - widgetWidth);
            double maxY = Math.max(0.0, screenHeight - widgetHeight);
            anchoredX = clamp(anchoredX, 0.0, maxX);
            anchoredY = clamp(anchoredY, 0.0, maxY);
        }

        double centerX = anchoredX + (widgetWidth / 2.0);
        double centerY = anchoredY + (widgetHeight / 2.0);
        String anchor = selectAutoAnchor(centerX, centerY, screenWidth, screenHeight);

        double baseX = anchorBaseX(anchor, widgetWidth, screenWidth);
        double baseY = anchorBaseY(anchor, widgetHeight, screenHeight);

        widget.hasManualPosition = true;
        widget.hasManualAnchor = true;
        widget.manualAnchor = anchor;
        widget.manualX = anchoredX;
        widget.manualY = anchoredY;
        widget.manualOffsetX = anchoredX - baseX;
        widget.manualOffsetY = anchoredY - baseY;
        double uniformScale = clamp((widget.manualScaleX + widget.manualScaleY) * 0.5, MIN_MANUAL_SCALE, MAX_MANUAL_SCALE);
        widget.manualScaleX = uniformScale;
        widget.manualScaleY = uniformScale;
    }

    private static String selectAutoAnchor(double centerX, double centerY, int screenWidth, int screenHeight) {
        String horizontal;
        if (centerX < screenWidth / 3.0) {
            horizontal = "left";
        } else if (centerX > (screenWidth * 2.0) / 3.0) {
            horizontal = "right";
        } else {
            horizontal = "center";
        }

        String vertical;
        if (centerY < screenHeight / 3.0) {
            vertical = "top";
        } else if (centerY > (screenHeight * 2.0) / 3.0) {
            vertical = "bottom";
        } else {
            vertical = "middle";
        }

        if (vertical.equals("top")) {
            return switch (horizontal) {
                case "center" -> "top-center";
                case "right" -> "top-right";
                default -> "top-left";
            };
        }

        if (vertical.equals("bottom")) {
            return switch (horizontal) {
                case "center" -> "bottom-center";
                case "right" -> "bottom-right";
                default -> "bottom-left";
            };
        }

        return switch (horizontal) {
            case "center" -> "center";
            case "right" -> "middle-right";
            default -> "middle-left";
        };
    }

    private static double anchorBaseX(String anchor, double widgetWidth, int screenWidth) {
        return switch (anchor) {
            case "top-center", "center", "middle-center", "bottom-center" -> (screenWidth - widgetWidth) / 2.0;
            case "top-right", "middle-right", "bottom-right" -> screenWidth - widgetWidth;
            default -> 0.0;
        };
    }

    private static double anchorBaseY(String anchor, double widgetHeight, int screenHeight) {
        return switch (anchor) {
            case "middle-left", "center", "middle-right", "middle-center" -> (screenHeight - widgetHeight) / 2.0;
            case "bottom-left", "bottom-center", "bottom-right" -> screenHeight - widgetHeight;
            default -> 0.0;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private static double squaredDistance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return (dx * dx) + (dy * dy);
    }

    private HudWidgetInstance getTopMostWidget(double mouseX, double mouseY) {
        return getTopMostWidget(mouseX, mouseY, false);
    }

    private HudWidgetInstance getTopMostWidget(double mouseX, double mouseY, boolean preferHidden) {
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
            if (preferHidden && !widget.userVisible) {
                z += 1_000_000;
            }

            if (selected == null || z >= bestZIndex) {
                selected = widget;
                bestZIndex = z;
            }
        }

        return selected;
    }

    private boolean installMissingExtensionWidgets() {
        boolean changed = false;
        Set<String> modsWithHudDefinitions = new LinkedHashSet<>();

        for (HudifineHudRegistry.RegisteredHudDefinition definition : HudifineHudRegistry.getHudDefinitions()) {
            modsWithHudDefinitions.add(definition.ownerModId());

            String extensionKey = definition.uniqueKey();
            if (extensionKey == null || extensionKey.isBlank()) {
                continue;
            }
            if (dismissedExtensionKeys.contains(extensionKey)) {
                continue;
            }
            if (hasWidgetForExtensionKey(extensionKey)) {
                continue;
            }

            try {
                HudAst.HudScriptDocument document = new HudScriptParser(definition.script()).parse();
                HudWidgetInstance instance = new HudWidgetInstance(definition.script(), document);
                instance.extensionKey = extensionKey;
                applyDefaultSettings(instance);
                widgets.add(instance);
                changed = true;
            } catch (Exception parseError) {
                lastError = "Failed to load extension HUD '" + extensionKey + "': " + parseError.getMessage();
            }
        }

        if (installProviderFallbackWidgets(modsWithHudDefinitions)) {
            changed = true;
        }

        return changed;
    }

    private boolean installProviderFallbackWidgets(Set<String> modsWithHudDefinitions) {
        Map<String, List<HudifineProviderRegistry.ProviderInfo>> providersByMod = new LinkedHashMap<>();

        for (HudifineProviderRegistry.ProviderInfo provider : HudifineProviderRegistry.getProviders()) {
            if (provider == null || provider.ownerModId() == null || provider.ownerModId().isBlank()) {
                continue;
            }

            providersByMod
                .computeIfAbsent(provider.ownerModId(), ignored -> new ArrayList<>())
                .add(provider);
        }

        boolean changed = false;
        int fallbackIndex = 0;

        for (Map.Entry<String, List<HudifineProviderRegistry.ProviderInfo>> entry : providersByMod.entrySet()) {
            String modId = entry.getKey();
            if (modsWithHudDefinitions.contains(modId)) {
                continue;
            }

            String extensionKey = "provider-auto:" + modId;
            if (dismissedExtensionKeys.contains(extensionKey) || hasWidgetForExtensionKey(extensionKey)) {
                fallbackIndex++;
                continue;
            }

            String customScript = HudifineProviderRegistry.getFallbackHudScriptForMod(modId).trim();
            String script = customScript;
            if (script.isBlank()) {
                script = buildProviderFallbackScript(modId, entry.getValue(), fallbackIndex);
            }
            if (script.isBlank()) {
                fallbackIndex++;
                continue;
            }

            try {
                HudAst.HudScriptDocument document = new HudScriptParser(script).parse();
                HudWidgetInstance instance = new HudWidgetInstance(script, document);
                instance.extensionKey = extensionKey;
                applyDefaultSettings(instance);
                widgets.add(instance);
                changed = true;
            } catch (Exception parseError) {
                if (customScript.isBlank()) {
                    lastError = "Failed to build fallback HUD for provider mod '" + modId + "': " + parseError.getMessage();
                } else {
                    String generatedScript = buildProviderFallbackScript(modId, entry.getValue(), fallbackIndex);
                    if (!generatedScript.isBlank()) {
                        try {
                            HudAst.HudScriptDocument document = new HudScriptParser(generatedScript).parse();
                            HudWidgetInstance instance = new HudWidgetInstance(generatedScript, document);
                            instance.extensionKey = extensionKey;
                            applyDefaultSettings(instance);
                            widgets.add(instance);
                            changed = true;
                            lastError = "Failed to parse custom fallback HUD script for provider mod '" + modId + "'; used auto-generated fallback.";
                        } catch (Exception generatedParseError) {
                            lastError = "Failed to parse both custom and auto-generated fallback HUDs for provider mod '" + modId + "': " + generatedParseError.getMessage();
                        }
                    } else {
                        lastError = "Failed to parse custom fallback HUD script for provider mod '" + modId + "': " + parseError.getMessage();
                    }
                }
            }

            fallbackIndex++;
        }

        return changed;
    }

    private static String buildProviderFallbackScript(
        String modId,
        List<HudifineProviderRegistry.ProviderInfo> providers,
        int fallbackIndex
    ) {
        if (modId == null || modId.isBlank() || providers == null || providers.isEmpty()) {
            return "";
        }

        List<HudifineProviderRegistry.ProviderInfo> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparing(HudifineProviderRegistry.ProviderInfo::id));

        int maxLines = Math.min(5, sorted.size());
        int offsetY = 10 + (fallbackIndex * 56);

        String escapedModId = escapeHudString(modId);
        StringBuilder script = new StringBuilder();
        script.append("meta {\n");
        script.append("  name: \"").append(escapedModId).append(" HUD\"\n");
        script.append("  author: \"").append(escapedModId).append("\"\n");
        script.append("}\n\n");
        script.append("widget {\n");
        script.append("  anchor: top-right\n");
        script.append("  offsetX: -10\n");
        script.append("  offsetY: ").append(offsetY).append("\n");
        script.append("  background: #0b1320cc\n");
        script.append("  border: 1 #6dd3ff66\n");
        script.append("  padding: 6\n");
        script.append("  gap: 3\n");
        script.append("  direction: column\n\n");

        script.append("  text {\n");
        script.append("    value: \"").append(escapedModId).append("\"\n");
        script.append("    color: #9fb6cc\n");
        script.append("    fontSize: 10\n");
        script.append("  }\n");

        for (int i = 0; i < maxLines; i++) {
            HudifineProviderRegistry.ProviderInfo provider = sorted.get(i);
            String label = provider.displayName() == null || provider.displayName().isBlank()
                ? provider.id()
                : provider.displayName();

            script.append("  text {\n");
            script.append("    value: \"")
                .append(escapeHudString(label))
                .append(": {get(")
                .append(provider.id())
                .append(")}\"\n");
            script.append("    color: #ffffff\n");
            script.append("    fontSize: 11\n");
            script.append("    shadow: true\n");
            script.append("  }\n");
        }

        if (sorted.size() > maxLines) {
            script.append("  text {\n");
            script.append("    value: \"...\"\n");
            script.append("    color: #7f93a8\n");
            script.append("    fontSize: 9\n");
            script.append("  }\n");
        }

        script.append("}\n");
        return script.toString();
    }

    private static String escapeHudString(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private boolean ensureDefaultWidgetIfEmpty() {
        if (!widgets.isEmpty()) {
            return false;
        }

        try {
            HudAst.HudScriptDocument document = new HudScriptParser(DEFAULT_SCRIPT).parse();
            HudWidgetInstance instance = new HudWidgetInstance(DEFAULT_SCRIPT, document);
            applyDefaultSettings(instance);
            widgets.add(instance);
            return true;
        } catch (Exception parseError) {
            lastError = "Failed to load default HUD: " + parseError.getMessage();
            return false;
        }
    }

    private boolean hasWidgetForExtensionKey(String extensionKey) {
        for (HudWidgetInstance widget : widgets) {
            if (extensionKey.equals(widget.extensionKey)) {
                return true;
            }
        }

        return false;
    }

    private void markExtensionWidgetDismissed(HudWidgetInstance widget) {
        if (widget == null || widget.extensionKey == null) {
            return;
        }

        String key = widget.extensionKey.trim();
        if (!key.isBlank()) {
            dismissedExtensionKeys.add(key);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();

        JsonArray widgetArray = new JsonArray();
        for (HudWidgetInstance widget : widgets) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", widget.id.toString());
            entry.addProperty("script", widget.sourceScript);
            entry.addProperty("manualX", widget.manualX);
            entry.addProperty("manualY", widget.manualY);
            entry.addProperty("manualScaleX", widget.manualScaleX);
            entry.addProperty("manualScaleY", widget.manualScaleY);
            entry.addProperty("hasManualPosition", widget.hasManualPosition);
            entry.addProperty("hasManualAnchor", widget.hasManualAnchor);
            entry.addProperty("manualAnchor", widget.manualAnchor == null ? "top-left" : widget.manualAnchor);
            entry.addProperty("manualOffsetX", widget.manualOffsetX);
            entry.addProperty("manualOffsetY", widget.manualOffsetY);
            entry.addProperty("userVisible", widget.userVisible);
            if (widget.extensionKey != null && !widget.extensionKey.isBlank()) {
                entry.addProperty("extensionKey", widget.extensionKey);
            }

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

        JsonArray dismissedArray = new JsonArray();
        for (String extensionKey : dismissedExtensionKeys) {
            dismissedArray.add(extensionKey);
        }
        root.add("dismissedExtensionKeys", dismissedArray);

        try {
            Files.writeString(configPath, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void load() {
        widgets.clear();
        dismissedExtensionKeys.clear();

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

            JsonElement dismissedElement = root.get("dismissedExtensionKeys");
            if (dismissedElement != null && dismissedElement.isJsonArray()) {
                for (JsonElement dismissed : dismissedElement.getAsJsonArray()) {
                    if (dismissed != null && dismissed.isJsonPrimitive()) {
                        String key = dismissed.getAsString().trim();
                        if (key != null && !key.isBlank()) {
                            dismissedExtensionKeys.add(key);
                        }
                    }
                }
            }

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
                    double loadedScaleX = clamp(
                        entry.has("manualScaleX") ? entry.get("manualScaleX").getAsDouble() : 1.0,
                        MIN_MANUAL_SCALE,
                        MAX_MANUAL_SCALE
                    );
                    double loadedScaleY = clamp(
                        entry.has("manualScaleY") ? entry.get("manualScaleY").getAsDouble() : loadedScaleX,
                        MIN_MANUAL_SCALE,
                        MAX_MANUAL_SCALE
                    );
                    double loadedScale = clamp((loadedScaleX + loadedScaleY) * 0.5, MIN_MANUAL_SCALE, MAX_MANUAL_SCALE);
                    instance.manualScaleX = loadedScale;
                    instance.manualScaleY = loadedScale;
                    instance.hasManualAnchor = entry.has("hasManualAnchor") && entry.get("hasManualAnchor").getAsBoolean();
                    instance.manualAnchor = entry.has("manualAnchor")
                        ? entry.get("manualAnchor").getAsString().toLowerCase()
                        : "top-left";
                    instance.manualOffsetX = entry.has("manualOffsetX") ? entry.get("manualOffsetX").getAsDouble() : 0.0;
                    instance.manualOffsetY = entry.has("manualOffsetY") ? entry.get("manualOffsetY").getAsDouble() : 0.0;
                    instance.userVisible = !entry.has("userVisible") || entry.get("userVisible").getAsBoolean();
                    instance.extensionKey = entry.has("extensionKey") ? entry.get("extensionKey").getAsString().trim() : "";

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

    private record ContextMenuEntry(String label, ContextMenuAction action, boolean enabled) {
    }

    private record RenderOrderEntry(HudWidgetInstance widget, int zIndex, int insertionIndex) {
    }

    private record DeleteConfirmLayout(
        int x,
        int y,
        int width,
        int height,
        int confirmX,
        int cancelX,
        int buttonY,
        int buttonWidth,
        int buttonHeight
    ) {
    }

    private record SnapDelta(double deltaX, double deltaY) {
    }

    private record DragState(HudWidgetInstance widget, double startX, double startY, double width, double height) {
    }

    private record ResizeState(
        HudWidgetInstance widget,
        double startX,
        double startY,
        double startWidth,
        double startHeight,
        double startScale
    ) {
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
