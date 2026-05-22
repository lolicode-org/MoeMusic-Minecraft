# AGENTS.md

This document serves as working guidance for AI agents and developers working on the MoeMusic Minecraft platform integrations on the `version/1.18.2` branch.

## Project Baseline & Branch Strategy

- **Multi-Version Maintenance**: This branch targets **Minecraft 1.18.2**.
- **Java Target Split**: Loader-facing modules like `:platform-common`, `:fabric`, and `:forge` target **Java 17**.
- **Gradle Builds**: The platform build compiles with Java 17 and uses standard Minecraft dev plugin layouts for that version.

## Architecture Boundaries

- `:platform-common` adapts core music logic to Minecraft concepts: commands, permissions, networking handlers, audio output, client UI/HUD. This module is source-only and compiled directly into each loader module.
- `:fabric` owns Fabric-only bootstrap, lifecycle wiring, permission-provider bridge, and optional integrations (e.g., Mod Menu).
- `:forge` owns Forge-only bootstrap, lifecycle wiring, permission bridge, and config-screen integration.
- **Dependency Guard**: Keep loader-specific APIs and dependencies out of `:platform-common` unless the public API is truly loader-neutral.

## Minecraft Platform Adaptation Guidelines (Minecraft 1.18.2)

### Resource Location
- Minecraft 1.18.2 has public `ResourceLocation(namespace, path)` constructors. Fabric 1.18.2 does not expose the newer `ResourceLocation.fromNamespaceAndPath(...)` helper, so use the direct constructor.

### Text Components
- Minecraft 1.18.2 predates `Component.literal(...)` / `Component.translatable(...)`. You must use `TextComponent(...)` and `TranslatableComponent(...)` wrappers.

### Commands & Permissions
- Fabric API 1.18.2 uses `net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback`, whose callback is `(dispatcher, dedicated)`. The command API v2 registration path is a 1.19+ migration point.
- `CommandSourceStack` does not expose the newer nullable `player` accessor. Use `getPlayerOrException()`. When a command can originate from console or blocks, wrap it in a safety call: `runCatching { source.playerOrException }.getOrNull()`.
- `ServerPlayer` does not have the `sendSystemMessage(...)` method. Use `displayClientMessage(component, false)` to send messages to client chat.

### System Toasts
- `SystemToast` uses the fixed `SystemToastIds` enum, and toasts cannot be force-hidden through `forceHide()`.
- `SystemToast.reset(...)` does not preserve multiline wrapping; enqueue duplicate `SystemToast.multiline(...)` toasts when wrapped text matters. For update-by-id semantics with wrapped text, use `org.lolicode.moemusic.platform.client.ui.ClientNotificationsKt.showWrappedSystemToast`.
- `SystemToast.SystemToastIds` only defines legacy enum values such as `PERIODIC_NOTIFICATION`. Newer identifiers like `CHAT_PREVIEW_WARNING` do not exist.

### F3 Debug Overlay
- To check if the full F3 debug overlay screen is open, use `Minecraft.options.renderDebug` (do not use the 26.1.x `Minecraft.debugEntries.isOverlayVisible()`).

### NativeImage & Cover Art Memory Safety
- `NativeImage.read(byte[])` copies payloads onto LWJGL's `MemoryStack`. Large cover-art payloads can trigger an `OutOfMemoryError: Out of stack space`. Use `NativeImage.read(InputStream)` or convert `BufferedImage -> NativeImage` to avoid allocating on the memory stack.

### Forge Client Glue
- Legacy registry/event targets must be used for client hooks:
  - Keybindings: Use `ClientRegistry.registerKeyBinding(...)` instead of the later `RegisterKeyMappingsEvent`.
  - HUD Overlays: Use `OverlayRegistry.registerOverlayBelow(ForgeIngameGui.SUBTITLES_ELEMENT, ...)` instead of `RegisterGuiOverlaysEvent`.
  - Config Screen Factory: Use `ConfigGuiHandler.ConfigGuiFactory` instead of `ConfigScreenHandler`.
  - Client Playback Events: Listen to `ClientPlayerNetworkEvent.LoggedInEvent` and `ClientPlayerNetworkEvent.LoggedOutEvent` instead of the later `LoggingIn` and `LoggingOut` events.

### Forge Kotlin Integration (Without KotlinForForge)
- Forge 1.18.2 runs without KotlinForForge on this branch.
- Use plain `javafml` bootstrap and bundle unrelocated Kotlin 2 stdlib/reflect plus common KotlinX runtime libraries (coroutines and serialization) in the Forge install/dev jar.
- Mark the Forge build as intentionally incompatible with KotlinForForge 3.x. Fabric continues to use Fabric Language Kotlin.
- **JarJar/Dependency Extraction Limitation**: Forge JarJar does not provide package isolation. Unrelocated bundled Kotlin still exports `kotlin.*` and `kotlinx.*` packages into Forge's shared loading environment, which can conflict with KotlinForForge or other mods bundling incompatible Kotlin runtime classes.

### Forge Dev Dependency & Build Quirks (Bad Packets Forge)
- Forge 1.18.2 `:forge:runClient` requires a dev-only Bad Packets remap. The published `badpackets:forge` jar carries SRG-style mixin bytecode/refmap entries.
- Dev setup uses Forge Renamer with `map2srg` (`reverse=true`, `naiveSrg=true`) on the dev dependency, then patches the dev-only `badpackets.mixins.json` to omit the stale `refmap` entry so Mixin keeps the renamed method targets.
- Forge 1.18.2's `unpackSharedRuntimeForDev` explodes Kotlin multi-release jars into a plain directory for userdev. This task requires explicit duplicate handling and should exclude `META-INF/versions/**` to prevent collisions on files like `module-info.class`.
- Forge 1.18.2 Mod List reads `mods.toml` `logoFile` through `AbstractPackResources.getRootResource(...)`, which only accepts a root filename. Keep Forge metadata at `logoFile="icon.png"` and copy the shared `assets/moemusic/icon.png` into the jar/dev-root resource root during `processResources`.

### Dev Launch Configurations
- ForgeGradle 7 / Slime Launcher development runs under Java 17 require passing `--add-opens java.base/java.lang.invoke=ALL-UNNAMED` in the JVM arguments for `runClient` / `runServer`; otherwise, `net.minecraftforge.launcher.Main` fails to reflectively resolve `cpw.mods.bootstraplauncher.BootstrapLauncher.main(...)`.

## Networking Integration (Bad Packets)

- **Channel Declaration**: Declare C→S and S→C channels on *both* sides before sending packets.
- **Standby State**: Ensure any local transition to standby (due to instance lock wait or local disablement) halts client audio immediately and rejects incoming playback packets, even if the server has not acknowledged the state transition yet.

## Audio Integration (OpenAL)

- **Thread Context**: Background audio thread calls to OpenAL must bind the saved context handle first using `ALC10.alcMakeContextCurrent(handle)`.
- **State Cleanup**: Client audio state must be fully cleared on disconnect to prevent stale playback state from carrying over to world/main-menu transitions.

## Config & Settings Integration (Cloth Config)

- **Optional Runtime Dependency**: Cloth Config remains optional at runtime. Shared client UI must open settings through `ConfigScreenAccess` to fall back cleanly when the library is absent.
- **Structured Fields**: For structured repeated config rows, use `NestedListListEntry` to get native add/remove controls and validation without forcing users to type serialized strings.
- **Default Values**: Ensure config screens derive default values from config/plugin default constructors rather than pointing to the currently loaded instance, or the UI reset action will not function correctly.
