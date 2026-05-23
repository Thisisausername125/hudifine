package dev.hudifine.api.hud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime registry for HUD script definitions contributed by extension mods.
 */
public final class HudifineHudRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("HudifineHudRegistry");
    private static final Pattern HUD_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*$");

    private static final Map<String, RegisteredHudDefinition> HUD_DEFINITIONS = new LinkedHashMap<>();

    private static boolean discovered;

    private HudifineHudRegistry() {
    }

    /**
     * Discovers and registers HUD definitions from {@code hudifine:hud} entrypoints.
     */
    public static synchronized void discoverEntrypointHuds() {
        if (discovered) {
            return;
        }
        discovered = true;

        List<EntrypointContainer<HudifineHudProvider>> containers;
        try {
            containers = FabricLoader.getInstance().getEntrypointContainers("hudifine:hud", HudifineHudProvider.class);
        } catch (Throwable throwable) {
            LOGGER.error("Failed to discover Hudifine HUD extensions.", throwable);
            return;
        }

        int loaded = 0;
        for (EntrypointContainer<HudifineHudProvider> container : containers) {
            String modId = container.getProvider().getMetadata().getId();
            HudifineHudProvider provider;
            try {
                provider = container.getEntrypoint();
            } catch (Throwable throwable) {
                LOGGER.warn("Failed to initialize Hudifine HUD entrypoint from mod '{}'.", modId, throwable);
                continue;
            }

            List<HudifineHudDefinition> definitions;
            try {
                definitions = provider.getHudDefinitions();
            } catch (Throwable throwable) {
                LOGGER.warn("Hudifine HUD entrypoint from mod '{}' threw while listing HUD definitions.", modId, throwable);
                continue;
            }

            if (definitions == null || definitions.isEmpty()) {
                continue;
            }

            for (HudifineHudDefinition definition : definitions) {
                if (registerInternal(definition, modId)) {
                    loaded++;
                }
            }
        }

        LOGGER.info("Loaded {} Hudifine HUD definition(s).", loaded);
    }

    /**
     * Returns immutable HUD definitions loaded from entrypoints.
     */
    public static synchronized List<RegisteredHudDefinition> getHudDefinitions() {
        return List.copyOf(new ArrayList<>(HUD_DEFINITIONS.values()));
    }

    private static boolean registerInternal(HudifineHudDefinition definition, String ownerModId) {
        if (definition == null) {
            LOGGER.warn("Ignoring null Hudifine HUD definition from '{}'.", ownerModId);
            return false;
        }

        String id = normalize(definition.id());
        if (!isValidId(id)) {
            LOGGER.warn("Ignoring Hudifine HUD definition from '{}': invalid HUD id '{}'.", ownerModId, definition.id());
            return false;
        }

        String script = definition.script() == null ? "" : definition.script().trim();
        if (script.isEmpty()) {
            LOGGER.warn("Ignoring Hudifine HUD definition '{}:{}': script is blank.", ownerModId, id);
            return false;
        }

        String displayName = definition.displayName() == null || definition.displayName().isBlank()
            ? id
            : definition.displayName();

        String uniqueKey = ownerModId + ":" + id;
        if (HUD_DEFINITIONS.containsKey(uniqueKey)) {
            LOGGER.warn("Ignoring duplicate Hudifine HUD definition '{}'.", uniqueKey);
            return false;
        }

        HUD_DEFINITIONS.put(uniqueKey, new RegisteredHudDefinition(uniqueKey, ownerModId, id, displayName, script));
        return true;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidId(String id) {
        return !id.isBlank() && HUD_ID_PATTERN.matcher(id).matches();
    }

    public record RegisteredHudDefinition(
        String uniqueKey,
        String ownerModId,
        String hudId,
        String displayName,
        String script
    ) {
    }
}
