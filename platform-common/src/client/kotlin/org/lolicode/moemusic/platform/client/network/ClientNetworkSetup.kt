package org.lolicode.moemusic.platform.client.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.bai.badpackets.api.play.PlayPackets
import net.minecraft.resources.Identifier
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.ContentFilterActionResponse
import org.lolicode.moemusic.core.protocol.proto.IdentifierSubmitResponse
import org.lolicode.moemusic.core.protocol.proto.PlayTrack
import org.lolicode.moemusic.core.protocol.proto.PlaybackControlResponse
import org.lolicode.moemusic.core.protocol.proto.QueueRemoveResponse
import org.lolicode.moemusic.core.protocol.proto.QueueResponse
import org.lolicode.moemusic.core.protocol.proto.SearchResponse
import org.lolicode.moemusic.core.protocol.proto.SelectionSubmitResponse
import org.lolicode.moemusic.core.protocol.proto.ServerHandshake
import org.lolicode.moemusic.core.protocol.proto.StateUpdate
import org.lolicode.moemusic.core.protocol.proto.SyncRequest
import org.lolicode.moemusic.core.protocol.proto.SyncResponse
import org.lolicode.moemusic.core.protocol.proto.SyncState
import org.lolicode.moemusic.core.protocol.proto.TrackSubmitResponse
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Client-side network setup for MoeMusic.
 *
 * Registers C-side receivers for all S→C packets via bad packets, and starts the
 * background clock-sync coroutine loop.
 *
 * Called from `MoeMusicClient.onInitializeClient`.
 * Lives in the **client** source set because bad packets' `PlayPackets.registerClientReceiver`
 * is a client-only API.
 */
object ClientNetworkSetup {

    private val logger = LoggerFactory.getLogger(ClientNetworkSetup::class.java)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Running sync-loop job, so it can be cancelled on disconnect if needed. */
    private var syncJob: Job? = null

    private const val SYNC_INTERVAL_MS = 30_000L

    /**
     * Register client-side receivers for all S→C packet IDs.
     * Each callback decodes the Wire proto and delegates to [ClientPlaybackHandler].
     */
    fun register() {
        // Per Bad Packets javadoc: registerClientChannel and registerServerChannel
        // must be called on ALL sides. Do channel declarations first, then receivers.

        // Declare S→C channels (client receives these)
        listOf(
            PacketIds.TRACK_SUBMIT_RESPONSE,
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE,
            PacketIds.SELECTION_SUBMIT_RESPONSE,
            PacketIds.SYNC_RESPONSE,
            PacketIds.SERVER_HANDSHAKE,
            PacketIds.PLAY_TRACK,
            PacketIds.SYNC_STATE,
            PacketIds.STATE_UPDATE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
            PacketIds.QUEUE_REMOVE_RESPONSE,
            PacketIds.PLAYBACK_CONTROL_RESPONSE,
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
        ).forEach { PlayPackets.registerClientChannel(Identifier.fromNamespaceAndPath(it.namespace, it.path)) }

        // Declare C→S channels (client sends these)
        listOf(
            PacketIds.CLIENT_HANDSHAKE,
            PacketIds.CLIENT_STATE_CHANGE,
            PacketIds.SYNC_REQUEST,
            PacketIds.TRACK_SUBMIT,
            PacketIds.IDENTIFIER_SUBMIT,
            PacketIds.SELECTION_SUBMIT,
            PacketIds.SEARCH_REQUEST,
            PacketIds.QUEUE_REQUEST,
            PacketIds.QUEUE_REMOVE_REQUEST,
            PacketIds.PLAYBACK_CONTROL_REQUEST,
            PacketIds.CONTENT_FILTER_ACTION_REQUEST,
        ).forEach { PlayPackets.registerServerChannel(Identifier.fromNamespaceAndPath(it.namespace, it.path)) }

        // Register S→C receivers
        registerReceiver(PacketIds.PLAY_TRACK) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handlePlayTrack(PlayTrack.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.SYNC_STATE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleSyncState(SyncState.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.STATE_UPDATE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleStateUpdate(StateUpdate.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.SYNC_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleSyncResponse(SyncResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.SERVER_HANDSHAKE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleServerHandshake(ServerHandshake.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.SEARCH_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleSearchResponse(SearchResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.TRACK_SUBMIT_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleTrackSubmitResponse(TrackSubmitResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.IDENTIFIER_SUBMIT_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleIdentifierSubmitResponse(IdentifierSubmitResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.SELECTION_SUBMIT_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleSelectionSubmitResponse(SelectionSubmitResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.QUEUE_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleQueueResponse(QueueResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.QUEUE_REMOVE_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleQueueRemoveResponse(QueueRemoveResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.PLAYBACK_CONTROL_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handlePlaybackControlResponse(PlaybackControlResponse.ADAPTER.decode(bytes))
        }

        registerReceiver(PacketIds.CONTENT_FILTER_ACTION_RESPONSE) { buf ->
            val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
            ClientPlaybackHandler.handleContentFilterActionResponse(ContentFilterActionResponse.ADAPTER.decode(bytes))
        }

        logger.debug("ClientNetworkSetup: channels declared and S→C receivers registered.")
    }

    /**
     * Start the periodic clock-sync coroutine (sends [SyncRequest] every [SYNC_INTERVAL_MS] ms).
     * No-op if already running.
     */
    fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            delay(5_000.milliseconds) // Initial settling delay
            while (isActive) {
                try {
                    ClientPlaybackHandler.sendSyncRequest()
                } catch (e: Exception) {
                    logger.debug("SyncRequest failed: {}", e.message)
                }
                delay(SYNC_INTERVAL_MS.milliseconds)
            }
        }
    }

    /** Cancel the sync loop (e.g. on disconnect). Safe to call if not running. */
    fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun registerReceiver(
        id: PacketId,
        handler: (net.minecraft.network.FriendlyByteBuf) -> Unit,
    ) {
        PlayPackets.registerClientReceiver(
            Identifier.fromNamespaceAndPath(id.namespace, id.path)
        ) { _, buf ->
            try {
                handler(buf)
            } catch (e: Exception) {
                logger.error("Error handling packet {}: {}", id, e.message)
            }
        }
    }
}
