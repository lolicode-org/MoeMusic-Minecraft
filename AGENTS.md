# AGENTS.md

This document serves as working guidance for AI agents and developers working on the MoeMusic Minecraft platform integrations (`platform/` repository).

## Project Baseline & Branch Strategy

- **Multi-Version Maintenance**: The repository uses separate Git branches to target different Minecraft versions (this worktree is `version/26.2`). The default branch always points to the latest supported Minecraft version.
- **Java Target Split**: Loader-facing modules like `:platform-common`, `:fabric`, and `:neoforge` compile at the Java level required by the target Minecraft version (Java 25 for Minecraft 26.1+, Java 21 for 1.21.1, and Java 17 for 1.20.1/1.19/1.18.2). The Java 17 targets should still be compiled with JDK 21 because some toolchains will not work with older JDKs, but the bytecode target should be set to 17.
- **Gradle Builds**: The platform build supports two dependency modes: local sibling composite build (`includeBuild("../shared")`) for development, and consuming published core artifacts from Maven repositories.

## Architecture Boundaries

- `:platform-common` adapts core music logic to Minecraft concepts: commands, permissions, networking handlers, audio output, client UI/HUD. This module is source-only and compiled directly into each loader module.
- `:fabric` owns Fabric-only bootstrap, lifecycle wiring, permission-provider bridge, and optional integrations (e.g., Mod Menu).
- `:neoforge` (and `:forge` on older branches) owns NeoForge-only bootstrap, lifecycle wiring, permission bridge, and config-screen integration.
- **Dependency Guard**: Keep loader-specific APIs and dependencies out of `:platform-common` unless the public API is truly loader-neutral.

## Minecraft Platform Adaptation Guidelines (Minecraft 26.3)

### Replace GLFW with SDL
Mojang has replaced GLFW with SDL in 26.3. MoeMusic does not directly use any GLFW method, but worth keeping in mind.

### Keyboard
`InputConstants.Type.KEYSYM` -> `InputConstants.Type.KEYBOARD`. That's part of the SDL migration mentioned above.

**Avoid** using literal number for keycode: they're different on GLFW and SDL. To reduce future maintenance cost, use InputConstants.KEY_xxx.

---
Below are 26.2 concepts. Most still apply to 26.3. Check carefully if you notice any mismatch.

### Resource & Command Identifiers
- Use `net.minecraft.resources.Identifier`.
- Do not use `Identifier.of(...)`; use `Identifier.fromNamespaceAndPath(namespace, path)` or `Identifier.parse(...)`.

### Permissions & Commands
- Vanilla permission checks: use `permissions().hasPermission(Permission.HasCommandLevel(PermissionLevel.byId(level)))`. Do not use the deprecated `hasPermissions(int)` method.
- Use `org.lolicode.moemusic.platform.text.McText` helpers instead of direct vanilla text component constructors, as the text API changes across versions.
- For interactive command output, continue using `MutableComponent.withStyle { ... }` with click/hover events. Do not introduce new component code that depends on `ChatFormatting` internals; in 26.2 `ChatFormatting` is no longer the general component color/format representation.

### GUI Ownership
- In 26.2 the current screen, overlays, toasts, and many HUD-facing methods live under `Minecraft.gui` / `Minecraft.gui.hud`, not directly on `Minecraft`.
- Shared client UI should use `org.lolicode.moemusic.platform.client.ui.MinecraftGuiAccess` for screen reads and writes (`mc.screen`, `mc.setScreen(...)`). If code cannot use that helper, call `mc.gui.screen()` and `mc.gui.setScreen(...)` directly.
- Do not call removed 26.1-era APIs such as `Minecraft.screen`, `Minecraft.setScreen(...)`, or `Minecraft.toastManager`.
- Toasts use `minecraft.gui.toastManager()`. `SystemToast.multiline(...)` is gone in 26.2; use `SystemToast.add(...)` or `SystemToast.addOrUpdate(...)`. MoeMusic uses `addOrUpdate` for repeated runtime warnings/tips so they refresh instead of stacking.
- If vanilla HUD messages/titles are needed, route them through `minecraft.gui.hud`. Player overlay messages can still use `player.sendOverlayMessage(...)`.

### GUI & HUD Rendering
- Minecraft 26.2 added the Vulkan backend path. GUI/HUD code in this mod must not use raw OpenGL, `RenderSystem` state pokes, or classes under `com.mojang.blaze3d.opengl`; keep rendering through Minecraft's GUI extraction/render pipeline.
- Use `GuiGraphicsExtractor` for custom screens and HUD rendering. Text should be submitted with extractor helpers such as `text`, `centeredText`, and `textWithWordWrap`; do not add direct `Font.drawInBatch` usage, as the old font draw methods were removed.
- For HUD transformation matrix operations, use `GuiGraphicsExtractor.pose()` which returns a `Matrix3x2fStack`. Use `pushMatrix()`, `translate()`, `rotate()`, `scale()`, and `popMatrix()`.
- HUD element registration is loader-specific: Fabric uses `HudElementRegistry.attachElementBefore(...)`/`attachElementAfter(...)`, while NeoForge uses `RegisterGuiLayersEvent` (`registerBelowAll` for MoeMusic's now-playing HUD).
- Custom screens should render through `extractRenderState(...)` and call `super.extractRenderState(...)` once. Do not manually duplicate background extraction.

### Dynamic Cover Art & Textures
- GUI colors passed to the graphics extractor are packed ARGB integers. RGB-only literals (e.g., `0xFFFFFF`) will render fully transparent.
- Use `DynamicTexture({ label }, image)` for runtime cover textures and draw them via `blit(RenderPipelines.GUI_TEXTURED, id, ...)`.
- Register and release runtime cover textures on the Minecraft client thread (`Minecraft.execute { ... }`). Close `NativeImage`s yourself only when registration fails or when you created temporary intermediate images; registered `DynamicTexture`s own their image.

### Keybindings
- Keybinding categories use `KeyMapping.Category` values, not raw category strings. A category like `moemusic:general` translates `id.toLanguageKey("key.category")`, requiring `key.category.moemusic.general` in the translation file.

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
