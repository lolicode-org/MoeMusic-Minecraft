package org.lolicode.moemusic.platform.client.playback

import kotlinx.coroutines.Deferred
import net.minecraft.client.Minecraft
import net.minecraft.world.level.storage.LevelResource
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.clientcore.audio.ClientAudioFailure
import org.lolicode.moemusic.clientcore.playback.*
import org.lolicode.moemusic.core.config.ClientConfig
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.playback.LyricLine
import org.lolicode.moemusic.core.playback.ParsedLyrics
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.proto.*
import org.lolicode.moemusic.platform.client.audio.ClientAudioPlayer
import org.lolicode.moemusic.platform.client.audio.VanillaSoundBlocker
import org.lolicode.moemusic.platform.client.i18n.ClientLocalization
import org.lolicode.moemusic.platform.client.ui.ClientToastIds
import org.lolicode.moemusic.platform.client.ui.showPersistentRuntimeWarning
import org.lolicode.moemusic.platform.client.ui.showWrappedSystemToast
import org.lolicode.moemusic.platform.network.NetworkSetup
import org.lolicode.moemusic.platform.text.McText

/**
 * Minecraft-facing facade for the shared client playback runtime.
 *
 * Protocol/session logic lives in `:client-core`; this object keeps the existing platform-common
 * call surface for screens, keybinds, and packet registration while supplying Minecraft adapters.
 */
object ClientPlaybackHandler {

    @Volatile
    private var clientModVersion: String = "unknown"

    @Volatile
    private var clientProtocolVersion: Int = MoeMusicProtocol.VERSION

    private val runtime = ClientPlaybackRuntime(
        platform = MinecraftPlaybackPlatform(),
        listener = MinecraftPlaybackListener(),
    )

    @Volatile
    var guiListener: GuiListener? = null

    interface GuiListener {
        fun onSearchSourcesChanged() {}
        fun onSearchResponse(response: SearchResponse) {}
        fun onUiBootstrapResponse(response: UiBootstrapResponse) {}
        fun onTrackSubmitResponse(response: TrackSubmitResponse) {}
        fun onIdentifierSubmitResponse(response: IdentifierSubmitResponse) {}
        fun onSelectionSubmitResponse(response: SelectionSubmitResponse) {}
        fun onSelectionPageResponse(response: SelectionPageResponse) {}
        fun onQueueResponse(response: QueueResponse) {}
        fun onQueueRemoveResponse(response: QueueRemoveResponse) {}
        fun onQueueClearResponse(response: QueueClearResponse) {}
        fun onPlaybackControlResponse(response: PlaybackControlResponse) {}
        fun onContentFilterActionResponse(response: ContentFilterActionResponse) {}
        fun onLocalPlaybackBlocked(message: String) {}
        fun onLocalPlaybackRetrying(message: String) {}
        fun onLocalPlaybackRecovered(track: TrackInfo) {}
        fun onLocalPlaybackFailed(message: String) {}
        fun onInstancePlaybackStandby(message: String?) {}
        fun onPlaybackStateChanged() {}
        fun onPlaybackSnapshotApplied() {}
    }

    val serverClockOffset: Long
        get() = runtime.serverClockOffset

    val currentContext: TrackContext?
        get() = runtime.currentContext

    val lastSearchResponse: SearchResponse?
        get() = runtime.lastSearchResponse

    val cachedSearchTabState: CachedSearchTabState?
        get() = runtime.cachedSearchTabState

    val sourceCatalog: SearchSourceCatalog?
        get() = runtime.sourceCatalog

    val handshakeRequestedAtNanos: Long
        get() = runtime.handshakeRequestedAtNanos

    val serverHandshakeReceived: Boolean
        get() = runtime.serverHandshakeReceived

    val lastServerWelcomeRejection: ServerWelcomeRejection?
        get() = runtime.lastServerWelcomeRejection

    val lastQueueResponse: QueueResponse?
        get() = runtime.lastQueueResponse

    val lastUiBootstrapResponse: UiBootstrapResponse?
        get() = runtime.lastUiBootstrapResponse

    val uiCapabilitySnapshot: UiCapabilitySnapshot?
        get() = runtime.uiCapabilitySnapshot

