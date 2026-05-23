package dev.hudifine.api.provider;

/**
 * Provider that returns string values.
 */
public interface StringDataProvider extends HudifineDataProvider {
    String getValue();

    @Override
    default Object getValueObject() {
        return getValue();
    }
}
