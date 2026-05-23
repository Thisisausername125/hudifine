package dev.hudifine.api.provider;

/**
 * Provider that returns integer values.
 */
public interface IntDataProvider extends HudifineDataProvider {
    int getValue();

    @Override
    default Object getValueObject() {
        return getValue();
    }
}
