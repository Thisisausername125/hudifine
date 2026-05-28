# Changelog

## 2.0.0

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
- Version bumped to 2.0.0.
- Default generated script metadata version bumped to 2.0.0.
- Integration guide dependency examples updated to 2.0.0.

### Fixed
- Fixed delete confirmation lifecycle issues where confirm UI could close unexpectedly during pointer release flow.
- Fixed context menu Settings eligibility: Settings is now disabled when no settings are present.
- Fixed context menu Settings eligibility for multi-select: Settings is disabled when selected widgets do not share the same script.
- Fixed mixed-visibility edge case by disabling Hide/Unhide action when multi-selection contains mixed visibility states.
- Fixed extension visibility issue where provider-only extension mods loaded data but produced no visible HUDs.
- Fixed provider-only integration limitation where custom widget styling could not be supplied through metadata-driven fallback scripts.
- Fixed rounded-rectangle translucency artifact where center region appeared darker from overlapping alpha fills.

### Latest 5 Bug Fixes (Summary)
- Fixed chat click interception by selection-box arming: unhandled clicks pass through, and marquee selection activates only after drag threshold.
- Fixed stale selection state in chat: unclaimed background clicks now clear HUD selection.
- Fixed Esc behavior in chat: pressing Esc now clears HUD selection and transient HUD UI state.
- Fixed marquee drag activation regression by using real left-button pressed state for drag updates.
- Fixed premature selection/drag release cleanup by handling only explicit left-button release and hardening button-state checks.

### Rendering
- Improved rounded corner smoothing with boundary multisample anti-aliasing.
- Removed expensive rounded-corner upscaling path in favor of no-upscale edge AA.
- Preserved rounded appearance while avoiding heavy superscale raster cost.

### Performance
- Added expression compilation caching in the HUD expression engine to reduce repeated parse/lex work per frame.
- Added template compilation caching for repeated string template evaluation.
- Reduced per-frame zIndex sort overhead by precomputing render-order entries once per frame.
- Improved rounded-corner rasterization strategy for better performance-to-quality balance.

### Latest Optimizations (Summary)
- Added literal style caches for renderer color and border parsing to avoid repeated per-frame parsing/allocation.
- Reduced container child-order overhead by skipping order sorts when no non-default `order` values are present.
- Added cached `DecimalFormat` instances for expression `format(...)` to remove repeated formatter construction.
- Added tick-scoped data-source caches for inventory counts, nearest-player scans, potion lists/maps, and cardinal direction lookup reuse.
- Reduced zIndex overhead by skipping zIndex expression evaluation and z-sort when widgets do not define custom zIndex values.

### UX
- Context menu rows now correctly gray out disabled actions with matching icon and text styling.
- Context actions now better reflect selection state and capability rules.
- In-editor keyboard shortcuts now only consume input when a HUD action is actually handled.

### Validation
- Repeated local build validation after feature and bug-fix patches.
- No diagnostics errors in modified source files at release preparation time.