    val lastTrackSubmitResponse: TrackSubmitResponse?
        get() = runtime.lastTrackSubmitResponse

    val lastQueueRemoveResponse: QueueRemoveResponse?
        get() = runtime.lastQueueRemoveResponse

    val lastQueueClearResponse: QueueClearResponse?
        get() = runtime.lastQueueClearResponse

    val lastPlaybackControlResponse: PlaybackControlResponse?
        get() = runtime.lastPlaybackControlResponse

    val lastContentFilterActionResponse: ContentFilterActionResponse?
        get() = runtime.lastContentFilterActionResponse

    val lastLocalPlaybackBlockedMessage: String?
        get() = runtime.lastLocalPlaybackBlockedMessage

    val lastLocalPlaybackFailureMessage: String?
        get() = runtime.lastLocalPlaybackFailureMessage

    val lastInstanceLockMessage: String?
        get() = runtime.lastInstanceLockMessage

    val currentLyrics: ParsedLyrics?
        get() = runtime.currentLyrics

    val currentSecondaryLyrics: ParsedLyrics?
        get() = runtime.currentSecondaryLyrics

    fun initializeClientMetadata(modVersion: String, protocolVersion: Int = MoeMusicProtocol.VERSION) {
        clientModVersion = modVersion.ifBlank { "unknown" }
        clientProtocolVersion = protocolVersion
    }

    fun currentServerScope(mc: Minecraft = Minecraft.getInstance()): ClientServerScope? {
        if (mc.connection == null) return null

        mc.singleplayerServer?.let { server ->
            val levelName = server.worldData.levelName.trim()
            val levelId = server.getWorldPath(LevelResource.ROOT).normalize().fileName?.toString()?.trim().orEmpty()
            val displayName = levelName.ifBlank { levelId.ifBlank { "Singleplayer" } }
            val legacyKey = if (levelName.isBlank()) "singleplayer" else "singleplayer:${levelName.lowercase()}"
            return ClientServerScope(
                key = if (levelId.isBlank()) legacyKey else "singleplayer-folder:${levelId.lowercase()}",
                displayName = displayName,
                keyAliases = setOf(legacyKey),
            )
        }

        val serverData = mc.currentServer ?: return null
        val address = serverData.ip.trim()
        if (address.isBlank()) return null

        return ClientServerScope(
            key = "server:${address.lowercase()}",
            displayName = serverData.name.takeIf { it.isNotBlank() } ?: address,
        )
    }

    fun isPlaybackEnabledForCurrentServer(mc: Minecraft = Minecraft.getInstance()): Boolean =
        ClientPlaybackAvailability.isPlaybackEnabledForServer(
            clientConfig = ModConfigManager.config.client,
            serverScope = currentServerScope(mc),
        )

    fun currentAvailabilityIssue(mc: Minecraft = Minecraft.getInstance()): AvailabilityIssue? =
        ClientPlaybackAvailability.availabilityIssue(
            hasConnection = mc.connection != null,
            serverHandshakeMissing = isServerHandshakeMissing(),
            serverHandshakeRejected = lastServerWelcomeRejection != null,
        )

    fun currentParticipationState(): UserParticipationState? =
        runtime.currentParticipationState()

    fun onConnectionJoined() = runtime.onConnectionJoined()

    fun onConnectionDisconnected() = runtime.onConnectionDisconnected()

    fun syncParticipationWithCurrentConfig() = runtime.syncParticipationWithCurrentConfig()

    fun refreshTrackNormalization() = runtime.refreshTrackNormalization()

    fun receiveFromServer(packetId: PacketId, payload: ByteArray) = runtime.receiveFromServer(packetId, payload)

    fun acceptsServerPacket(packetId: PacketId): Boolean = runtime.acceptsServerPacket(packetId)

    fun handlePlaybackSnapshotPush(msg: PlaybackSnapshotPush) = runtime.handlePlaybackSnapshotPush(msg)

    fun handleStateUpdate(msg: StateUpdate) = runtime.handleStateUpdate(msg)

    fun handleSyncResponse(resp: SyncResponse) = runtime.handleSyncResponse(resp)

    fun handleServerWelcome(msg: ServerWelcome) = runtime.handleServerWelcome(msg)

