package com.hudifine.client.ui;

import com.hudifine.client.hud.HudScriptManager;
import com.hudifine.client.hud.HudWidgetInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class HudScriptEditorScreen extends Screen {
    private final Screen parent;
    private final HudScriptManager manager;
    private final HudWidgetInstance editingWidget;

    private MultiLineEditBox scriptEditor;

    public HudScriptEditorScreen(Screen parent, HudScriptManager manager, HudWidgetInstance editingWidget) {
        super(Component.translatable("hudifine.editor.title"));
        this.parent = parent;
        this.manager = manager;
        this.editingWidget = editingWidget;
    }

    @Override
    protected void init() {
        int editorX = 16;
        int editorY = 40;
        int editorWidth = Math.max(200, width - 32);
        int editorHeight = Math.max(120, height - 98);

        scriptEditor = MultiLineEditBox.builder()
            .setX(editorX)
            .setY(editorY)
            .setPlaceholder(Component.literal("Paste or write your HUD script here"))
            .setTextColor(0xFFE8EEF5)
            .setCursorColor(0xFFFFFFFF)
            .setShowBackground(true)
            .setShowDecorations(true)
            .setTextShadow(false)
            .build(font, editorWidth, editorHeight, Component.empty());

        scriptEditor.setCharacterLimit(300000);
        scriptEditor.setLineLimit(3000);

        if (editingWidget != null) {
            scriptEditor.setValue(editingWidget.sourceScript);
        }

        addRenderableWidget(scriptEditor);

        int buttonY = height - 44;

        addRenderableWidget(Button.builder(Component.translatable("hudifine.editor.apply"), ignored -> applyScript())
            .bounds(16, buttonY, 100, 20)
            .build());

        addRenderableWidget(Button.builder(Component.translatable("hudifine.editor.paste"), ignored -> {
                if (minecraft != null) {
                    scriptEditor.setValue(minecraft.keyboardHandler.getClipboard());
                }
            })
            .bounds(122, buttonY, 100, 20)
            .build());

        addRenderableWidget(Button.builder(Component.translatable("hudifine.editor.clear"), ignored -> scriptEditor.setValue(""))
            .bounds(228, buttonY, 80, 20)
            .build());

        addRenderableWidget(Button.builder(Component.translatable("hudifine.editor.close"), ignored -> onClose())
            .bounds(width - 96, buttonY, 80, 20)
            .build());
    }

    private void applyScript() {
        manager.applyScript(scriptEditor.getValue(), editingWidget);

        if (!manager.getLastError().isEmpty() || minecraft == null) {
            return;
        }

        minecraft.setScreen(null);
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
        context.fillGradient(0, 0, width, height, 0xE00A1320, 0xE014253A);
        context.text(font, title, 16, 14, 0xFFFFFFFF, true);
        context.text(font, "Open chat + click widgets to drag. Right click widgets to customize.", 16, 27, 0xFF9FB6CC);

        super.extractRenderState(context, mouseX, mouseY, deltaTicks);

        if (!manager.getLastError().isEmpty()) {
            context.text(font, "Error: " + manager.getLastError(), 16, height - 68, 0xFFFF7A7A, true);
        }
    }
}
