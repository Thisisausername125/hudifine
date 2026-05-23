package dev.hudifine.api.provider;

/**
 * Provider that returns boolean values.
 */
public interface BooleanDataProvider extends HudifineDataProvider {
    boolean getValue();

    @Override
    default Object getValueObject() {
        return getValue();
    }
}
