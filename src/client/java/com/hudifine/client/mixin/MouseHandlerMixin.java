package com.hudifine.client.mixin;

import com.hudifine.client.HudifineClientMod;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private MouseButtonInfo activeButton;

    @Shadow
    public abstract double getScaledXPos(Window window);

    @Shadow
    public abstract double getScaledYPos(Window window);

    @Shadow
    public abstract boolean isLeftPressed();

    @Inject(method = "onMove", at = @At("TAIL"))
    private void hudifine$onMove(long window, double x, double y, CallbackInfo ci) {
        if (HudifineClientMod.getManager() == null || this.minecraft.getWindow() == null) {
            return;
        }

        Screen screen = this.minecraft.screen;
        if (!(screen instanceof ChatScreen)) {
            return;
        }

        boolean leftPressed = this.isLeftPressed() || GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!leftPressed) {
            return;
        }

        double mouseX = this.getScaledXPos(this.minecraft.getWindow());
        double mouseY = this.getScaledYPos(this.minecraft.getWindow());

        if (HudifineClientMod.getManager().isDragging()) {
            HudifineClientMod.getManager().handleMouseDragged(mouseX, mouseY);
        }
    }

    @Inject(method = "onButton", at = @At("TAIL"))
    private void hudifine$onButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (HudifineClientMod.getManager() == null || this.minecraft.getWindow() == null) {
            return;
        }

        if (action == GLFW.GLFW_PRESS && (button.button() == 0 || button.button() == 1)) {
            HudifineClientMod.getManager().recordGlobalClick(button.button());
        }

        Screen screen = this.minecraft.screen;
        if (!(screen instanceof ChatScreen)) {
            return;
        }

        if (button.button() != 0) {
            return;
        }

        if (action == GLFW.GLFW_RELEASE) {
            HudifineClientMod.getManager().handleMouseReleased();
        }
    }
}
