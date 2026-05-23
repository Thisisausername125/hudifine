# Hudifine Mod Integration Guide

Hudifine exposes a provider API that lets Fabric mods register custom runtime data.
Once registered, values are available in HUDScript through `get(...)`, exactly like built-ins such as `get(perf.fps)` or `get(player.health)`.

## Requirements

- Fabric Loader `>=0.18.6`
- Minecraft `26.1.2`
- Hudifine installed on the client

## 1. Add the Hudifine API dependency

When published as a standalone API artifact:

```groovy
dependencies {
  modImplementation "com.hudifine:hudifine-api:2.0.0-beta"
}
```

Or depend on the main Hudifine mod artifact if you are using bundled API classes.

In your `fabric.mod.json`, add a required dependency:

```json
{
  "depends": {
    "hudifine": ">=2.0.0-beta"
  }
}
```

## 2. Implement a provider

Available interfaces in `dev.hudifine.api.provider`:

- `IntDataProvider`
- `FloatDataProvider`
- `StringDataProvider`
- `BooleanDataProvider`

Example:

```java
import dev.hudifine.api.provider.IntDataProvider;
import net.minecraft.client.Minecraft;

public final class FpsProvider implements IntDataProvider {
    @Override
    public String getId() {
        return "mymod.fps";
    }

    @Override
    public String getDisplayName() {
        return "FPS Counter";
    }

    @Override
    public int getValue() {
        return Minecraft.getInstance().getFps();
    }
}
```

ID rules:

- Must be dot-separated lowercase identifiers, for example `mymod.fps`
- Must be unique
- Invalid or duplicate IDs are rejected at load time with a Hudifine warning log

## 3. Register via Fabric entrypoint

Add your provider class to `hudifine:provider` in your `fabric.mod.json`:

```json
{
  "entrypoints": {
    "hudifine:provider": [
      "com.example.mymod.FpsProvider"
    ]
  }
}
```

You can register multiple providers:

```json
{
  "entrypoints": {
    "hudifine:provider": [
      "com.example.mymod.FpsProvider",
      "com.example.mymod.PingProvider"
    ]
  }
}
```

Hudifine discovers these entrypoints automatically during client initialization.

Compatibility fallback:

- If your mod registers only `hudifine:provider` and no `hudifine:hud` entrypoint, Hudifine auto-generates a basic HUD panel so users still see your values immediately.
- Registering `hudifine:hud` gives full control over layout and styling and is recommended for production extensions.

## 4. Ship a HUD extension that auto-appears

If you want your mod to include a ready-to-use HUD widget (not just data values), implement `dev.hudifine.api.hud.HudifineHudProvider`.

Example provider:

```java
import dev.hudifine.api.hud.HudifineHudDefinition;
import dev.hudifine.api.hud.HudifineHudProvider;
import java.util.List;

public final class ExampleHudProvider implements HudifineHudProvider {
    @Override
    public List<HudifineHudDefinition> getHudDefinitions() {
        return List.of(new HudifineHudDefinition(
            "fps_panel",
            "FPS Panel",
            """
            widget {
              anchor: top-right
              offsetX: -10
              offsetY: 10
              background: #00000066
              padding: 6

              text {
                value: \"{get(mymod.fps)} FPS\"
                color: #ffffff
                fontSize: 12
                shadow: true
              }
            }
            """
        ));
    }
}
```

Register it under `hudifine:hud`:

```json
{
  "entrypoints": {
    "hudifine:hud": [
      "com.example.mymod.ExampleHudProvider"
    ]
  }
}
```

Behavior:

- HUD definitions are discovered on startup
- Missing extension HUDs are auto-added to the player's widget list
- If a player deletes an extension HUD, Hudifine remembers that dismissal and does not auto-add it again

## 5. Use data in HUDScript

```hud
widget {
  anchor: top-right
  offsetX: -10
  offsetY: 10
  background: #00000066
  padding: 6

  text {
    value: "{get(mymod.fps)} FPS"
    color: #ffffff
    fontSize: 12
    shadow: true
  }
}
```

## 6. Optional metadata

Implement `HudifineProviderMeta` for richer provider metadata:

```java
import dev.hudifine.api.provider.HudifineProviderMeta;
import dev.hudifine.api.provider.IntDataProvider;

public final class FpsProvider implements IntDataProvider, HudifineProviderMeta {
    @Override
    public String getId() { return "mymod.fps"; }

    @Override
    public String getDisplayName() { return "FPS Counter"; }

    @Override
    public int getValue() { return 0; }

    @Override
    public String getDescription() { return "Current rendered frames per second."; }

    @Override
    public String getCategory() { return "performance"; }

    @Override
    public String getUnit() { return "fps"; }
}
```

## 7. Performance guidance

`getValue()` runs every frame.

- Cache expensive computations
- Prefer updating cache on ticks/events
- Keep `getValue()` to cheap field reads where possible

Example cache pattern:

```java
import dev.hudifine.api.provider.IntDataProvider;

public final class EntityCountProvider implements IntDataProvider {
    private int cachedCount;

    public void onClientTick(int currentCount) {
        this.cachedCount = currentCount;
    }

    @Override
    public String getId() { return "mymod.entityCount"; }

    @Override
    public String getDisplayName() { return "Nearby Entity Count"; }

    @Override
    public int getValue() { return cachedCount; }
}
```

## Summary

1. Add the Hudifine API dependency
2. Implement a typed provider with a dot-notation ID
3. Register provider class(es) under `hudifine:provider`
4. Optionally register `hudifine:hud` to ship auto-installing HUD widgets
5. Consume data in HUDScript with `get(your.id)`
6. Keep provider reads fast
