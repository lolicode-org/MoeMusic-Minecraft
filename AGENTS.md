# AGENTS.md

This document serves as working guidance for AI agents and developers working on the MoeMusic Minecraft platform integrations on the `version/1.19` branch.

## Project Baseline & Branch Strategy

- **Multi-Version Maintenance**: This branch targets **Minecraft 1.19**.
- **Java Target Split**: Loader-facing modules like `:platform-common`, `:fabric`, and `:forge` target **Java 17**.
- **Gradle Builds**: The platform build compiles with Java 17 and uses standard Minecraft dev plugin layouts for that version. But using JDK 17 may fail due to toolchain requirements, if that happens, build with JDK 21 while still targeting Java 17 bytecode.

## Architecture Boundaries

- `:platform-common` adapts core music logic to Minecraft concepts: commands, permissions, networking handlers, audio output, client UI/HUD. This module is source-only and compiled directly into each loader module.
- `:fabric` owns Fabric-only bootstrap, lifecycle wiring, permission-provider bridge, and optional integrations (e.g., Mod Menu).
- `:forge` owns Forge-only bootstrap, lifecycle wiring, permission bridge, and config-screen integration.
- **Dependency Guard**: Keep loader-specific APIs and dependencies out of `:platform-common` unless the public API is truly loader-neutral.

## Minecraft Platform Adaptation Guidelines (Minecraft 1.19)

### Resource Location
- Minecraft 1.19 has public `ResourceLocation(namespace, path)` constructors. Fabric 1.19 does not expose `ResourceLocation.fromNamespaceAndPath(...)`, so use the direct constructor.

### GUI & HUD Rendering
- Client screens/HUD render through `PoseStack`, not vanilla `GuiGraphics`. This branch keeps a tiny `platform.client.ui.GuiGraphics` compatibility wrapper for fill/text/blit/hint helpers so the shared `:platform-common` screens and HUD can stay close to the 1.20 layout code.
- Uses older widget APIs such as `Button(x, y, w, h, message) { ... }`, `EditBox.setSuggestion(...)`, and `Vector3f.ZP`. Later additions like `Button.builder(...)`, `EditBox.setHint(...)`, and `Axis.ZP` are not available.
- Vanilla `Button` rendering samples a fixed 20px texture strip. Using it for 13-18px controls clips the bottom border and makes text look vertically off. Use the branch's compact button renderer for MoeMusic's small controls instead of raw vanilla `Button`.
- `EditBox.setSuggestion(...)` is an autocomplete suffix, not a placeholder. It intentionally renders after typed text when the cursor is at the end, so MoeMusic placeholders must be drawn separately.
- Fabric API 1.19 `HudRenderCallback` runs late enough for MoeMusic's HUD to draw over chat. Fabric 1.19 uses a client `Gui.render` mixin before `ChatComponent.render(...)`. That call site has already translated the `PoseStack` by `screenHeight - 48`, so undo that translation or inject before the chat `pushPose`. Forge keeps its loader overlay registration path.
- Check full-screen F3 debug overlay status via `Minecraft.options.renderDebug` instead of `Minecraft.debugOverlay.showDebugScreen()`.

### System Toasts
- `SystemToast` uses the fixed `SystemToastIds` enum, and toasts cannot be force-hidden through `forceHide()`.
- `SystemToast.reset(...)` does not preserve multiline wrapping; enqueue duplicate `SystemToast.multiline(...)` toasts when wrapped text matters. For update-by-id semantics with wrapped text, use `org.lolicode.moemusic.platform.client.ui.ClientNotificationsKt.showWrappedSystemToast`.

### NativeImage & Cover Art Memory Safety
- `NativeImage.read(byte[])` copies payloads onto LWJGL's `MemoryStack`. Large cover-art payloads can trigger an `OutOfMemoryError: Out of stack space`. Use `NativeImage.read(InputStream)` or convert `BufferedImage -> NativeImage` to avoid allocating on the memory stack.

### Fabric Mod Metadata
- Fabric API `0.58.0+1.19` exposes the aggregate loader mod id `fabric`, not `fabric-api`. `fabric.mod.json` dependencies must use `"fabric": "*"`, or dev/runtime resolution will fail even if Fabric API is present.

### Forge Kotlin Integration (Without KotlinForForge)
- Forge 1.19 runs without KotlinForForge on this branch.
- Use plain `javafml` bootstrap and bundle unrelocated Kotlin 2 stdlib/JDK7/JDK8/reflect plus common KotlinX runtime libraries (coroutines and serialization) in the Forge install/dev jar.
- Mark the Forge build as intentionally incompatible with KotlinForForge 3.x. Fabric continues to use Fabric Language Kotlin.
- **JarJar/Dependency Extraction Limitation**: Forge JarJar can negotiate a single dependency jar by Maven coordinate/version range, but it does not provide per-mod package isolation. Unrelocated bundled Kotlin still exports `kotlin.*` and `kotlinx.*` packages into Forge's shared loading environment, which can conflict with KotlinForForge or other mods bundling incompatible Kotlin runtime classes.

### Forge Dev Dependency & Build Quirks (Bad Packets Forge)
- Forge 1.19 `:forge:runClient` requires a dev-only Bad Packets remap. The published `badpackets:forge-0.1.3` jar carries SRG-style mixin bytecode/refmap entries (e.g. `f_9745_`).
- Dev setup uses Forge Renamer with `map2srg` (`reverse=true`, `naiveSrg=true`) on the dev dependency, then patches the dev-only `badpackets.mixins.json` to omit the stale `refmap` entry so Mixin keeps the renamed method targets (`MixinPlayerList`, `MixinServerGamePacketListenerImpl`, and `client.MixinClientPacketListener`).
- Forge 1.19's `unpackSharedRuntimeForDev` explodes Kotlin multi-release jars into a plain directory for userdev. This task requires explicit duplicate handling and should exclude `META-INF/versions/**` to prevent collisions on files like `module-info.class`.
- Forge 1.19 ForgeGradle 7 reobf/dev tasks should use `net.minecraft:mappings_official:1.19-20220607.102129:map2srg@tsrg.gz`.
- Forge 1.19 Mod List reads `mods.toml` `logoFile` through `AbstractPackResources.getRootResource(...)`, which only accepts a root filename. Keep Forge metadata at `logoFile="icon.png"` and copy the shared `assets/moemusic/icon.png` into the jar/dev-root resource root during `processResources`.

## Networking Integration (Bad Packets 0.1.x)

- **Channel Declaration**: Bad Packets 0.1.x does not have `api.play.PlayPackets`. Declare C→S and S→C channels on *both* sides before sending packets.
- **Standby State**: Ensure any local transition to standby (due to instance lock wait or local disablement) halts client audio immediately and rejects incoming playback packets, even if the server has not acknowledged the state transition yet.

## Audio Integration (OpenAL)

- **Thread Context**: Background audio thread calls to OpenAL must bind the saved context handle first using `ALC10.alcMakeContextCurrent(handle)`.
- **State Cleanup**: Client audio state must be fully cleared on disconnect to prevent stale playback state from carrying over to world/main-menu transitions.

## Config & Settings Integration (Cloth Config)

- **Optional Runtime Dependency**: Cloth Config remains optional at runtime. Shared client UI must open settings through `ConfigScreenAccess` to fall back cleanly when the library is absent.
- **Structured Fields**: For structured repeated config rows, use `NestedListListEntry` to get native add/remove controls and validation without forcing users to type serialized strings.
- **Default Values**: Ensure config screens derive default values from config/plugin default constructors rather than pointing to the currently loaded instance, or the UI reset action will not function correctly.
