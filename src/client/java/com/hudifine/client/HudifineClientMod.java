package com.hudifine.client;

import com.hudifine.client.hud.HudScriptManager;
import com.hudifine.client.ui.HudScriptEditorScreen;
import dev.hudifine.api.hud.HudifineHudRegistry;
import dev.hudifine.api.provider.HudifineProviderRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

public final class HudifineClientMod implements ClientModInitializer {
    private static final Identifier HUD_ELEMENT_ID = Identifier.fromNamespaceAndPath("hudifine", "script_hud");
    private static HudScriptManager manager;

    @Override
    public void onInitializeClient() {
        Minecraft client = Minecraft.getInstance();
        HudifineProviderRegistry.discoverEntrypointProviders();
        HudifineHudRegistry.discoverEntrypointHuds();
        manager = new HudScriptManager(client);
        manager.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(ignored -> manager.tick());
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, HUD_ELEMENT_ID, (drawContext, tickCounter) -> {
            manager.beginFrame();
            manager.renderHud(drawContext);
        });
    }

    public static HudScriptManager getManager() {
        return manager;
    }

    public static void openEditor(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new HudScriptEditorScreen(parent, manager, null));
    }
}
