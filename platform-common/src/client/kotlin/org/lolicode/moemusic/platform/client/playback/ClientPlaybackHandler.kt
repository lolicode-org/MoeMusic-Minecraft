package org.lolicode.moemusic.platform.client.playback

import org.lolicode.moemusic.platform.text.McText

import io.netty.buffer.Unpooled
import kotlinx.coroutines.*
import lol.bai.badpackets.api.PacketSender
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.ClientRequestException
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.event.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.clientcore.media.ClientMediaFirewall
import org.lolicode.moemusic.clientcore.playback.*
import org.lolicode.moemusic.clientcore.playback.SearchSourceInfo
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.*
import org.lolicode.moemusic.core.playback.*
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult
import org.lolicode.moemusic.platform.client.audio.ClientAudioPlayer
import org.lolicode.moemusic.platform.client.audio.VanillaSoundBlocker
import org.lolicode.moemusic.platform.client.i18n.ClientLocalization
import org.lolicode.moemusic.platform.client.network.ClientNetworkSetup
import org.lolicode.moemusic.platform.client.ui.ClientToastIds
import org.lolicode.moemusic.platform.client.ui.NowPlayingHud
import org.lolicode.moemusic.platform.client.ui.showPersistentRuntimeWarning
import org.lolicode.moemusic.platform.client.ui.showWrappedSystemToast
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Client-side packet handler for all music playback control packets.
 *
 * Receives [PlayTrack], [PlaybackSnapshotUpdate], [StateUpdate], and [SyncResponse] from the server,
 * then drives [ClientAudioPlayer].
 *
 * Maintains [serverClockOffset] (nanoseconds to add to [System.nanoTime] to estimate the
 * server's monotonic clock), updated each time a [SyncResponse] arrives.
 *
 * Singleton — accessed from [ClientNetworkSetup] and [NowPlayingHud].
 */
object ClientPlaybackHandler {

    private data class DesiredParticipation(
        val state: ClientStateProto,
        val waitForLock: Boolean,
    )

    private class PendingRequestRegistry<T> {
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<T>>()

        fun register(requestId: Long): CompletableDeferred<T> =
            CompletableDeferred<T>().also { deferred ->
                pending[requestId] = deferred
                deferred.invokeOnCompletion {
                    pending.remove(requestId, deferred)
                }
            }

        fun complete(requestId: Long, response: T) {
            pending.remove(requestId)?.complete(response)
        }

        fun failAll(cause: Throwable) {
            val entries = pending.values.toList()
            pending.clear()
            entries.forEach { it.completeExceptionally(cause) }
        }
    }

    private val logger = LoggerFactory.getLogger(ClientPlaybackHandler::class.java)
    private val standbyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val timeSyncHandler = TimeSyncHandler()
    private val requestIdCounter = AtomicLong(1L)
    private val pendingSearchResponses = PendingRequestRegistry<SearchResponse>()
    private val pendingQueueResponses = PendingRequestRegistry<QueueResponse>()
    private val pendingTrackSubmitResponses = PendingRequestRegistry<TrackSubmitResponse>()
    private val pendingIdentifierSubmitResponses = PendingRequestRegistry<IdentifierSubmitResponse>()
    private val pendingSelectionSubmitResponses = PendingRequestRegistry<SelectionSubmitResponse>()
    private val pendingQueueRemoveResponses = PendingRequestRegistry<QueueRemoveResponse>()
    private val pendingPlaybackControlResponses = PendingRequestRegistry<PlaybackControlResponse>()
    private val pendingContentFilterActionResponses = PendingRequestRegistry<ContentFilterActionResponse>()
    private const val HANDSHAKE_GRACE_NANOS = 3_000_000_000L
    private const val STANDBY_LOCK_POLL_INTERVAL_MS = 3_000L

    @Volatile
    private var clientModVersion: String = "unknown"

    @Volatile
    private var clientProtocolVersion: Int = MoeMusicProtocol.VERSION

    @Volatile
    private var participationRequested: Boolean = false

    /** True while this client is actively registered for playback broadcasts in MinecraftUserRegistry. */
    @Volatile
    private var playbackRegistrationActive: Boolean = false

    /** True while local standby is caused by the instance lock being unavailable. */
    @Volatile
    private var standbyWaitingForLock: Boolean = false

    @Volatile
    private var instanceLockWaitNotified: Boolean = false

    @Volatile
    private var latestSearchResponseRequestId: Long = 0L

    @Volatile
    private var latestQueueResponseRequestId: Long = 0L

    @Volatile
    private var latestTrackSubmitResponseRequestId: Long = 0L

    @Volatile
    private var latestQueueRemoveResponseRequestId: Long = 0L

    @Volatile
    private var latestPlaybackControlResponseRequestId: Long = 0L

    @Volatile
    private var latestContentFilterActionResponseRequestId: Long = 0L

    private var standbyPollJob: Job? = null

    /**
     * Nanosecond offset: `System.nanoTime() + serverClockOffset ≈ server's System.nanoTime()`.
     * Computed from the most recent [SyncResponse].
     */
    @Volatile
    var serverClockOffset: Long = 0L
        private set

    @Volatile
    private var timeSyncEstablished: Boolean = false

    /** Current client-side view of the playback state. Updated by incoming packets. */
    @Volatile
    var currentContext: TrackContext? = null
        private set

    /** The most recent [SearchResponse] received from the server. Null until a search is performed. */
    @Volatile
    var lastSearchResponse: SearchResponse? = null
        private set

    /** Aggregated search state used by the GUI when it restores a previous search session. */
    @Volatile
    var cachedSearchState: CachedSearchState? = null
        private set

    /** Server-provided search source metadata from the join handshake. */
    @Volatile
    var sourceCatalog: SearchSourceCatalog? = null
        private set

    @Volatile
    var handshakeRequestedAtNanos: Long = 0L
        private set

    @Volatile
    var serverHandshakeReceived: Boolean = false
        private set

    @Volatile
    private var serverSessionAccepted: Boolean = false

    @Volatile
    var lastServerWelcomeRejection: ServerWelcomeRejection? = null
        private set

    /** The most recent [QueueResponse] received from the server. Null until a queue request is made. */
    @Volatile
    var lastQueueResponse: QueueResponse? = null
        private set

    @Volatile
    var lastTrackSubmitResponse: TrackSubmitResponse? = null
        private set

    @Volatile
    var lastQueueRemoveResponse: QueueRemoveResponse? = null
        private set

    @Volatile
    var lastPlaybackControlResponse: PlaybackControlResponse? = null
        private set

    @Volatile
    var lastContentFilterActionResponse: ContentFilterActionResponse? = null
        private set

    @Volatile
    var lastLocalPlaybackBlockedMessage: String? = null
        private set

    @Volatile
    var lastInstanceLockMessage: String? = null
        private set

    @Volatile
    var currentLyrics: ParsedLyrics? = null
        private set

    @Volatile
    var currentSecondaryLyrics: ParsedLyrics? = null
        private set

    /**
     * Optional listener for GUI updates. Set by the music player screen to receive
     * notifications when packets arrive, so it can refresh its display.
     */
    @Volatile
    var guiListener: GuiListener? = null

    /** Callback interface for GUI screens to receive update notifications. */
    interface GuiListener {
        fun onSearchSourcesChanged() {}
        fun onSearchResponse(response: SearchResponse) {}
        fun onTrackSubmitResponse(response: TrackSubmitResponse) {}
        fun onIdentifierSubmitResponse(response: IdentifierSubmitResponse) {}
        fun onSelectionSubmitResponse(response: SelectionSubmitResponse) {}
        fun onQueueResponse(response: QueueResponse) {}
        fun onQueueRemoveResponse(response: QueueRemoveResponse) {}
        fun onPlaybackControlResponse(response: PlaybackControlResponse) {}
        fun onContentFilterActionResponse(response: ContentFilterActionResponse) {}
        fun onLocalPlaybackBlocked(message: String) {}
        fun onInstancePlaybackStandby(message: String?) {}
        fun onPlaybackStateChanged() {}
    }

    fun initializeClientMetadata(modVersion: String, protocolVersion: Int = MoeMusicProtocol.VERSION) {
        clientModVersion = modVersion.ifBlank { "unknown" }
        clientProtocolVersion = protocolVersion
    }

    fun currentServerScope(mc: Minecraft = Minecraft.getInstance()): ClientServerScope? {
        if (mc.connection == null) return null

        mc.singleplayerServer?.let { server ->
            val levelName = server.worldData.levelName.trim()
            return ClientServerScope(
                key = if (levelName.isBlank()) "singleplayer" else "singleplayer:${levelName.lowercase()}",
                displayName = levelName.ifBlank { "Singleplayer" },
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

    fun isPlaybackEnabledForCurrentServer(mc: Minecraft = Minecraft.getInstance()): Boolean {
        return ClientPlaybackAvailability.isPlaybackEnabledForServer(
            clientConfig = ModConfigManager.config.client,
            serverScope = currentServerScope(mc),
        )
    }

    fun currentAvailabilityIssue(mc: Minecraft = Minecraft.getInstance()): AvailabilityIssue? {
        return ClientPlaybackAvailability.availabilityIssue(
            hasConnection = mc.connection != null,
            serverHandshakeMissing = isServerHandshakeMissing(),
            serverHandshakeRejected = lastServerWelcomeRejection != null,
        )
    }

    fun currentParticipationState(): UserParticipationState? = when {
        !participationRequested -> null
        playbackRegistrationActive -> UserParticipationState.ACTIVE
        else -> UserParticipationState.STANDBY
    }

    fun onConnectionJoined() {
        startSession()
        CoreEvents.bus.fire(OnClientConnected)
    }

    fun onConnectionDisconnected() {
        CoreEvents.bus.fire(OnClientDisconnected)
        stopStandbyPolling()
        ClientNetworkSetup.stopSyncLoop()
        InstancePlaybackLock.release()
        ClientAudioPlayer.stop()
        ClientAudioPlayer.clearSavedState()
        clearContext()
    }

    fun syncParticipationWithCurrentConfig() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.connection == null) return
        if (!participationRequested) {
            startSession(minecraft)
            return
        }

        val desired = desiredParticipation(minecraft)
        if (desired.state == ClientStateProto.CLIENT_STATE_ACTIVE &&
            playbackRegistrationActive &&
            isGlobalInstancePlaybackLockEnabled()
        ) {
            currentContext?.let { ctx ->
                if (ctx.state != PlaybackState.Stopped && !ensurePlaybackLock()) return
            }
        }

        applyDesiredParticipation(desired)
    }

    // -------------------------------------------------------------------------
    // Packet handlers
    // -------------------------------------------------------------------------

    /**
     * Handle a [PlayTrack] packet: apply the server's anchored playback snapshot.
     */
    fun handlePlayTrack(msg: PlayTrack) {
        applyPlaybackSnapshot(msg.snapshot ?: return, fromSyncState = false)
    }

    /**
     * Handle a full snapshot update for a newly active client.
     */
    fun handlePlaybackSnapshotUpdate(msg: PlaybackSnapshotUpdate) {
        if (!participationRequested || !serverSessionAccepted) return
        msg.time_sync?.let(::applyTimeSync)
        if (!isPlaybackEnabledForCurrentServer()) {
            enterStandbyParticipation(waitForLock = false)
            return
        }
        playbackRegistrationActive = true
        ClientNetworkSetup.startSyncLoop()
        val snapshot = msg.snapshot
        if (snapshot == null) {
            releasePlaybackLock(clearMessage = true)
            stopActivePlayback(fireEvent = true)
            return
        }
        applyPlaybackSnapshot(snapshot, fromSyncState = true)
    }

    /**
     * Handle a [StateUpdate] packet.
     *
     * - **PAUSED**: pause [ClientAudioPlayer]; update context.
     * - **PLAYING** (resume/seek): recompute seek and restart audio.
     * - **STOPPED**: stop audio and clear context.
     */
    fun handleStateUpdate(msg: StateUpdate) {
        if (!canHandlePlaybackPackets()) return
        val ctx = currentContext ?: return

        when (msg.state) {
            PlaybackStateProto.PAUSED -> {
                val positionMs = normalizeClientPosition(msg.position_ms, ctx.track.durationMs)
                logger.debug("StateUpdate: PAUSED posMs={}", positionMs)
                ClientAudioPlayer.pause()
                currentContext = ctx.copy(state = PlaybackState.Paused(positionMs))
                CoreEvents.bus.fire(
                    OnClientPlaybackPaused(
                        track = ctx.track,
                        positionMs = positionMs,
                    )
                )
            }
            PlaybackStateProto.PLAYING -> {
                if (!ensurePlaybackLock()) return
                val serverNow = currentServerMonotonicNow()
                val wasPaused = ctx.state is PlaybackState.Paused
                val playback = msg.playback?.toApi() ?: ctx.playback
                val seekMs = anchoredPlaybackPositionMs(
                    positionMs = msg.position_ms,
                    anchorServerMonotonic = msg.position_anchor_server_monotonic,
                    durationMs = ctx.track.durationMs,
                )
                logger.debug("StateUpdate: PLAYING seekMs={}", seekMs)
                ClientAudioPlayer.play(playback, seekMs)
                VanillaSoundBlocker.stopBlockedSoundsIfNeeded()
                val newStart = if (msg.position_anchor_server_monotonic != 0L) {
                    msg.position_anchor_server_monotonic - msg.position_ms.coerceAtLeast(0L) * 1_000_000L
                } else {
                    ctx.serverStartMonotonic
                }
                currentContext = ctx.copy(
                    playback = playback,
                    state = PlaybackState.Playing(seekMs),
                    serverStartMonotonic = newStart,
                    serverResumeMonotonic = serverNow,
                )
                if (wasPaused) {
                    CoreEvents.bus.fire(
                        OnClientPlaybackResumed(
                            track = ctx.track,
                            positionMs = seekMs,
                        )
                    )
                } else {
                    CoreEvents.bus.fire(
                        OnClientPlaybackSeeked(
                            track = ctx.track,
                            positionMs = seekMs,
                        )
                    )
                }
            }
            PlaybackStateProto.STOPPED -> {
                logger.debug("StateUpdate: STOPPED")
                releasePlaybackLock(clearMessage = true)
                stopActivePlayback(fireEvent = true)
            }
        }
        guiListener?.onPlaybackStateChanged()
    }

    /**
     * Handle a [SyncResponse] packet. Updates [serverClockOffset].
     * Called immediately when the response arrives to minimise timing error.
     */
    fun handleSyncResponse(resp: SyncResponse) {
        if (!canHandlePlaybackPackets()) return
        applyTimeSync(resp)
        logger.debug("Clock offset updated: {} ns", serverClockOffset)
    }

    fun handleServerWelcome(msg: ServerWelcome) {
        if (!participationRequested) return
        msg.initial_time_sync?.let(::applyTimeSync)
        serverHandshakeReceived = true
        serverSessionAccepted = msg.accepted
        if (!msg.accepted) {
            val rejection = serverWelcomeRejection(msg)
            lastServerWelcomeRejection = rejection
            val failure = renderServerWelcomeRejection(rejection)
            logger.warn(
                "ServerWelcome rejected: reason={} clientProtocol={} serverProtocol={} detail='{}'",
                rejection.reason,
                rejection.clientProtocolVersion,
                rejection.serverProtocolVersion,
                rejection.detail.orEmpty(),
            )
            sourceCatalog = null
            playbackRegistrationActive = false
            ClientNetworkSetup.stopSyncLoop()
            releasePlaybackLock(clearMessage = true)
            stopActivePlayback()
            failPendingRequests(ClientRequestException(failure))
            val minecraft = Minecraft.getInstance()
            minecraft.execute {
                showPersistentRuntimeWarning(
                    minecraft,
                    McText.translatable("screen.moemusic.unavailable.rejected.title"),
                    McText.literal(failure),
                )
            }
            guiListener?.onSearchSourcesChanged()
            return
        }
        lastServerWelcomeRejection = null

        sourceCatalog = SearchSourceCatalog(
            sources = msg.sources.map { info ->
                SearchSourceInfo(
                    id = info.id,
                    displayName = info.display_name.ifBlank { info.id },
                    searchable = info.searchable,
                )
            },
            defaultSourceId = msg.default_source_id,
        )
        playbackRegistrationActive = msg.accepted_state == ClientStateProto.CLIENT_STATE_ACTIVE
        logger.debug(
            "ServerWelcome: {} sources default='{}' state={} initialPlayback={}",
            sourceCatalog?.sources?.size ?: 0,
            sourceCatalog?.defaultSourceId.orEmpty(),
            msg.accepted_state,
            msg.initial_playback != null,
        )
        if (playbackRegistrationActive) {
            if (isPlaybackEnabledForCurrentServer()) {
                ClientNetworkSetup.startSyncLoop()
                val snapshot = msg.initial_playback
                if (snapshot != null) {
                    applyPlaybackSnapshot(snapshot, fromSyncState = true)
                } else {
                    releasePlaybackLock(clearMessage = true)
                    stopActivePlayback()
                }
            } else {
                enterStandbyParticipation(waitForLock = false)
            }
        } else {
            ClientNetworkSetup.stopSyncLoop()
        }
        if (standbyWaitingForLock) {
            updateInstanceLockStandby(notifyUser = true)
            startStandbyPolling()
        }
        guiListener?.onSearchSourcesChanged()
    }

    /**
     * Handle a [SearchResponse] packet (response to a client-submitted [SearchRequest]).
     *
     * The results are stored for display in the search GUI.
     * Notifies any registered [guiListener].
     */
    fun handleSearchResponse(msg: SearchResponse) {
        if (!canHandleDirectResponses()) return
        logger.debug(
            "SearchResponse: query='{}' offset={} results={} total={} hasMore={} failure='{}'",
            msg.query, msg.offset, msg.entries.size, msg.total, msg.has_more, msg.failure,
        )
        pendingSearchResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestSearchResponseRequestId) {
            latestSearchResponseRequestId = msg.request_id
            lastSearchResponse = msg
            if (msg.offset == 0 || cachedSearchState?.query != msg.query || cachedSearchState?.sourceId != msg.source_id) {
                cachedSearchState = CachedSearchState(
                    query = msg.query,
                    sourceId = msg.source_id,
                    entries = msg.entries.map { it.toApi() },
                    total = msg.total,
                    hasMore = msg.has_more,
                    failure = msg.failure.ifEmpty { null },
                )
            }
        }
        guiListener?.onSearchResponse(msg)
    }

    /**
     * Handle a [TrackSubmitResponse] packet.
     */
    fun handleTrackSubmitResponse(msg: TrackSubmitResponse) {
        if (!canHandleDirectResponses()) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("TrackSubmitResponse failure: {}", msg.failure)
        }
        pendingTrackSubmitResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestTrackSubmitResponseRequestId) {
            latestTrackSubmitResponseRequestId = msg.request_id
            lastTrackSubmitResponse = msg
        }
        guiListener?.onTrackSubmitResponse(msg)
    }

    fun handleIdentifierSubmitResponse(msg: IdentifierSubmitResponse) {
        if (!canHandleDirectResponses()) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("IdentifierSubmitResponse failure: {}", msg.failure)
        }
        pendingIdentifierSubmitResponses.complete(msg.request_id, msg)
        guiListener?.onIdentifierSubmitResponse(msg)
    }

    fun handleSelectionSubmitResponse(msg: SelectionSubmitResponse) {
        if (!canHandleDirectResponses()) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("SelectionSubmitResponse failure: {}", msg.failure)
        }
        pendingSelectionSubmitResponses.complete(msg.request_id, msg)
        guiListener?.onSelectionSubmitResponse(msg)
    }

    /**
     * Handle a [QueueResponse] packet (response to a client-submitted [QueueRequest]).
     */
    fun handleQueueResponse(msg: QueueResponse) {
        if (!canHandleDirectResponses()) return
        logger.debug("QueueResponse: {} tracks failure='{}'", msg.tracks.size, msg.failure)
        pendingQueueResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestQueueResponseRequestId) {
            latestQueueResponseRequestId = msg.request_id
            lastQueueResponse = msg
        }
        guiListener?.onQueueResponse(msg)
    }

    /**
     * Handle a [QueueRemoveResponse] packet.
     */
    fun handleQueueRemoveResponse(msg: QueueRemoveResponse) {
        if (!canHandleDirectResponses()) return
        logger.debug("QueueRemoveResponse: failure='{}'", msg.failure)
        pendingQueueRemoveResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestQueueRemoveResponseRequestId) {
            latestQueueRemoveResponseRequestId = msg.request_id
            lastQueueRemoveResponse = msg
        }
        guiListener?.onQueueRemoveResponse(msg)
    }

    /**
     * Handle a [PlaybackControlResponse] packet.
     */
    fun handlePlaybackControlResponse(msg: PlaybackControlResponse) {
        if (!canHandleDirectResponses()) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("PlaybackControlResponse failure: {}", msg.failure)
        }
        pendingPlaybackControlResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestPlaybackControlResponseRequestId) {
            latestPlaybackControlResponseRequestId = msg.request_id
            lastPlaybackControlResponse = msg
        }
        guiListener?.onPlaybackControlResponse(msg)
    }

    fun handleContentFilterActionResponse(msg: ContentFilterActionResponse) {
        if (!canHandleDirectResponses()) return
        if (msg.failure.isNotEmpty()) {
            logger.debug("ContentFilterActionResponse failure: {}", msg.failure)
        }
        pendingContentFilterActionResponses.complete(msg.request_id, msg)
        if (msg.request_id == 0L || msg.request_id >= latestContentFilterActionResponseRequestId) {
            latestContentFilterActionResponseRequestId = msg.request_id
            lastContentFilterActionResponse = msg
        }
        guiListener?.onContentFilterActionResponse(msg)
    }

    // -------------------------------------------------------------------------
    // C→S senders (used by the GUI and sync loop)
    // -------------------------------------------------------------------------

    /**
     * Send a [SyncRequest] to the server using bad packets directly (no NetworkChannel needed).
     * Safe to call from any thread.
     */
    fun sendSyncRequest() {
        if (!canHandlePlaybackPackets()) return
        val req = SyncRequest(client_send_monotonic = System.nanoTime())
        val id = ResourceLocation(PacketIds.SYNC_REQUEST.namespace, PacketIds.SYNC_REQUEST.path)
        PacketSender.c2s().send(id, FriendlyByteBuf(Unpooled.wrappedBuffer(req.encode())))
    }

    /** Send the initial client hello for this connection. */
    fun sendHandshake(locale: String, modVersion: String, protocolVersion: Int, initialState: ClientStateProto) {
        participationRequested = true
        playbackRegistrationActive = false
        val now = System.nanoTime()
        handshakeRequestedAtNanos = now
        serverHandshakeReceived = false
        serverSessionAccepted = false
        lastServerWelcomeRejection = null
        sourceCatalog = null
        val msg = ClientHandshake(
            locale = locale,
            mod_version = modVersion,
            protocol_version = protocolVersion,
            initial_state = initialState,
            client_send_monotonic = now,
        )
        val id = ResourceLocation(PacketIds.CLIENT_HANDSHAKE.namespace, PacketIds.CLIENT_HANDSHAKE.path)
        PacketSender.c2s().send(id, FriendlyByteBuf(Unpooled.wrappedBuffer(msg.encode())))
        logger.debug(
            "CLIENT_HANDSHAKE sent (locale={}, state={}, mod={}, protocol={})",
            locale,
            initialState,
            modVersion,
            protocolVersion,
        )
    }

    fun sendClientStateChange(state: ClientStateProto) {
        if (!participationRequested || !serverSessionAccepted) return
        if (state == ClientStateProto.CLIENT_STATE_STANDBY) {
            playbackRegistrationActive = false
            ClientNetworkSetup.stopSyncLoop()
        }
        send(
            PacketIds.CLIENT_STATE_CHANGE,
            ClientStateChange(
                state = state,
                client_send_monotonic = System.nanoTime(),
            ).encode(),
        )
    }

    /** Send a [SearchRequest] to the server. Used by the search GUI. */
    fun sendSearchRequest(query: String, sourceId: String = "", limit: Int = 20, offset: Int = 0): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val msg = SearchRequest(query = query, source_id = sourceId, limit = limit, offset = offset, request_id = requestId)
        send(PacketIds.SEARCH_REQUEST, msg.encode())
        return requestId
    }

    internal fun beginSearchRequest(query: String, sourceId: String = "", limit: Int = 20, offset: Int = 0): Deferred<SearchResponse>? =
        beginCorrelatedRequest(pendingSearchResponses, PacketIds.SEARCH_REQUEST) { requestId ->
            SearchRequest(query = query, source_id = sourceId, limit = limit, offset = offset, request_id = requestId).encode()
        }

    /** Send a [QueueRequest] to fetch the current user queue. */
    fun sendQueueRequest(): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        send(PacketIds.QUEUE_REQUEST, QueueRequest(request_id = requestId).encode())
        return requestId
    }

    internal fun beginQueueRequest(): Deferred<QueueResponse>? =
        beginCorrelatedRequest(pendingQueueResponses, PacketIds.QUEUE_REQUEST) { requestId ->
            QueueRequest(request_id = requestId).encode()
        }

    /** Send a [QueueRemoveRequest] to remove a queued track by its stable source/track identity. */
    fun sendQueueRemoveRequest(track: TrackInfo): Long? {
        val sourceId = track.sourceId ?: return null
        if (sourceId.isBlank() || track.id.isBlank()) return null
        val requestId = nextCorrelatedRequestId() ?: return null
        val msg = QueueRemoveRequest(source_id = sourceId, track_id = track.id, request_id = requestId)
        send(PacketIds.QUEUE_REMOVE_REQUEST, msg.encode())
        return requestId
    }

    internal fun beginQueueRemoveRequest(sourceId: String, trackId: String): Deferred<QueueRemoveResponse>? {
        if (sourceId.isBlank() || trackId.isBlank()) return null
        return beginCorrelatedRequest(pendingQueueRemoveResponses, PacketIds.QUEUE_REMOVE_REQUEST) { requestId ->
            QueueRemoveRequest(source_id = sourceId, track_id = trackId, request_id = requestId).encode()
        }
    }

    /** Send a [TrackSubmitRequest] to enqueue a track on the server. */
    fun sendTrackSubmit(track: TrackInfo, mode: TrackAddMode = TrackAddMode.NORMAL): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val msg = TrackSubmitRequest(
            source_id = track.sourceId.orEmpty(),
            track_id = track.id,
            mode = mode.toProto(),
            request_id = requestId,
        )
        send(PacketIds.TRACK_SUBMIT, msg.encode())
        return requestId
    }

    /** Send a [TrackSubmitRequest] for a direct-track [SelectionEntry]. */
    fun sendTrackSubmit(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Long? =
        entry.toDirectTrackSubmitTrack()?.let { track -> sendTrackSubmit(track, mode) }

    internal fun beginTrackSubmitRequest(track: TrackInfo, mode: TrackAddMode = TrackAddMode.NORMAL): Deferred<TrackSubmitResponse>? =
        beginCorrelatedRequest(pendingTrackSubmitResponses, PacketIds.TRACK_SUBMIT) { requestId ->
            TrackSubmitRequest(
                source_id = track.sourceId.orEmpty(),
                track_id = track.id,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    internal fun beginTrackSubmitRequest(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Deferred<TrackSubmitResponse>? =
        entry.toDirectTrackSubmitTrack()?.let { track -> beginTrackSubmitRequest(track, mode) }

    fun sendIdentifierSubmit(identifier: String, mode: TrackAddMode): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val msg = IdentifierSubmitRequest(
            identifier = identifier,
            mode = mode.toProto(),
            request_id = requestId,
        )
        send(PacketIds.IDENTIFIER_SUBMIT, msg.encode())
        return requestId
    }

    internal fun beginIdentifierSubmitRequest(identifier: String, mode: TrackAddMode): Deferred<IdentifierSubmitResponse>? =
        beginCorrelatedRequest(pendingIdentifierSubmitResponses, PacketIds.IDENTIFIER_SUBMIT) { requestId ->
            IdentifierSubmitRequest(
                identifier = identifier,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    fun sendSelectionSubmit(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val msg = SelectionSubmitRequest(
            source_id = entry.sourceId.orEmpty(),
            selection_id = entry.selectionId,
            mode = mode.toProto(),
            request_id = requestId,
        )
        send(PacketIds.SELECTION_SUBMIT, msg.encode())
        return requestId
    }

    internal fun beginSelectionSubmitRequest(entry: SelectionEntry, mode: TrackAddMode = TrackAddMode.NORMAL): Deferred<SelectionSubmitResponse>? =
        beginCorrelatedRequest(pendingSelectionSubmitResponses, PacketIds.SELECTION_SUBMIT) { requestId ->
            SelectionSubmitRequest(
                source_id = entry.sourceId.orEmpty(),
                selection_id = entry.selectionId,
                mode = mode.toProto(),
                request_id = requestId,
            ).encode()
        }

    fun cacheSearchState(state: CachedSearchState?) {
        cachedSearchState = state
    }

    private fun failPendingRequests(cause: Throwable) {
        pendingSearchResponses.failAll(cause)
        pendingQueueResponses.failAll(cause)
        pendingTrackSubmitResponses.failAll(cause)
        pendingIdentifierSubmitResponses.failAll(cause)
        pendingSelectionSubmitResponses.failAll(cause)
        pendingQueueRemoveResponses.failAll(cause)
        pendingPlaybackControlResponses.failAll(cause)
        pendingContentFilterActionResponses.failAll(cause)
    }

    private fun SelectionEntry.toDirectTrackSubmitTrack(): TrackInfo? {
        val trackId = directTrackId?.takeIf(String::isNotBlank) ?: return null
        return TrackInfo(
            id = trackId,
            title = title,
            artists = artists,
            durationMs = durationMs,
            sourceId = sourceId,
            album = album,
            unavailableReason = unavailableReason,
        )
    }

    /** Send a [PlaybackControlRequest] for a pause/resume/skip/stop/seek action. */
    fun sendPlaybackControl(action: PlaybackControlAction, positionMs: Long = 0L): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val msg = PlaybackControlRequest(action = action, position_ms = positionMs, request_id = requestId)
        send(PacketIds.PLAYBACK_CONTROL_REQUEST, msg.encode())
        return requestId
    }

    internal fun beginPlaybackControlRequest(action: PlaybackControlAction, positionMs: Long = 0L): Deferred<PlaybackControlResponse>? =
        beginCorrelatedRequest(pendingPlaybackControlResponses, PacketIds.PLAYBACK_CONTROL_REQUEST) { requestId ->
            PlaybackControlRequest(action = action, position_ms = positionMs, request_id = requestId).encode()
        }

    fun sendContentFilterTrackAction(sourceId: String, trackId: String, note: String?, ban: Boolean): Long? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val msg = ContentFilterActionRequest(
            action = if (ban) ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN else ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN,
            target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK,
            source_id = sourceId,
            value_id = trackId,
            note = note.orEmpty(),
            request_id = requestId,
        )
        send(PacketIds.CONTENT_FILTER_ACTION_REQUEST, msg.encode())
        return requestId
    }

    internal fun beginContentFilterTrackActionRequest(
        sourceId: String,
        trackId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        beginCorrelatedRequest(pendingContentFilterActionResponses, PacketIds.CONTENT_FILTER_ACTION_REQUEST) { requestId ->
            ContentFilterActionRequest(
                action = if (ban) ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN else ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN,
                target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK,
                source_id = sourceId,
                value_id = trackId,
                note = note.orEmpty(),
                request_id = requestId,
            ).encode()
        }

    internal fun beginContentFilterArtistActionRequest(
        sourceId: String,
        artistId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        beginCorrelatedRequest(pendingContentFilterActionResponses, PacketIds.CONTENT_FILTER_ACTION_REQUEST) { requestId ->
            ContentFilterActionRequest(
                action = if (ban) ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN else ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN,
                target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_ARTIST,
                source_id = sourceId,
                value_id = artistId,
                note = note.orEmpty(),
                request_id = requestId,
            ).encode()
        }

    /** Clear connection-scoped local state on disconnect so stale packets do not restart playback. */
    fun clearContext() {
        val disconnect = ClientRequestException("Disconnected from MoeMusic session.")
        failPendingRequests(disconnect)
        participationRequested = false
        playbackRegistrationActive = false
        standbyWaitingForLock = false
        instanceLockWaitNotified = false
        latestSearchResponseRequestId = 0L
        latestQueueResponseRequestId = 0L
        latestTrackSubmitResponseRequestId = 0L
        latestQueueRemoveResponseRequestId = 0L
        latestPlaybackControlResponseRequestId = 0L
        latestContentFilterActionResponseRequestId = 0L
        serverClockOffset = 0L
        timeSyncEstablished = false
        stopStandbyPolling()
        currentContext = null
        currentLyrics = null
        currentSecondaryLyrics = null
        lastSearchResponse = null
        lastQueueResponse = null
        lastTrackSubmitResponse = null
        lastQueueRemoveResponse = null
        lastPlaybackControlResponse = null
        lastContentFilterActionResponse = null
        lastLocalPlaybackBlockedMessage = null
        lastInstanceLockMessage = null
        sourceCatalog = null
        handshakeRequestedAtNanos = 0L
        serverHandshakeReceived = false
        serverSessionAccepted = false
        lastServerWelcomeRejection = null
        // Search cache is intentionally kept — the GUI may restore recent results across reconnects.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun applyPlaybackSnapshot(snapshot: PlaybackSnapshot, fromSyncState: Boolean) {
        if (!canHandlePlaybackPackets()) return
        val trackProto = snapshot.track ?: return
        val playback = snapshot.playback?.toApi() ?: run {
            logger.error("Playback snapshot for '{}' is missing playback details; refusing to start audio.", trackProto.title)
            return
        }
        val track = trackProto.toApi().withLyrics(snapshot.lyric_lrc, snapshot.secondary_lyric_lrc)

        if (playback.url.isBlank()) {
            logger.error("Playback snapshot for '{}' is missing a playable URL; refusing to start audio.", track.title)
            stopActivePlayback()
            return
        }
        if (!applyClientMediaPolicy(track, playback.url)) {
            stopActivePlayback()
            return
        }
        val locallyAllowedTrack = applyLocalContentFilter(track) ?: run {
            stopActivePlayback()
            return
        }
        currentLyrics = parseLyrics(locallyAllowedTrack.lyricLrc)
        currentSecondaryLyrics = parseLyrics(locallyAllowedTrack.secondaryLyricLrc)

        when (snapshot.state) {
            PlaybackStateProto.PLAYING -> {
                if (!ensurePlaybackLock()) return
                val serverNow = currentServerMonotonicNow()
                val seekMs = anchoredPlaybackPositionMs(
                    positionMs = snapshot.position_ms,
                    anchorServerMonotonic = snapshot.position_anchor_server_monotonic,
                    durationMs = locallyAllowedTrack.durationMs,
                )
                logger.debug("PlaybackSnapshot PLAYING: '{}' seekMs={}", locallyAllowedTrack.title, seekMs)
                ClientAudioPlayer.play(playback, seekMs)
                VanillaSoundBlocker.stopBlockedSoundsIfNeeded()
                currentContext = TrackContext(
                    track = locallyAllowedTrack,
                    playback = playback,
                    state = PlaybackState.Playing(seekMs),
                    serverStartMonotonic = if (snapshot.position_anchor_server_monotonic != 0L) {
                        snapshot.position_anchor_server_monotonic - snapshot.position_ms.coerceAtLeast(0L) * 1_000_000L
                    } else {
                        serverNow - seekMs * 1_000_000L
                    },
                    serverResumeMonotonic = serverNow,
                )
                CoreEvents.bus.fire(
                    OnClientPlaybackStarted(
                        track = locallyAllowedTrack,
                        playback = playback,
                        positionMs = seekMs,
                        fromSyncState = fromSyncState,
                    )
                )
            }

            PlaybackStateProto.PAUSED -> {
                if (!ensurePlaybackLock()) return
                val posMs = normalizeClientPosition(snapshot.position_ms, locallyAllowedTrack.durationMs)
                val serverNow = currentServerMonotonicNow()
                logger.debug("PlaybackSnapshot PAUSED: '{}' posMs={}", locallyAllowedTrack.title, posMs)
                ClientAudioPlayer.play(playback, posMs)
                ClientAudioPlayer.pause()
                currentContext = TrackContext(
                    track = locallyAllowedTrack,
                    playback = playback,
                    state = PlaybackState.Paused(posMs),
                    serverStartMonotonic = serverNow - posMs * 1_000_000L,
                    serverResumeMonotonic = serverNow,
                )
                CoreEvents.bus.fire(
                    OnClientPlaybackStarted(
                        track = locallyAllowedTrack,
                        playback = playback,
                        positionMs = posMs,
                        fromSyncState = fromSyncState,
                    )
                )
            }

            PlaybackStateProto.STOPPED -> {
                releasePlaybackLock(clearMessage = true)
                stopActivePlayback(fireEvent = true)
            }
        }
        guiListener?.onPlaybackStateChanged()
    }

    private fun applyTimeSync(resp: SyncResponse) {
        serverClockOffset = timeSyncHandler.computeClientOffset(resp)
        timeSyncEstablished = true
    }

    private fun anchoredPlaybackPositionMs(
        positionMs: Long,
        anchorServerMonotonic: Long,
        durationMs: Long,
    ): Long {
        val basePositionMs = positionMs.coerceAtLeast(0L)
        if (!timeSyncEstablished || anchorServerMonotonic == 0L) {
            return normalizeClientPosition(basePositionMs, durationMs)
        }
        val elapsedMs = (currentServerMonotonicNow() - anchorServerMonotonic) / 1_000_000L
        return normalizeClientPosition(basePositionMs + elapsedMs, durationMs)
    }

    private fun normalizeClientPosition(positionMs: Long, durationMs: Long): Long {
        val nonNegative = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) nonNegative.coerceAtMost(durationMs) else nonNegative
    }

    private fun currentServerMonotonicNow(): Long = System.nanoTime() + serverClockOffset

    fun currentPositionMs(ctx: TrackContext): Long =
        when (val state = ctx.state) {
            is PlaybackState.Playing -> ClientAudioPlayer.currentPositionMs()
            is PlaybackState.Paused -> state.positionMs
            PlaybackState.Stopped -> 0L
        }.coerceAtMost(ctx.track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    fun sourceDisplayName(sourceId: String?): String {
        if (sourceId.isNullOrBlank()) return ""
        return sourceCatalog?.sources?.firstOrNull { it.id == sourceId }?.displayName ?: sourceId
    }

    fun isServerHandshakeMissing(nowNanos: Long = System.nanoTime()): Boolean =
        participationRequested &&
            handshakeRequestedAtNanos != 0L &&
            !serverHandshakeReceived &&
            nowNanos - handshakeRequestedAtNanos >= HANDSHAKE_GRACE_NANOS

    fun currentLyricLine(positionMs: Long): LyricLine? = currentLyrics?.lineAt(positionMs)

    fun currentSecondaryLyricLine(positionMs: Long): LyricLine? = currentSecondaryLyrics?.lineAt(positionMs)

    /** Playback broadcasts only apply while this client is actively registered. */
    private fun canHandlePlaybackPackets(): Boolean =
        serverSessionAccepted && playbackRegistrationActive && isPlaybackEnabledForCurrentServer()

    /** Direct request/response packets stay available during local standby. */
    private fun canHandleDirectResponses(): Boolean =
        participationRequested && serverSessionAccepted

    private fun canSendRequests(): Boolean =
        participationRequested && serverSessionAccepted

    private fun startSession(minecraft: Minecraft = Minecraft.getInstance()) {
        stopStandbyPolling()
        val desired = desiredParticipation(minecraft)
        standbyWaitingForLock = desired.waitForLock
        if (!desired.waitForLock) {
            clearInstanceLockStandby()
        }
        val locale = minecraft.languageManager.selected
        sendHandshake(locale, clientModVersion, clientProtocolVersion, desired.state)
        ClientNetworkSetup.stopSyncLoop()
        if (desired.waitForLock) {
            updateInstanceLockStandby(notifyUser = false)
        }
    }

    private fun applyDesiredParticipation(desired: DesiredParticipation) {
        standbyWaitingForLock = desired.waitForLock
        if (!desired.waitForLock) {
            stopStandbyPolling()
            clearInstanceLockStandby()
        }
        when (desired.state) {
            ClientStateProto.CLIENT_STATE_ACTIVE -> enterActiveParticipation()
            ClientStateProto.CLIENT_STATE_STANDBY -> enterStandbyParticipation(desired.waitForLock)
        }
    }

    private fun desiredParticipation(minecraft: Minecraft = Minecraft.getInstance()): DesiredParticipation {
        val playbackEnabled = isPlaybackEnabledForCurrentServer(minecraft)
        if (!playbackEnabled) {
            return DesiredParticipation(
                state = ClientStateProto.CLIENT_STATE_STANDBY,
                waitForLock = false,
            )
        }
        // Initial hello / standby polling only probe the shared lock. Actual ownership is
        // acquired later from ensurePlaybackLock(...) once playback packets make audio relevant.
        val waitingForLock = isGlobalInstancePlaybackLockEnabled() && !InstancePlaybackLock.probeAvailable()
        return DesiredParticipation(
            state = if (waitingForLock) ClientStateProto.CLIENT_STATE_STANDBY else ClientStateProto.CLIENT_STATE_ACTIVE,
            waitForLock = waitingForLock,
        )
    }

    private fun enterActiveParticipation() {
        stopStandbyPolling()
        standbyWaitingForLock = false
        clearInstanceLockStandby()
        if (playbackRegistrationActive) {
            ClientNetworkSetup.startSyncLoop()
            return
        }
        if (!serverSessionAccepted) return
        sendClientStateChange(ClientStateProto.CLIENT_STATE_ACTIVE)
    }

    private fun enterStandbyParticipation(waitForLock: Boolean) {
        val hadActiveRegistration = playbackRegistrationActive
        playbackRegistrationActive = false
        ClientNetworkSetup.stopSyncLoop()
        releasePlaybackLock(clearMessage = !waitForLock)
        stopActivePlayback()
        if (hadActiveRegistration) {
            sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
        }
        if (waitForLock) {
            updateInstanceLockStandby(notifyUser = serverHandshakeReceived)
            startStandbyPolling()
        } else {
            stopStandbyPolling()
        }
    }

    private fun TrackInfo.withLyrics(primary: String, secondary: String): TrackInfo = copy(
        lyricLrc = primary.ifEmpty { null },
        secondaryLyricLrc = secondary.ifEmpty { null },
        lyricsFetched = true,
    )

    private fun applyClientMediaPolicy(track: TrackInfo, url: String): Boolean =
        when (val verdict = ClientMediaFirewall.evaluate(url)) {
            MediaUrlPolicyResult.Allow -> true
            is MediaUrlPolicyResult.Reject -> {
                val message = renderLocalized(
                    LocalizedText.key(
                        "screen.moemusic.playback.local_media_blocked",
                        track.title.ifBlank { track.id },
                        verdict.reason,
                    )
                )
                lastLocalPlaybackBlockedMessage = message
                logger.info("Track '{}' blocked by local media policy: {}", track.title, verdict.reason.debugString())
                guiListener?.onLocalPlaybackBlocked(message)
                val minecraft = Minecraft.getInstance()
                minecraft.execute {
                    showPersistentRuntimeWarning(
                        minecraft,
                        McText.translatable("screen.moemusic.playback.toast.title"),
                        McText.literal(message),
                    )
                }
                false
            }
        }

    private fun applyLocalContentFilter(track: TrackInfo): TrackInfo? {
        if (!ContentFilterRuntime.clientFilterEnabled()) return track
        val reason = ContentFilterRuntime.trackBlockReason(track) ?: return track
        val message = renderLocalized(
            LocalizedText.key(
                "screen.moemusic.playback.local_filter_blocked",
                track.title.ifBlank { track.id },
                reason,
            )
        )
        lastLocalPlaybackBlockedMessage = message
        logger.info("Track '{}' blocked by local content filter: {}", track.title, reason.debugString())
        guiListener?.onLocalPlaybackBlocked(message)
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            showPersistentRuntimeWarning(
                minecraft,
                McText.translatable("screen.moemusic.filter.toast.title"),
                McText.literal(message),
            )
        }
        return null
    }

    private fun isGlobalInstancePlaybackLockEnabled(): Boolean =
        ModConfigManager.config.client.globalInstancePlaybackLock

    private fun ensurePlaybackLock(): Boolean {
        if (!isGlobalInstancePlaybackLockEnabled()) {
            releasePlaybackLock(clearMessage = true)
            return true
        }
        if (InstancePlaybackLock.tryAcquire()) {
            stopStandbyPolling()
            standbyWaitingForLock = false
            clearInstanceLockStandby()
            return true
        }

        enterStandbyForInstanceLock()
        return false
    }

    private fun enterStandbyForInstanceLock() {
        if (!participationRequested) return

        standbyWaitingForLock = true
        ClientNetworkSetup.stopSyncLoop()
        val hadActiveRegistration = playbackRegistrationActive
        playbackRegistrationActive = false
        if (hadActiveRegistration) {
            sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
        }
        releasePlaybackLock(clearMessage = false)
        stopActivePlayback()
        updateInstanceLockStandby(notifyUser = true)
        startStandbyPolling()
    }

    private fun releasePlaybackLock(clearMessage: Boolean) {
        InstancePlaybackLock.release()
        if (clearMessage) {
            clearInstanceLockStandby()
        }
    }

    private fun updateInstanceLockStandby(notifyUser: Boolean) {
        val message = renderInstanceLockWaitMessage()
        lastInstanceLockMessage = message
        guiListener?.onInstancePlaybackStandby(message)
        if (!notifyUser || instanceLockWaitNotified) return
        instanceLockWaitNotified = true
        val minecraft = Minecraft.getInstance()
        if (guiListener == null) {
            minecraft.execute {
                showWrappedSystemToast(
                    minecraft,
                    ClientToastIds.instanceLocked,
                    McText.translatable("screen.moemusic.instance_lock.toast.title"),
                    McText.literal(message),
                )
            }
        }
    }

    private fun clearInstanceLockStandby() {
        instanceLockWaitNotified = false
        lastInstanceLockMessage = null
        guiListener?.onInstancePlaybackStandby(null)
    }

    private fun renderInstanceLockWaitMessage(): String =
        renderLocalized(LocalizedText.key("screen.moemusic.playback.instance_lock_waiting"))

    private fun startStandbyPolling() {
        if (
            !participationRequested ||
            !serverHandshakeReceived ||
            !standbyWaitingForLock ||
            playbackRegistrationActive ||
            !isGlobalInstancePlaybackLockEnabled()
        ) {
            return
        }
        if (standbyPollJob?.isActive == true) return

        standbyPollJob = standbyScope.launch {
            while (isActive) {
                delay(STANDBY_LOCK_POLL_INTERVAL_MS.milliseconds)
                if (!participationRequested || !standbyWaitingForLock || playbackRegistrationActive) return@launch
                if (!isPlaybackEnabledForCurrentServer()) return@launch

                if (InstancePlaybackLock.probeAvailable()) {
                    Minecraft.getInstance().execute {
                        if (!participationRequested || !standbyWaitingForLock || playbackRegistrationActive) return@execute
                        if (!isPlaybackEnabledForCurrentServer()) return@execute
                        applyDesiredParticipation(desiredParticipation())
                    }
                    return@launch
                }
            }
        }
    }

    private fun stopStandbyPolling() {
        standbyPollJob?.cancel()
        standbyPollJob = null
    }

    private fun stopActivePlayback(fireEvent: Boolean = false) {
        val stoppedTrack = currentContext?.track
        ClientAudioPlayer.stop()
        currentContext = null
        currentLyrics = null
        currentSecondaryLyrics = null
        if (fireEvent && stoppedTrack != null) {
            CoreEvents.bus.fire(OnClientPlaybackStopped(stoppedTrack))
        }
        guiListener?.onPlaybackStateChanged()
    }

    private fun TrackAddMode.toProto(): TrackAddModeProto = when (this) {
        TrackAddMode.NORMAL -> TrackAddModeProto.TRACK_ADD_MODE_NORMAL
        TrackAddMode.SKIP_AUTOPLAY -> TrackAddModeProto.TRACK_ADD_MODE_SKIP_AUTOPLAY
        TrackAddMode.PLAY_NOW -> TrackAddModeProto.TRACK_ADD_MODE_PLAY_NOW
    }

    internal fun ensureDirectRequestSessionReady() {
        val minecraft = Minecraft.getInstance()
        if (minecraft.connection == null) {
            throw ClientRequestException("Not connected to a Minecraft server.")
        }
        if (!participationRequested) {
            throw ClientRequestException("MoeMusic client session is not initialized for this connection.")
        }
        if (!serverHandshakeReceived) {
            throw ClientRequestException("MoeMusic server handshake has not completed yet.")
        }
        if (!serverSessionAccepted) {
            val rejection = lastServerWelcomeRejection
            throw ClientRequestException(
                if (rejection != null) renderServerWelcomeRejection(rejection)
                else renderLocalized(LocalizedText.key("screen.moemusic.unavailable.rejected.body"))
            )
        }
    }

    private fun nextCorrelatedRequestId(): Long? =
        if (canSendRequests()) requestIdCounter.getAndIncrement().coerceAtLeast(1L) else null

    private fun <T> beginCorrelatedRequest(
        registry: PendingRequestRegistry<T>,
        packetId: PacketId,
        payloadFactory: (Long) -> ByteArray,
    ): Deferred<T>? {
        val requestId = nextCorrelatedRequestId() ?: return null
        val deferred = registry.register(requestId)
        send(packetId, payloadFactory(requestId))
        return deferred
    }

    private fun send(packetId: PacketId, payload: ByteArray) {
        val id = ResourceLocation(packetId.namespace, packetId.path)
        PacketSender.c2s().send(id, FriendlyByteBuf(Unpooled.wrappedBuffer(payload)))
    }

    private fun serverWelcomeRejection(msg: ServerWelcome): ServerWelcomeRejection =
        ServerWelcomeRejection(
            reason = when (msg.reject_reason) {
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_PROTOCOL_MISMATCH ->
                    ServerWelcomeRejectionReason.PROTOCOL_MISMATCH
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_SERVER_ERROR ->
                    ServerWelcomeRejectionReason.SERVER_ERROR
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_UNSPECIFIED ->
                    if (msg.server_protocol_version != 0 && msg.server_protocol_version != clientProtocolVersion) {
                        ServerWelcomeRejectionReason.PROTOCOL_MISMATCH
                    } else {
                        ServerWelcomeRejectionReason.UNKNOWN
                    }
            },
            clientProtocolVersion = clientProtocolVersion,
            serverProtocolVersion = msg.server_protocol_version,
            detail = msg.failure.ifBlank { null },
        )

    private fun renderServerWelcomeRejection(rejection: ServerWelcomeRejection): String =
        renderLocalized(rejection.toLocalizedText())

    private fun renderLocalized(text: LocalizedText): String =
        ClientLocalization.render(text)
}
