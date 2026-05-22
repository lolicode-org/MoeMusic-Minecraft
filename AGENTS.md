# AGENTS.md

This document serves as working guidance for AI agents and developers working on the MoeMusic Minecraft platform integrations (`platform/` repository).

## Project Baseline & Branch Strategy

- **Multi-Version Maintenance**: The repository uses separate Git branches to target different Minecraft versions (e.g., `version/26.1`). The default branch always points to the latest supported Minecraft version.
- **Java Target Split**: Loader-facing modules like `:platform-common`, `:fabric`, and `:neoforge` compile at the Java level required by the target Minecraft version (Java 25 for Minecraft 26.1.x, Java 21 for 1.21.1, and Java 17 for 1.20.1/1.19/1.18.2). The Java 17 targets should still be compiled with JDK 21 because some toolchains will not work with older JDKs, but the bytecode target should be set to 17.
- **Gradle Builds**: The platform build supports two dependency modes: local sibling composite build (`includeBuild("../shared")`) for development, and consuming published core artifacts from Maven repositories.

## Architecture Boundaries

- `:platform-common` adapts core music logic to Minecraft concepts: commands, permissions, networking handlers, audio output, client UI/HUD. This module is source-only and compiled directly into each loader module.
- `:fabric` owns Fabric-only bootstrap, lifecycle wiring, permission-provider bridge, and optional integrations (e.g., Mod Menu).
- `:neoforge` (and `:forge` on older branches) owns NeoForge-only bootstrap, lifecycle wiring, permission bridge, and config-screen integration.
- **Dependency Guard**: Keep loader-specific APIs and dependencies out of `:platform-common` unless the public API is truly loader-neutral.

## Minecraft Platform Adaptation Guidelines (Minecraft 26.1.x)

### Resource & Command Identifiers
- Use `net.minecraft.resources.Identifier`.
- Do not use `Identifier.of(...)`; use `Identifier.fromNamespaceAndPath(namespace, path)` or `Identifier.parse(...)`.

### Permissions & Commands
- Vanilla permission checks: use `permissions().hasPermission(Permission.HasCommandLevel(PermissionLevel.byId(level)))`. Do not use the deprecated `hasPermissions(int)` method.
- Use `org.lolicode.moemusic.platform.text.McText` helpers instead of direct vanilla text component constructors, as the text API changes across versions.

### GUI & HUD Rendering
- Many render methods on the graphics extractor lost their `draw`/`render` prefixes. Use `GuiGraphicsExtractor` for HUD rendering.
- For HUD transformation matrix operations, use `GuiGraphicsExtractor.pose()` which returns a `Matrix3x2fStack`. Use `pushMatrix()`, `translate()`, `rotate()`, `scale()`, and `popMatrix()`.
- HUD element registration on Fabric uses `HudElementRegistry.attachElementAfter(...)`/`attachElementBefore` with a `HudElement` callback.
- Screen background blur: `Screen.extractRenderStateWithTooltipAndSubtitles(...)` already calls `extractBackground(...)`. Custom screens should not call `extractBackground(...)` again to avoid tripping the blur-per-frame guard.

### Dynamic Cover Art & Textures
- GUI colors passed to the graphics extractor are packed ARGB integers. RGB-only literals (e.g., `0xFFFFFF`) will render fully transparent.
- Use `DynamicTexture({ label }, image)` for runtime cover textures and draw them via `blit(RenderPipelines.GUI_TEXTURED, id, ...)`.

### Keybindings
- Keybinding categories use `KeyMapping.Category`. A category like `moemusic:general` translates `id.toLanguageKey("key.category")`, requiring `key.category.moemusic.general` in the translation file.

## Networking Integration (Bad Packets)

- **Channel Declaration**: Bad Packets requires declaring S→C and C→S channels on *both* sides before registering receivers or sending packets.
- **Standby State**: Ensure any local transition to standby (due to instance lock wait or local disablement) halts client audio immediately and rejects incoming playback packets, even if the server has not acknowledged the state transition yet.

## Audio Integration (OpenAL)

- **Thread Context**: Background audio thread calls to OpenAL must bind the saved context handle first using `ALC10.alcMakeContextCurrent(handle)`.
- **State Cleanup**: Client audio state must be fully cleared on disconnect to prevent stale playback state from carrying over to world/main-menu transitions.

## Config & Settings Integration (Cloth Config)

- **Optional Runtime Dependency**: Cloth Config remains optional at runtime. Shared client UI must open settings through `ConfigScreenAccess` to fall back cleanly when the library is absent.
- **Structured Fields**: For structured repeated config rows, use `NestedListListEntry` to get native add/remove controls and validation without forcing users to type serialized strings.
- **Default Values**: Ensure config screens derive default values from config/plugin default constructors rather than pointing to the currently loaded instance, or the UI reset action will not function correctly.