    fun handleSearchResponse(msg: SearchResponse) = runtime.handleSearchResponse(msg)

    fun handleUiBootstrapResponse(msg: UiBootstrapResponse) = runtime.handleUiBootstrapResponse(msg)

    fun handleTrackSubmitResponse(msg: TrackSubmitResponse) = runtime.handleTrackSubmitResponse(msg)

    fun handleIdentifierSubmitResponse(msg: IdentifierSubmitResponse) = runtime.handleIdentifierSubmitResponse(msg)

    fun handleSelectionSubmitResponse(msg: SelectionSubmitResponse) = runtime.handleSelectionSubmitResponse(msg)

    fun handleQueueResponse(msg: QueueResponse) = runtime.handleQueueResponse(msg)

    fun handleQueueRemoveResponse(msg: QueueRemoveResponse) = runtime.handleQueueRemoveResponse(msg)

    fun handlePlaybackControlResponse(msg: PlaybackControlResponse) = runtime.handlePlaybackControlResponse(msg)

    fun handleContentFilterActionResponse(msg: ContentFilterActionResponse) = runtime.handleContentFilterActionResponse(msg)

    fun sendSyncRequest() = runtime.sendSyncRequest()

    fun sendClientStateChange(state: org.lolicode.moemusic.core.protocol.proto.ClientStateProto) =
        runtime.sendClientStateChange(state)

    fun sendSearchRequest(query: String, sourceId: String = "", limit: Int = 20, offset: Int = 0): Long? =
        runtime.sendSearchRequest(query, sourceId, limit, offset)

    internal fun beginSearchRequest(query: String, sourceId: String = "", limit: Int = 20, offset: Int = 0): Deferred<SearchResponse>? =
        runtime.beginSearchRequest(query, sourceId, limit, offset)

    fun sendQueueRequest(limit: Int = 20, offset: Int = 0): Long? = runtime.sendQueueRequest(limit, offset)

    fun sendUiBootstrapRequest(queueLimit: Int = 20): Long? = runtime.sendUiBootstrapRequest(queueLimit)

    fun sendSelectionPageRequest(sessionId: String, offset: Int = 0, limit: Int = 20): Long? =
        runtime.sendSelectionPageRequest(sessionId, offset, limit)

    internal fun beginQueueRequest(limit: Int = 20, offset: Int = 0): Deferred<QueueResponse>? =
        runtime.beginQueueRequest(limit, offset)

    internal fun beginSelectionPageRequest(sessionId: String, offset: Int = 0, limit: Int = 20): Deferred<SelectionPageResponse>? =
        runtime.beginSelectionPageRequest(sessionId, offset, limit)

    fun sendQueueRemoveRequest(track: TrackInfo): Long? = runtime.sendQueueRemoveRequest(track)

    fun sendQueueClearRequest(scope: QueueClearScopeProto, targetUserId: String? = null): Long? =
        runtime.sendQueueClearRequest(scope, targetUserId)

    internal fun beginQueueRemoveRequest(sourceId: String, trackId: String, queueEntryId: String? = null): Deferred<QueueRemoveResponse>? =
        runtime.beginQueueRemoveRequest(sourceId, trackId, queueEntryId)

    internal fun beginQueueClearRequest(scope: QueueClearScopeProto, targetUserId: String? = null): Deferred<QueueClearResponse>? =
        runtime.beginQueueClearRequest(scope, targetUserId)

    fun sendTrackSubmit(track: TrackInfo, mode: TrackAddMode = TrackAddMode.NORMAL): Long? =
        runtime.sendTrackSubmit(track, mode)

