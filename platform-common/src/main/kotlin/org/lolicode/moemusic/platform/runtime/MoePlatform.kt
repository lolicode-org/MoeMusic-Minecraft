package org.lolicode.moemusic.platform.runtime

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.IClientPlaybackService
import org.lolicode.moemusic.api.client.IClientRequestService
import org.lolicode.moemusic.api.isNullOrBlank
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.api.service.IUserActionService
import org.lolicode.moemusic.api.service.PlaybackActionOutcome
import org.lolicode.moemusic.clientcore.MoeMusicClientCoreBuildInfo
import org.lolicode.moemusic.core.MoeMusicCoreBuildInfo
import org.lolicode.moemusic.core.media.probe.MediaProbeServiceImpl
import org.lolicode.moemusic.core.permission.PermissionServiceImpl
import org.lolicode.moemusic.core.playback.ServerPlaybackController
import org.lolicode.moemusic.core.playback.TrackQueue
import org.lolicode.moemusic.core.playback.TrackSubmissionService
import org.lolicode.moemusic.core.user.UserActionServiceImpl
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import org.lolicode.moemusic.core.runtime.ServerConfigReloadReport
import org.lolicode.moemusic.core.runtime.ServerPluginServices
import org.lolicode.moemusic.core.runtime.ServerRuntimeAdapter
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.transport.NetworkChannel
import org.lolicode.moemusic.platform.MoeMusicPlatformBuildInfo
import org.lolicode.moemusic.platform.chat.LocalizedChatRenderer
import org.lolicode.moemusic.platform.command.VoteManager
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Platform composition root.
 *
 * Server-side runtime ownership lives in [ServerRuntimeCoordinator]; this object keeps the
 * existing platform entrypoints and broadcasts Minecraft chat notifications for core callbacks.
 */
object MoePlatform {

    data class RuntimeInfo(
        val loaderId: String,
        val modVersion: String,
        val coreVersion: String,
        val clientCoreVersion: String,
        val platformCommonVersion: String,
    )

    private val logger = LoggerFactory.getLogger(MoePlatform::class.java)

    @Volatile
    private var _runtimeInfo = RuntimeInfo(
        loaderId = "unknown",
        modVersion = "unknown",
        coreVersion = MoeMusicCoreBuildInfo.CORE_VERSION,
        clientCoreVersion = MoeMusicClientCoreBuildInfo.CLIENT_CORE_VERSION,
        platformCommonVersion = MoeMusicPlatformBuildInfo.PLATFORM_COMMON_VERSION,
    )

    val runtimeInfo: RuntimeInfo
        get() = _runtimeInfo

    val channel: NetworkChannel
        get() = ServerRuntimeCoordinator.channel

    val configDir: Path
        get() = ServerRuntimeCoordinator.configDir

    val queue: TrackQueue
        get() = ServerRuntimeCoordinator.queue

    val playbackController: ServerPlaybackController
        get() = ServerRuntimeCoordinator.playbackController

    val trackSubmissionService: TrackSubmissionService
        get() = ServerRuntimeCoordinator.trackSubmissionService

    val requestRateLimiter: RequestRateLimiter
        get() = ServerRuntimeCoordinator.requestRateLimiter

    val userActionService: IUserActionService
        get() = ServerRuntimeCoordinator.userActionService

    val serverRuntimeInitialized: Boolean
        get() = ServerRuntimeCoordinator.serverRuntimeInitialized

    val serverSessionActive: Boolean
        get() = ServerRuntimeCoordinator.serverSessionActive

    var clientInitialized: Boolean = false
        private set

    fun configureRuntimeInfo(loaderId: String, modVersion: String) {
        _runtimeInfo = RuntimeInfo(
            loaderId = loaderId.ifBlank { "unknown" },
            modVersion = modVersion.ifBlank { "unknown" },
            coreVersion = MoeMusicCoreBuildInfo.CORE_VERSION,
            clientCoreVersion = MoeMusicClientCoreBuildInfo.CLIENT_CORE_VERSION,
            platformCommonVersion = MoeMusicPlatformBuildInfo.PLATFORM_COMMON_VERSION,
        )
    }

