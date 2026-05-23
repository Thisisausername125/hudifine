package dev.hudifine.api.hud;

/**
 * Describes a HUD script exposed by an extension mod.
 */
public record HudifineHudDefinition(String id, String displayName, String script) {
    public HudifineHudDefinition {
        id = id == null ? "" : id;
        displayName = displayName == null ? "" : displayName;
        script = script == null ? "" : script;
    }
}