    fun sendTrackSubmit(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Long? =
        runtime.sendTrackSubmit(entry, mode)

    internal fun beginTrackSubmitRequest(track: TrackInfo, mode: TrackAddMode = TrackAddMode.NORMAL): Deferred<TrackSubmitResponse>? =
        runtime.beginTrackSubmitRequest(track, mode)

    internal fun beginTrackSubmitRequest(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Deferred<TrackSubmitResponse>? =
        runtime.beginTrackSubmitRequest(entry, mode)

    fun sendIdentifierSubmit(identifier: String, mode: TrackAddMode): Long? =
        runtime.sendIdentifierSubmit(identifier, mode)

    internal fun beginIdentifierSubmitRequest(identifier: String, mode: TrackAddMode): Deferred<IdentifierSubmitResponse>? =
        runtime.beginIdentifierSubmitRequest(identifier, mode)

    fun sendSelectionSubmit(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Long? =
        runtime.sendSelectionSubmit(entry, mode)

    internal fun beginSelectionSubmitRequest(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Deferred<SelectionSubmitResponse>? =
        runtime.beginSelectionSubmitRequest(entry, mode)

    fun cacheSearchTabState(state: CachedSearchTabState?) = runtime.cacheSearchTabState(state)

    fun clearContext() = runtime.clearContext()

    fun sendPlaybackControl(action: PlaybackControlAction, positionMs: Long = 0L): Long? =
        runtime.sendPlaybackControl(action, positionMs)

    internal fun beginPlaybackControlRequest(action: PlaybackControlAction, positionMs: Long = 0L): Deferred<PlaybackControlResponse>? =
        runtime.beginPlaybackControlRequest(action, positionMs)

    fun sendContentFilterTrackAction(sourceId: String, trackId: String, note: String?, ban: Boolean): Long? =
        runtime.sendContentFilterTrackAction(sourceId, trackId, note, ban)

    internal fun beginContentFilterTrackActionRequest(
        sourceId: String,
        trackId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        runtime.beginContentFilterTrackActionRequest(sourceId, trackId, note, ban)

    internal fun beginContentFilterArtistActionRequest(
        sourceId: String,
        artistId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        runtime.beginContentFilterArtistActionRequest(sourceId, artistId, note, ban)

    fun currentPositionMs(ctx: TrackContext): Long = runtime.currentPositionMs(ctx)

    fun sourceDisplayName(sourceId: String?): String = runtime.sourceDisplayName(sourceId)

    fun isServerHandshakeMissing(nowNanos: Long = System.nanoTime()): Boolean =
        runtime.isServerHandshakeMissing(nowNanos)

    fun currentLyricLine(positionMs: Long): LyricLine? = runtime.currentLyricLine(positionMs)

    fun currentSecondaryLyricLine(positionMs: Long): LyricLine? = runtime.currentSecondaryLyricLine(positionMs)

    internal fun ensureDirectRequestSessionReady() = runtime.ensureDirectRequestSessionReady()

    private class MinecraftPlaybackPlatform : ClientPlaybackPlatform {
        override val name: String = "Minecraft"
        override val clientModVersion: String
            get() = ClientPlaybackHandler.clientModVersion
        override val clientProtocolVersion: Int
            get() = ClientPlaybackHandler.clientProtocolVersion
        override val audio: ClientPlaybackAudioAdapter = MinecraftAudioAdapter()

        override fun hasConnection(): Boolean =
            Minecraft.getInstance().connection != null

        override fun currentServerScope(): ClientServerScope? =
            ClientPlaybackHandler.currentServerScope(Minecraft.getInstance())

        override fun currentLocale(): String =
            ClientLocalization.selectedLanguage()

        override fun clientConfig(): ClientConfig =
            ModConfigManager.config.client

        override fun sendToServer(packetId: PacketId, payload: ByteArray) {
            NetworkSetup.channel.sendToServer(packetId, payload)
        }

        override fun executeOnClientThread(block: () -> Unit) {
            Minecraft.getInstance().execute(block)
        }

        override fun render(text: LocalizedText): String =
            ClientLocalization.render(text)

        override fun showPersistentWarning(title: LocalizedText, message: String) {
            val minecraft = Minecraft.getInstance()
            minecraft.execute {
                showPersistentRuntimeWarning(
                    minecraft,
                    ClientLocalization.component(title),
                    McText.literal(message),
                )
            }
        }

        override fun showLocalPlaybackBlocked(title: LocalizedText, message: String) {
            val minecraft = Minecraft.getInstance()
            minecraft.execute {
                showPersistentRuntimeWarning(
                    minecraft,
                    ClientLocalization.component(title),
                    McText.literal(message),
                )
            }
        }

        override fun showLocalPlaybackFailed(title: LocalizedText, message: String) {
            val minecraft = Minecraft.getInstance()
            minecraft.execute {
                showPersistentRuntimeWarning(
                    minecraft,
                    ClientLocalization.component(title),
                    McText.literal(message),
                )
            }
        }

        override fun onLocalPlaybackFailureFinal(track: TrackInfo, message: String) {
            val minecraft = Minecraft.getInstance()
            if (minecraft.singleplayerServer == null) return
            minecraft.execute {
                ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.SKIP)
            }
        }

        override fun showInstanceLockStandby(message: String) {
            if (ClientPlaybackHandler.guiListener != null) return
            val minecraft = Minecraft.getInstance()
            minecraft.execute {
                showWrappedSystemToast(
                    minecraft,
                    ClientToastIds.instanceLocked,
                    McText.translatable("screen.moemusic.instance_lock.toast.title"),
                    McText.literal(message),
                )
            }
        }

        override fun stopBlockedPlatformSoundsIfNeeded() {
            VanillaSoundBlocker.stopBlockedSoundsIfNeeded()
        }
    }

    private class MinecraftAudioAdapter : ClientPlaybackAudioAdapter {
        override fun play(playback: PlaybackResource, seekMs: Long, onError: (ClientAudioFailure) -> Unit) {
            ClientAudioPlayer.play(playback, seekMs, onError)
        }

        override fun pause() {
            ClientAudioPlayer.pause()
        }

        override fun stop() {
            ClientAudioPlayer.stop()
        }

        override fun setNormalizationGain(gain: Float) {
            ClientAudioPlayer.setNormalizationGain(gain)
        }

        override fun currentPositionMs(): Long =
            ClientAudioPlayer.currentPositionMs()

        override fun clearSavedState() {
            ClientAudioPlayer.clearSavedState()
        }
    }

    private class MinecraftPlaybackListener : ClientPlaybackRuntimeListener {
        override fun onSearchSourcesChanged(catalog: SearchSourceCatalog?) {
            guiListener?.onSearchSourcesChanged()
        }

        override fun onSearchResponse(response: SearchResponse) {
            guiListener?.onSearchResponse(response)
        }

        override fun onUiBootstrapResponse(response: UiBootstrapResponse) {
            guiListener?.onUiBootstrapResponse(response)
        }

        override fun onTrackSubmitResponse(response: TrackSubmitResponse) {
            guiListener?.onTrackSubmitResponse(response)
        }

        override fun onIdentifierSubmitResponse(response: IdentifierSubmitResponse) {
            guiListener?.onIdentifierSubmitResponse(response)
        }

        override fun onSelectionSubmitResponse(response: SelectionSubmitResponse) {
            guiListener?.onSelectionSubmitResponse(response)
        }

        override fun onSelectionPageResponse(response: SelectionPageResponse) {
            guiListener?.onSelectionPageResponse(response)
        }

        override fun onQueueResponse(response: QueueResponse) {
            guiListener?.onQueueResponse(response)
        }

        override fun onQueueRemoveResponse(response: QueueRemoveResponse) {
            guiListener?.onQueueRemoveResponse(response)
        }

        override fun onQueueClearResponse(response: QueueClearResponse) {
            guiListener?.onQueueClearResponse(response)
        }

        override fun onPlaybackControlResponse(response: PlaybackControlResponse) {
            guiListener?.onPlaybackControlResponse(response)
        }

        override fun onContentFilterActionResponse(response: ContentFilterActionResponse) {
            guiListener?.onContentFilterActionResponse(response)
        }

        override fun onLocalPlaybackBlocked(message: String) {
            guiListener?.onLocalPlaybackBlocked(message)
        }

        override fun onLocalPlaybackRetrying(message: String) {
            guiListener?.onLocalPlaybackRetrying(message)
        }

        override fun onLocalPlaybackRecovered(track: TrackInfo) {
            guiListener?.onLocalPlaybackRecovered(track)
        }

        override fun onLocalPlaybackFailed(message: String) {
            guiListener?.onLocalPlaybackFailed(message)
        }

        override fun onInstancePlaybackStandby(message: String?) {
            guiListener?.onInstancePlaybackStandby(message)
        }

        override fun onPlaybackStateChanged() {
            guiListener?.onPlaybackStateChanged()
        }

        override fun onPlaybackSnapshotApplied() {
            guiListener?.onPlaybackSnapshotApplied()
        }
    }
}
