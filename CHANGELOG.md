# Changelog

## 2.0.0-beta

### Added
- HUD selection clipboard shortcuts in editor mode.
- Ctrl+C to copy selected HUD elements.
- Ctrl+V to paste duplicated HUD elements from the copied selection.
- Backspace shortcut to open delete confirmation for selected HUD elements.
- Drag-box marquee selection on empty-space drag, with additive selection support via Shift.
- Context menu action to copy widget code (enabled for a single selection).
- Public provider API support for external mods via hudifine:provider entrypoint and registry integration.
- Public HUD extension API support for external mods via hudifine:hud entrypoint and registry integration.
- Automatic installation of extension-provided HUD widgets.
- Provider-only compatibility fallback that auto-generates visible HUD widgets when no hudifine:hud entrypoint is present.
- Provider-only fallback style override via `HudifineProviderMeta#getFallbackHudScript()` for custom widget styling without a separate `hudifine:hud` entrypoint.
- Persistent storage for extension widget ownership and dismissed extension keys.
- New release changelog file.

### Changed
- Removed multi-select floating TRASH and HIDE/SHOW overlay buttons.
- Consolidated multi-select destructive/visibility actions into the context menu.
- Version bumped to 2.0.0-beta.
- Default generated script metadata version bumped to 2.0.0-beta.
- Integration guide dependency examples updated to 2.0.0-beta.

### Fixed
- Fixed delete confirmation lifecycle issues where confirm UI could close unexpectedly during pointer release flow.
- Fixed context menu Settings eligibility: Settings is now disabled when no settings are present.
- Fixed context menu Settings eligibility for multi-select: Settings is disabled when selected widgets do not share the same script.
- Fixed mixed-visibility edge case by disabling Hide/Unhide action when multi-selection contains mixed visibility states.
- Fixed extension visibility issue where provider-only extension mods loaded data but produced no visible HUDs.
- Fixed provider-only integration limitation where custom widget styling could not be supplied through metadata-driven fallback scripts.
- Fixed rounded-rectangle translucency artifact where center region appeared darker from overlapping alpha fills.

### Rendering
- Improved rounded corner smoothing with boundary multisample anti-aliasing.
- Removed expensive rounded-corner upscaling path in favor of no-upscale edge AA.
- Preserved rounded appearance while avoiding heavy superscale raster cost.

### Performance
- Added expression compilation caching in the HUD expression engine to reduce repeated parse/lex work per frame.
- Added template compilation caching for repeated string template evaluation.
- Reduced per-frame zIndex sort overhead by precomputing render-order entries once per frame.
- Improved rounded-corner rasterization strategy for better performance-to-quality balance.

### UX
- Context menu rows now correctly gray out disabled actions with matching icon and text styling.
- Context actions now better reflect selection state and capability rules.
- In-editor keyboard shortcuts now only consume input when a HUD action is actually handled.

### Validation
- Repeated local build validation after feature and bug-fix patches.
- No diagnostics errors in modified source files at release preparation time.