    fun serverInit(channel: NetworkChannel, configDir: Path) {
        ServerRuntimeCoordinator.serverInit(
            channel = channel,
            configDir = configDir,
            adapter = object : ServerRuntimeAdapter {
                override fun onUserQueueTrackSkipped(track: TrackInfo, reason: LocalizedText?) {
                    broadcastSkippedUserQueueTrack(track, reason)
                }

                override fun onTrackSubmitted(track: TrackInfo, result: TrackAddResult) {
                    broadcastSubmittedTrack(track, result)
                }

                override fun onServerSessionCleared() {
                    VoteManager.reset()
                }
            },
            pluginServicesFactory = { playbackController, trackSubmissionService, requestRateLimiter ->
                val permissionService = PermissionServiceImpl()
                ServerPluginServices(
                    permissionService = permissionService,
                    userActionService = UserActionServiceImpl(
                        permissionService = permissionService,
                        requestRateLimiter = requestRateLimiter,
                        searchService = PluginManager.searchService,
                        identifierResolutionService = PluginManager.identifierResolutionService,
                        trackSubmissionService = trackSubmissionService,
                        playbackController = playbackController,
                        voteToSkipHandler = { userId ->
                            val result = VoteManager.requestVote(userId)
                            PlaybackActionOutcome(success = result.success, failure = result.failure)
                        },
                    ),
                    mediaProbeService = MediaProbeServiceImpl(),
                )
            },
        )
        logger.info("Platform server session initialized.")
    }

    fun clientInit(
        clientPlaybackService: IClientPlaybackService,
        clientRequestService: IClientRequestService,
    ) {
        PluginManager.activateClientRuntime(clientPlaybackService, clientRequestService)
        PluginManager.dispatchClientRuntimeLoad()
        clientInitialized = true
        logger.info("MoePlatform client initialized.")
    }

    fun clientInitIfNeeded(
        clientPlaybackService: IClientPlaybackService,
        clientRequestService: IClientRequestService,
    ) {
        if (!clientInitialized) clientInit(clientPlaybackService, clientRequestService)
    }

    fun serverShutdown(finalRuntime: Boolean) {
        ServerRuntimeCoordinator.serverShutdown(finalRuntime)
    }

    fun applyReloadableServerConfig(): List<String> =
        ServerRuntimeCoordinator.applyReloadableServerConfig()

    fun reloadServerConfigFromDisk(): ServerConfigReloadReport =
        ServerRuntimeCoordinator.reloadServerConfigFromDisk()

    fun refreshAutoplayRuntime() {
        ServerRuntimeCoordinator.refreshAutoplayRuntime()
    }

    fun onNativeAudienceAvailable() {
        ServerRuntimeCoordinator.ensureNativeAudienceLease()
    }

    fun onNativeAudienceUnavailable() {
        ServerRuntimeCoordinator.releaseNativeAudienceLeaseIfHeld()
    }

    fun clientShutdown() {
        PluginManager.dispatchClientRuntimeUnload()
        clientInitialized = false
        if (serverRuntimeInitialized) {
            serverShutdown(finalRuntime = true)
        } else {
            PluginManager.reset()
        }
        logger.info("MoePlatform client shutdown complete.")
    }

    private fun broadcastSkippedUserQueueTrack(track: TrackInfo, reason: LocalizedText?) {
        val title = displayTrackTitle(track)
        MinecraftUserRegistry.allActive().forEach { player ->
            val message = if (reason.isNullOrBlank()) {
                LocalizedText.key("action.moemusic.playback.queue_track_skipped", title)
            } else {
                LocalizedText.key("action.moemusic.playback.queue_track_skipped_reason", title, reason)
            }
            player.entity().sendSystemMessage(
                LocalizedChatRenderer.prefixed(player.locale, message, LocalizedChatRenderer.Tone.FAILURE)
            )
        }
    }

    private fun broadcastSubmittedTrack(track: TrackInfo, result: TrackAddResult) {
        val title = displayTrackTitle(track)
        val submitter = track.submittedByUserName?.takeIf { it.isNotBlank() } ?: "Server"
        MinecraftUserRegistry.allActive().forEach { player ->
            val message = LocalizedText.key("action.moemusic.track.queued_broadcast", submitter, title, track.artistDisplay)
            player.entity().sendSystemMessage(
                LocalizedChatRenderer.prefixed(player.locale, message, LocalizedChatRenderer.Tone.SUCCESS)
            )
        }
    }

    private fun displayTrackTitle(track: TrackInfo): String =
        track.title.ifBlank { track.id.ifBlank { "queued track" } }
}
