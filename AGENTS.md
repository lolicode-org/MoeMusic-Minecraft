# AGENTS.md

This document serves as working guidance for AI agents and developers working on the MoeMusic Minecraft platform integrations on the `version/1.21.1` branch.

## Project Baseline & Branch Strategy

- **Multi-Version Maintenance**: This branch targets **Minecraft 1.21.1**.
- **Java Target Split**: Loader-facing modules like `:platform-common`, `:fabric`, and `:neoforge` target **Java 21**. Platform mixin configs must declare `compatibilityLevel = JAVA_21`; leaving it at the newer `JAVA_25` level will compile but crash at runtime.
- **Gradle Builds**: Fabric Loom 1.16 requires running Gradle under **JDK 21**. Fabric uses the remapped Fabric Cloth Config artifact (`libs.cloth.config.fabric`), whereas NeoForge and Forge must use their respective loader-specific Cloth Config dependencies.

## Architecture Boundaries

- `:platform-common` adapts core music logic to Minecraft concepts: commands, permissions, networking handlers, audio output, client UI/HUD. This module is source-only and compiled directly into each loader module.
- `:fabric` owns Fabric-only bootstrap, lifecycle wiring, permission-provider bridge, and optional integrations (e.g., Mod Menu).
- `:neoforge` (and `:forge` on older branches) owns NeoForge-only bootstrap, lifecycle wiring, permission bridge, and config-screen integration.
- **Dependency Guard**: Keep loader-specific APIs and dependencies out of `:platform-common` unless the public API is truly loader-neutral.

## Minecraft Platform Adaptation Guidelines (Minecraft 1.21.1)

### Sound Engine Integration
- In Minecraft 1.21.1, `SoundEngine.play(SoundInstance)` returns `void`. Client-side mixins that intercept or cancel sound playback must modify/cancel the `CallbackInfo` parameter rather than checking for a `PlayResult`.

### F3 Debug Overlay
- To check if the full F3 debug overlay screen is open, use `Minecraft.debugOverlay.showDebugScreen()` (do not use `debugEntries.isOverlayVisible()`).

### Keybinding Registration
- Fabric 1.21.1 keybinding registration uses `net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper`, not the newer `client.keymapping.v1.KeyMappingHelper` used in 26.1.x.

### HUD Layering & Config Screen (Forge/NeoForge)
- NeoForge 21.1.x does not support `ClientStartedEvent` or `ClientStoppingEvent`. Use `FMLClientSetupEvent` for client setup work and `GameShuttingDownEvent` for client shutdown hooks.
- Forge 1.21.1 HUD layering uses `AddGuiOverlayLayersEvent` and `ForgeLayeredDraw.addBelow(...)` with `POST_SLEEP_STACK` (do not use NeoForge's `RegisterGuiLayersEvent` / `VanillaGuiLayers`).
- Forge 1.21.1 config-screen registration: use `ModContainer.registerExtensionPoint(ConfigScreenFactory::class.java, Supplier { ... })`.

### NativeImage & Cover Art Memory Safety
- `NativeImage.read(byte[])` copies payloads onto LWJGL's `MemoryStack`. Large cover-art payloads can trigger an `OutOfMemoryError: Out of stack space`. Use `NativeImage.read(InputStream)` or convert `BufferedImage -> NativeImage` to avoid allocating on the memory stack.

### JPMS & Classloading (Forge / NeoForge 1.21.1)
- Avoid split packages between Forge-specific glue and platform-common (keep Forge permission bridges under `org.lolicode.moemusic.forge.*`, not `org.lolicode.moemusic.platform.*`).
- Compile Forge Kotlin and Java outputs, including shared `platform-common` sources, directly into `build/resources/main` so they reside in the same development module.
- NeoForge 21.1 ModDev dev runs load mod classes through FML's module classloader; plain `implementation`/`runtimeClasspath` dependencies are not enough for MoeMusic shared classes or shaded libraries. Register a generated dev source set that unpacks MoeMusic `api`/`core`/`client-core` plus uniquely packaged shaded runtime libraries (e.g., LavaPlayer, Wire/Okio, Ktoml, kotlinx-datetime), rather than placing the entire shared runtime on `additionalRuntimeClasspath` which collides with KotlinForForge.
- ForgeGradle 7 dev runs require adding MDK repositories (`minecraft.mavenizer(this)`, `fg.forgeMaven`, `fg.minecraftLibsMaven`) in the Forge subproject, and Kotlin compilation needs an explicit compile-only view of the mavenized Forge API jar plus JavaFML/FML/eventbus/Brigadier/SLF4J classes.
- Forge 1.21.1 Kotlin object client bootstraps should register MOD-bus listeners explicitly from the `@Mod` init path. Do not rely on `@Mod.EventBusSubscriber` static discovery for the Kotlin `MoeMusicClient` object.
- Forge 1.21.1 / ForgeGradle 7 requires advertising mixins through `META-INF/MANIFEST.MF` `MixinConfigs` on both the exploded dev mod root (`build/resources/main`) and the final shaded Forge jar.
- IDEA debug runs on 1.21.1 Forge/NeoForge can crash on coroutine launch if IntelliJ's Kotlin coroutine agent is attached. Keep Kotlin/coroutines owned by KotlinForForge, and disable IntelliJ's "Attach coroutine agent" in IDE settings.

### Union Resource URLs (NeoForge 21.1)
- NeoForge 21.1 / SecureJar exposes resources via `union:` URLs. Resource loaders parsing files from the classloader must treat `union:` as a listable NIO path.

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
