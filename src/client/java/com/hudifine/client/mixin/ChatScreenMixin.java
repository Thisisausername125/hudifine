package com.hudifine.client.mixin;

import com.hudifine.client.HudifineClientMod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    private static final int HUDIFINE_PLUS_MARGIN = 8;
    private static final int HUDIFINE_PLUS_SIZE = 20;

    private double hudifine$scaledMouseX() {
        return this.minecraft.mouseHandler.getScaledXPos(this.minecraft.getWindow());
    }

    private double hudifine$scaledMouseY() {
        return this.minecraft.mouseHandler.getScaledYPos(this.minecraft.getWindow());
    }

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void hudifine$init(CallbackInfo ci) {
        // Keep this injection so mixin remains active when chat initializes.
    }

    private int hudifine$plusButtonX() {
        return this.width - HUDIFINE_PLUS_SIZE - HUDIFINE_PLUS_MARGIN;
    }

    private int hudifine$plusButtonY() {
        return HUDIFINE_PLUS_MARGIN;
    }

    private boolean hudifine$isPlusHovered(double mouseX, double mouseY) {
        int buttonX = hudifine$plusButtonX();
        int buttonY = hudifine$plusButtonY();

        return mouseX >= buttonX
            && mouseX <= buttonX + HUDIFINE_PLUS_SIZE
            && mouseY >= buttonY
            && mouseY <= buttonY + HUDIFINE_PLUS_SIZE;
    }

    private void hudifine$renderPlusButton(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int buttonX = hudifine$plusButtonX();
        int buttonY = hudifine$plusButtonY();
        boolean hovered = hudifine$isPlusHovered(mouseX, mouseY);

        int background = hovered ? 0xD05582A6 : 0xC0223B4F;

        context.fill(buttonX, buttonY, buttonX + HUDIFINE_PLUS_SIZE, buttonY + HUDIFINE_PLUS_SIZE, background);

        String plus = "+";
        int textX = buttonX + (HUDIFINE_PLUS_SIZE - this.font.width(plus)) / 2;
        int textY = buttonY + (HUDIFINE_PLUS_SIZE - this.font.lineHeight) / 2;
        context.text(this.font, plus, textX, textY, 0xFFFFFFFF, true);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void hudifine$renderEditorOverlay(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        int scaledMouseX = (int) Math.round(hudifine$scaledMouseX());
        int scaledMouseY = (int) Math.round(hudifine$scaledMouseY());
        boolean plusHovered = hudifine$isPlusHovered(scaledMouseX, scaledMouseY);
        int overlayMouseX = plusHovered ? Integer.MIN_VALUE / 4 : scaledMouseX;
        int overlayMouseY = plusHovered ? Integer.MIN_VALUE / 4 : scaledMouseY;

        if (HudifineClientMod.getManager() != null) {
            HudifineClientMod.getManager().renderEditorOverlay(context, overlayMouseX, overlayMouseY);
        }

        // Render the plus button after overlays so it never picks up or appears behind widget resize corners.
        hudifine$renderPlusButton(context, scaledMouseX, scaledMouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void hudifine$mouseClicked(MouseButtonEvent click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() == 0 && hudifine$isPlusHovered(click.x(), click.y())) {
            HudifineClientMod.openEditor((Screen) (Object) this);
            cir.setReturnValue(true);
            return;
        }

        if (HudifineClientMod.getManager() == null) {
            return;
        }

        boolean shiftDown = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        boolean handled = HudifineClientMod.getManager().handleMouseClick(
            (Screen) (Object) this,
            click.x(),
            click.y(),
            click.button(),
            shiftDown
        );
        if (handled) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void hudifine$keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (HudifineClientMod.getManager() == null) {
            return;
        }

        boolean controlDown = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (controlDown && event.key() == GLFW.GLFW_KEY_C) {
            if (HudifineClientMod.getManager().handleCopySelectionShortcut()) {
                cir.setReturnValue(true);
            }
            return;
        }

        if (controlDown && event.key() == GLFW.GLFW_KEY_V) {
            if (HudifineClientMod.getManager().handlePasteSelectionShortcut()) {
                cir.setReturnValue(true);
            }
            return;
        }

        if (event.key() != GLFW.GLFW_KEY_BACKSPACE) {
            return;
        }

        if (HudifineClientMod.getManager().handleDeleteSelectionShortcut()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void hudifine$clearDragOnClose(CallbackInfo ci) {
        if (HudifineClientMod.getManager() != null) {
            HudifineClientMod.getManager().handleMouseReleased();
            HudifineClientMod.getManager().closeTransientUi();
        }
    }
}
