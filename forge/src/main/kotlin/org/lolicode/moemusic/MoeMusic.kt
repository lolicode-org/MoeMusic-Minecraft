package org.lolicode.moemusic

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.FMLPaths
import org.lolicode.moemusic.core.session.ServerConnectionEventsDispatcher
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.forge.permission.ForgePermissionBridge
import org.lolicode.moemusic.platform.command.MusicCommands
import org.lolicode.moemusic.platform.network.NetworkSetup
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.lolicode.moemusic.platform.runtime.MoePlatform
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val MOEMUSIC_MOD_ID: String = "moemusic"
private const val KOTLIN_FOR_FORGE_MOD_ID: String = "kotlinforforge"

@Mod(MOEMUSIC_MOD_ID)
class MoeMusic {
    init {
        logger.info("MoeMusic server initializing...")
        warnIfKotlinForForgePresent()

        MoePlatform.configureRuntimeInfo(
            loaderId = MoeMusicForgeBuildInfo.LOADER_ID,
            modVersion = MoeMusicForgeBuildInfo.MOD_VERSION,
        )
        ForgePermissionBridge.install()
        NetworkSetup.setup(
            configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID),
            gameDir = FMLPaths.GAMEDIR.get(),
        )
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerClientModBusListeners()
        }

        MinecraftForge.EVENT_BUS.addListener(::onServerStarting)
        MinecraftForge.EVENT_BUS.addListener(::onServerStopping)
        MinecraftForge.EVENT_BUS.addListener(::onRegisterCommands)
        MinecraftForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
        MinecraftForge.EVENT_BUS.addListener(::onPlayerLoggedOut)

        logger.info("MoeMusic server initialized.")
    }

    private fun onServerStarting(event: ServerStartingEvent) {
        NetworkSetup.startServer()
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        MoePlatform.serverShutdown(finalRuntime = event.server.isDedicatedServer)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        MusicCommands.register(event.dispatcher)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val locale = UserSessionRegistry.localeFor(player.uuid) ?: "en_us"
        ServerConnectionEventsDispatcher.connected(MinecraftUserRegistry.snapshot(player, locale))
    }

    private fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val locale = UserSessionRegistry.localeFor(player.uuid) ?: "en_us"
        ServerConnectionEventsDispatcher.disconnected(MinecraftUserRegistry.snapshot(player, locale))
        NetworkSetup.handleDisconnect(player.uuid)
    }

    private fun registerClientModBusListeners() {
        Class.forName("org.lolicode.moemusic.client.MoeMusicClient")
            .getMethod("registerModBusListeners")
            .invoke(null)
    }

    private fun warnIfKotlinForForgePresent() {
        // In fact it will crash very early, much before this code runs, so this warning is basically useless. But keep it as a memo just in case...
        if (!ModList.get().isLoaded(KOTLIN_FOR_FORGE_MOD_ID)) return
        logger.warn(
            "Detected mod '{}' (KotlinForForge). It is very likely to conflict with MoeMusic because MoeMusic ships its own Kotlin libraries. " +
                "If crashes happen with both mods installed, this is a known issue and cannot be resolved in MoeMusic: " +
                "KotlinForForge for this Minecraft version has not been updated for a long time and ships Kotlin libraries that are too old for MoeMusic. " +
                "If you do not have a special reason to use Forge, use the Fabric variant instead, which does not conflict with other Kotlin mods. " +
                "Or, if you want to keep using Forge, please consider updating Minecraft to 1.20 or later, " +
                "where KotlinForForge will ship much newer Kotlin libraries that are compatible with MoeMusic.",
            KOTLIN_FOR_FORGE_MOD_ID,
        )
    }

    companion object {
        const val MOD_ID: String = MOEMUSIC_MOD_ID
        val logger: Logger = LoggerFactory.getLogger(MOD_ID)
    }
}
