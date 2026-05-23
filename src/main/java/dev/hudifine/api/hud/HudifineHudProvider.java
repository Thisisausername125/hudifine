package dev.hudifine.api.hud;

import java.util.List;

/**
 * Fabric entrypoint contract for shipping ready-to-use HUD scripts.
 */
public interface HudifineHudProvider {
    /**
     * Returns HUD definitions contributed by this mod.
     */
    List<HudifineHudDefinition> getHudDefinitions();
}
