package dev.hudifine.api.provider;

/**
 * Optional metadata that enriches provider presentation in Hudifine UIs.
 */
public interface HudifineProviderMeta {
    default String getDescription() {
        return "";
    }

    default String getCategory() {
        return "custom";
    }

    default String getUnit() {
        return "";
    }

    /**
     * Optional HUDScript used when this mod only exposes {@code hudifine:provider}
     * and does not register {@code hudifine:hud}.
     *
     * <p>Return a full {@code widget { ... }} script to control fallback widget
     * styling and layout. Return blank to use Hudifine's auto-generated fallback.</p>
     */
    default String getFallbackHudScript() {
        return "";
    }
}
