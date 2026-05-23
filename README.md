# Hudifine

Hudifine is a Fabric 26.1.2 client mod that provides a script-driven HUD system with:

- A + button in chat to open the script editor
- Paste-and-run HUD scripts
- Dragging widgets while chat is open
- Right-click widget customization menus for script settings
- A runtime-enforced 30% per-widget screen budget

## Mod Provider API

Hudifine includes a provider API so other mods can expose values to HUDScript through `get(...)`.

- Providers are discovered from the Fabric entrypoint key `hudifine:provider`
- Registered values become script-readable by ID, for example `get(mymod.fps)`
- Mods can also ship full HUD widgets via the `hudifine:hud` entrypoint
- Provider-only extensions automatically receive a basic generated HUD panel for compatibility

Integration docs: [docs/mod-integration-guide.md](docs/mod-integration-guide.md)

## Development

Requirements:

- JDK 21
- Gradle 9+

Build:

```powershell
gradle build
```

Run client:

```powershell
gradle runClient
```
