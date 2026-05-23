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
}
