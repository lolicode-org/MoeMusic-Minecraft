package org.lolicode.moemusic

import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.fml.common.Mod
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
import thedarkcolour.kotlinforforge.forge.runWhenOn

@Mod(MoeMusic.MOD_ID)
object MoeMusic {
    const val MOD_ID: String = "moemusic"
    val logger: Logger = LoggerFactory.getLogger(MOD_ID)

    init {
        logger.info("MoeMusic server initializing...")

        MoePlatform.configureRuntimeInfo(
            loaderId = MoeMusicForgeBuildInfo.LOADER_ID,
            modVersion = MoeMusicForgeBuildInfo.MOD_VERSION,
        )
        ForgePermissionBridge.install()
        NetworkSetup.setup(
            configDir = FMLPaths.CONFIGDIR.get().resolve(MOD_ID),
            gameDir = FMLPaths.GAMEDIR.get(),
        )
        runWhenOn(Dist.CLIENT) {
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
}
