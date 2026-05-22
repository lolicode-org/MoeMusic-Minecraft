# AGENTS.md

This document serves as working guidance for AI agents and developers working on the MoeMusic Minecraft platform integrations on the `version/1.20.1` branch.

## Project Baseline & Branch Strategy

- **Multi-Version Maintenance**: This branch targets **Minecraft 1.20.1**.
- **Java Target Split**: Loader-facing modules like `:platform-common`, `:fabric`, and `:neoforge` target **Java 17**.
- **Gradle Builds**: The platform build targets Java 17 and uses standard Minecraft dev plugin layouts for that version. But using JDK 17 may fail due to toolchain requirements, if that happens, build with JDK 21 while still targeting Java 17 bytecode.

## Architecture Boundaries

- `:platform-common` adapts core music logic to Minecraft concepts: commands, permissions, networking handlers, audio output, client UI/HUD. This module is source-only and compiled directly into each loader module.
- `:fabric` owns Fabric-only bootstrap, lifecycle wiring, permission-provider bridge, and optional integrations (e.g., Mod Menu).
- `:neoforge` (and `:forge` on older branches) owns NeoForge-only bootstrap, lifecycle wiring, permission bridge, and config-screen integration.
- **Dependency Guard**: Keep loader-specific APIs and dependencies out of `:platform-common` unless the public API is truly loader-neutral.

## Minecraft Platform Adaptation Guidelines (Minecraft 1.20.1)

### Resource Location
- Minecraft 1.20.1 has public `ResourceLocation(namespace, path)` constructors. Fabric 1.20.1 does not expose the newer `ResourceLocation.fromNamespaceAndPath(...)` helper, so use the direct constructor.

### GUI & HUD Callbacks
- GUI callbacks use `renderBackground(GuiGraphics)` and `mouseScrolled(mouseX, mouseY, amount)`.
- `DeltaTracker` does not exist in 1.20.1; HUD callbacks receive a float partial tick instead.
- The keybinds screen is located at `net.minecraft.client.gui.screens.controls.KeyBindsScreen`.

### F3 Debug Overlay
- To check if the full F3 debug overlay screen is open, use `Minecraft.options.renderDebug` (do not use the 26.1.x `Minecraft.debugEntries.isOverlayVisible()`).

### Forge HUD Layering
- Forge 1.20.1 HUD overlays use `RegisterGuiOverlaysEvent` plus `VanillaGuiOverlay`, not `AddGuiOverlayLayersEvent` / `ForgeLayeredDraw`.

### System Toasts
- `SystemToast` uses the fixed `SystemToastIds` enum, and toasts cannot be force-hidden through `forceHide()`.
- `SystemToast.reset(...)` does not preserve multiline wrapping; enqueue duplicate `SystemToast.multiline(...)` toasts when wrapped text matters. For update-by-id semantics with wrapped text, use `org.lolicode.moemusic.platform.client.ui.ClientNotificationsKt.showWrappedSystemToast`.

### NativeImage & Cover Art Memory Safety
- `NativeImage.read(byte[])` copies payloads onto LWJGL's `MemoryStack`. Large cover-art payloads can trigger an `OutOfMemoryError: Out of stack space`. Use `NativeImage.read(InputStream)` or convert `BufferedImage -> NativeImage` to avoid allocating on the memory stack.

### Dev Dependency Issues (Bad Packets Forge)
- Forge 1.20.1 dev runs cannot load the raw `lol.bai:badpackets:forge-0.4.3` jar directly in MCP/Mojmap userdev because its mixin bytecode/refmap carries SRG member names (e.g. `f_9745_` / `m_7026_`).
- Dev setup uses Forge Renamer with `map2srg`, `reverse=true`, and `naiveSrg=true` for the dev dependency, then patches the dev-only `badpackets.mixins.json` to omit the stale `refmap` entry so Mixin keeps the renamed method targets.

## Networking Integration (Bad Packets 0.4.x)

- **Receiver Registration**: Bad Packets 0.4.x does not have `api.play.PlayPackets`. Use `C2SPacketReceiver.register(...)` and `S2CPacketReceiver.register(...)` to handle packets.
- **Channel Declaration**: Receiver registration serves as the channel declaration path on Bad Packets 0.4.x. Declare C→S and S→C channels on *both* sides before sending packets.
- **Standby State**: Ensure any local transition to standby (due to instance lock wait or local disablement) halts client audio immediately and rejects incoming playback packets, even if the server has not acknowledged the state transition yet.

## Audio Integration (OpenAL)

- **Thread Context**: Background audio thread calls to OpenAL must bind the saved context handle first using `ALC10.alcMakeContextCurrent(handle)`.
- **State Cleanup**: Client audio state must be fully cleared on disconnect to prevent stale playback state from carrying over to world/main-menu transitions.

## Config & Settings Integration (Cloth Config)

- **Optional Runtime Dependency**: Cloth Config remains optional at runtime. Shared client UI must open settings through `ConfigScreenAccess` to fall back cleanly when the library is absent.
- **Structured Fields**: For structured repeated config rows, use `NestedListListEntry` to get native add/remove controls and validation without forcing users to type serialized strings.
- **Default Values**: Ensure config screens derive default values from config/plugin default constructors rather than pointing to the currently loaded instance, or the UI reset action will not function correctly.
