package dev.hudifine.api.provider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime registry for Hudifine data providers.
 */
public final class HudifineProviderRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("HudifineProviderRegistry");
    private static final Pattern PROVIDER_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+$");

    private static final Map<String, RegisteredProvider> PROVIDERS = new LinkedHashMap<>();
    private static final Set<String> REPORTED_FAILURES = new HashSet<>();

    private static boolean discovered;

    private HudifineProviderRegistry() {
    }

    /**
     * Discovers and registers providers from {@code hudifine:provider} entrypoints.
     */
    public static synchronized void discoverEntrypointProviders() {
        if (discovered) {
            return;
        }
        discovered = true;

        List<EntrypointContainer<HudifineDataProvider>> containers;
        try {
            containers = FabricLoader.getInstance().getEntrypointContainers("hudifine:provider", HudifineDataProvider.class);
        } catch (Throwable throwable) {
            LOGGER.error("Failed to discover Hudifine providers.", throwable);
            return;
        }

        int loaded = 0;
        for (EntrypointContainer<HudifineDataProvider> container : containers) {
            String modId = container.getProvider().getMetadata().getId();
            HudifineDataProvider provider;
            try {
                provider = container.getEntrypoint();
            } catch (Throwable throwable) {
                LOGGER.warn("Failed to initialize Hudifine provider entrypoint from mod '{}'.", modId, throwable);
                continue;
            }

            if (registerInternal(provider, modId)) {
                loaded++;
            }
        }

        LOGGER.info("Loaded {} Hudifine data provider(s).", loaded);
    }

    /**
     * Registers a provider instance at runtime.
     */
    public static synchronized boolean register(HudifineDataProvider provider) {
        return registerInternal(provider, "runtime");
    }

    /**
     * Returns immutable provider metadata for all currently-registered providers.
     */
    public static synchronized List<ProviderInfo> getProviders() {
        List<ProviderInfo> list = new ArrayList<>();
        for (RegisteredProvider registered : PROVIDERS.values()) {
            HudifineDataProvider provider = registered.provider();
            String description = "";
            String category = "custom";
            String unit = "";
            if (provider instanceof HudifineProviderMeta meta) {
                description = safeText(meta.getDescription());
                category = safeText(meta.getCategory());
                unit = safeText(meta.getUnit());
            }

            list.add(new ProviderInfo(
                registered.id(),
                safeText(provider.getDisplayName()),
                registered.ownerModId(),
                provider.getClass().getName(),
                description,
                category,
                unit
            ));
        }
        return List.copyOf(list);
    }

    /**
     * Resolves a provider value by ID.
     */
    public static Object getValue(String id) {
        RegisteredProvider registered;
        synchronized (HudifineProviderRegistry.class) {
            registered = PROVIDERS.get(id);
        }

        if (registered == null) {
            return null;
        }

        try {
            return registered.provider().getValueObject();
        } catch (Throwable throwable) {
            boolean firstFailure;
            synchronized (HudifineProviderRegistry.class) {
                firstFailure = REPORTED_FAILURES.add(id);
            }

            if (firstFailure) {
                LOGGER.warn("Hudifine provider '{}' failed while resolving value; returning empty value until restart.", id, throwable);
            }
            return null;
        }
    }

    /**
     * Returns a custom fallback HUDScript for a provider mod, if one was supplied
     * through {@link HudifineProviderMeta#getFallbackHudScript()}.
     */
    public static synchronized String getFallbackHudScriptForMod(String ownerModId) {
        if (ownerModId == null || ownerModId.isBlank()) {
            return "";
        }

        String chosenScript = "";
        String chosenProviderId = "";

        for (RegisteredProvider registered : PROVIDERS.values()) {
            if (!ownerModId.equals(registered.ownerModId())) {
                continue;
            }

            HudifineDataProvider provider = registered.provider();
            if (!(provider instanceof HudifineProviderMeta meta)) {
                continue;
            }

            String script = safeText(meta.getFallbackHudScript()).trim();
            if (script.isBlank()) {
                continue;
            }

            if (chosenScript.isBlank()) {
                chosenScript = script;
                chosenProviderId = registered.id();
                continue;
            }

            if (!chosenScript.equals(script)) {
                LOGGER.warn(
                    "Multiple Hudifine providers from mod '{}' supplied different fallback scripts. " +
                        "Using script from provider '{}' and ignoring provider '{}'.",
                    ownerModId,
                    chosenProviderId,
                    registered.id()
                );
            }
        }

        return chosenScript;
    }

    private static boolean registerInternal(HudifineDataProvider provider, String ownerModId) {
        if (provider == null) {
            LOGGER.warn("Ignoring null Hudifine provider from '{}'.", ownerModId);
            return false;
        }

        String rawId;
        try {
            rawId = provider.getId();
        } catch (Throwable throwable) {
            LOGGER.warn("Ignoring Hudifine provider from '{}' because getId() threw.", ownerModId, throwable);
            return false;
        }

        String id = normalizeId(rawId);
        if (!isValidProviderId(id)) {
            LOGGER.warn("Ignoring Hudifine provider '{}' from '{}': IDs must be dot-separated lowercase identifiers.", rawId, ownerModId);
            return false;
        }

        if (PROVIDERS.containsKey(id)) {
            LOGGER.warn("Ignoring duplicate Hudifine provider ID '{}' from '{}'.", id, ownerModId);
            return false;
        }

        PROVIDERS.put(id, new RegisteredProvider(id, ownerModId, provider));
        return true;
    }

    private static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidProviderId(String id) {
        return !id.isBlank() && PROVIDER_ID_PATTERN.matcher(id).matches();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private record RegisteredProvider(String id, String ownerModId, HudifineDataProvider provider) {
    }

    public record ProviderInfo(
        String id,
        String displayName,
        String ownerModId,
        String providerClass,
        String description,
        String category,
        String unit
    ) {
    }
}
