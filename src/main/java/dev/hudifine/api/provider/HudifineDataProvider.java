package dev.hudifine.api.provider;

/**
 * Base contract for all Hudifine data providers.
 *
 * <p>Implement one of the typed sub-interfaces when possible so your value type
 * is explicit for both your mod code and Hudifine integrations.</p>
 */
public interface HudifineDataProvider {
    /**
     * Returns a unique dot-notation ID, for example: {@code mymod.fps}.
     */
    String getId();

    /**
     * Returns a human-readable display name.
     */
    String getDisplayName();

    /**
     * Returns the current provider value.
     */
    Object getValueObject();
}
