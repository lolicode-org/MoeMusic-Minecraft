package org.lolicode.moemusic.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.api.service.PlaybackActionOutcome
import org.lolicode.moemusic.core.audio.LavaPlayerNativeBootstrap
import org.lolicode.moemusic.core.media.probe.MediaProbeServiceImpl
import org.lolicode.moemusic.core.permission.PermissionServiceImpl
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import org.lolicode.moemusic.core.runtime.ServerPluginServices
import org.lolicode.moemusic.core.runtime.ServerRuntimeAdapter
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.session.ServerConnectionEventsDispatcher
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.user.UserActionServiceImpl
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID

@Plugin(
    id = "moemusic",
    name = "MoeMusic",
    version = MoeMusicVelocityPlugin.VERSION,
    description = "Server-wide music playback for Velocity",
    authors = ["Lolicode"],
)
class MoeMusicVelocityPlugin @Inject constructor(
    val proxy: ProxyServer,
    val logger: Logger,
    @DataDirectory val dataDirectory: Path,
) {
    private lateinit var channel: VelocityNetworkChannel
    private lateinit var commands: VelocityMusicCommand
    private var closed = false

    @Subscribe
    fun onProxyInitialize(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        check(!closed) { "MoeMusic cannot be initialized after shutdown." }
        instance = this

        channel = VelocityNetworkChannel(this)
        channel.register()
        LavaPlayerNativeBootstrap.configure(dataDirectory)
        ServerRuntimeCoordinator.serverInit(
            channel = channel,
            configDir = dataDirectory,
            adapter = RuntimeAdapter,
            pluginServicesFactory = ::pluginServices,
        )

        commands = VelocityMusicCommand(this)
        proxy.commandManager.register(
            proxy.commandManager.metaBuilder("music").plugin(this).build(),
            commands.command(),
        )
        proxy.allPlayers.forEach { ServerConnectionEventsDispatcher.connected(VelocityUser.snapshot(it)) }
        logger.info("MoeMusic server initialized for Velocity.")
    }

    @Subscribe
    fun onPostLogin(event: PostLoginEvent) {
        if (closed) return
        ServerConnectionEventsDispatcher.connected(VelocityUser.snapshot(event.player))
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        if (closed) return
        val player = event.player
        channel.forgetPlayer(player.uniqueId)
        ServerConnectionEventsDispatcher.disconnected(VelocityUser.snapshot(player))
        handleDisconnect(player.uniqueId)
    }

    @Subscribe
    fun onPluginMessage(event: com.velocitypowered.api.event.connection.PluginMessageEvent) {
        if (!closed) channel.handle(event)
    }

    @Subscribe
    fun onProxyShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        close()
    }

    fun handleRegisteredClientLeave(userId: UUID) {
        val session = UserSessionRegistry.session(userId) ?: return
        if (session.participation != UserSessionRegistry.Participation.ACTIVE) return
        UserSessionRegistry.standby(userId)
        VelocityVoteManager.onLeave(userId)
        if (UserSessionRegistry.activeCount() == 0) {
            ServerRuntimeCoordinator.releaseNativeAudienceLeaseIfHeld()
        }
    }

    internal fun close() {
        if (closed) return
        closed = true
        if (::commands.isInitialized) commands.close()
        if (::channel.isInitialized) channel.unregister()
        runCatching { ServerRuntimeCoordinator.serverShutdown(finalRuntime = true) }
            .onFailure { logger.error("Failed to shut down MoeMusic.", it) }
    }

    internal fun schedule(block: () -> Unit) {
        if (!closed) proxy.scheduler.buildTask(this, Runnable(block)).schedule()
    }

    internal fun runOnProxyThread(block: () -> Unit) = schedule(block)

    internal fun hasPermission(userId: UUID, permission: String, defaultLevel: Int): Boolean {
        if (closed) return false
        val player = proxy.getPlayer(userId).orElse(null) ?: return false
        return when (player.getPermissionValue(permission)) {
            com.velocitypowered.api.permission.Tristate.TRUE -> true
            com.velocitypowered.api.permission.Tristate.FALSE -> false
            com.velocitypowered.api.permission.Tristate.UNDEFINED -> defaultLevel <= 0
        }
    }

    private fun handleDisconnect(userId: UUID) {
        val removed = UserSessionRegistry.disconnect(userId) ?: return
        if (removed.participation == UserSessionRegistry.Participation.ACTIVE) {
            VelocityVoteManager.onLeave(userId)
            if (UserSessionRegistry.activeCount() == 0) {
                ServerRuntimeCoordinator.releaseNativeAudienceLeaseIfHeld()
            }
        }
    }

    private fun pluginServices(
        playbackController: org.lolicode.moemusic.core.playback.ServerPlaybackController,
        trackSubmissionService: org.lolicode.moemusic.core.playback.TrackSubmissionService,
        requestRateLimiter: RequestRateLimiter,
    ): ServerPluginServices {
        val permissions = PermissionServiceImpl()
        return ServerPluginServices(
            permissionService = permissions,
            userActionService = UserActionServiceImpl(
                permissionService = permissions,
                requestRateLimiter = requestRateLimiter,
                searchService = PluginManager.searchService,
                identifierResolutionService = PluginManager.identifierResolutionService,
                trackSubmissionService = trackSubmissionService,
                playbackController = playbackController,
                voteToSkipHandler = { id ->
                    val vote = VelocityVoteManager.request(id)
                    PlaybackActionOutcome(vote.success, vote.failure)
                },
            ),
            mediaProbeService = MediaProbeServiceImpl(),
        )
    }

    private object RuntimeAdapter : ServerRuntimeAdapter {
        override fun onUserQueueTrackSkipped(track: TrackInfo, reason: LocalizedText?) {
            val title = track.title.ifBlank { track.id.ifBlank { "queued track" } }
            instance.runOnProxyThread {
                VelocityUsers.allActive().forEach { user ->
                    val message = if (reason == null) {
                        LocalizedText.key("action.moemusic.playback.queue_track_skipped", title)
                    } else {
                        LocalizedText.key("action.moemusic.playback.queue_track_skipped_reason", title, reason)
                    }
                    user.player()?.let { VelocityChat.failure(it, message) }
                }
            }
        }

        override fun onTrackSubmitted(track: TrackInfo, result: TrackAddResult) {
            val title = track.title.ifBlank { track.id.ifBlank { "queued track" } }
            val submitter = track.submittedByUserName?.takeIf(String::isNotBlank) ?: "Server"
            instance.runOnProxyThread {
                VelocityUsers.allActive().forEach { user ->
                    user.player()?.let {
                        VelocityChat.success(
                            it,
                            LocalizedText.key(
                                "action.moemusic.track.queued_broadcast",
                                submitter,
                                title,
                                track.artistDisplay,
                            ),
                        )
                    }
                }
            }
        }

        override fun onServerSessionCleared() = VelocityVoteManager.reset()
    }

    companion object {
        const val VERSION: String = "1.3.0"

        internal lateinit var instance: MoeMusicVelocityPlugin
            private set
    }
}
