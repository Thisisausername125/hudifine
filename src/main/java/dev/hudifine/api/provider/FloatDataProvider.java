package dev.hudifine.api.provider;

/**
 * Provider that returns float values.
 */
public interface FloatDataProvider extends HudifineDataProvider {
    float getValue();

    @Override
    default Object getValueObject() {
        return getValue();
    }
}
