package org.lolicode.moemusic.platform.client.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lol.bai.badpackets.api.PacketSender
import lol.bai.badpackets.api.S2CPacketReceiver
import net.minecraft.resources.ResourceLocation
import org.lolicode.moemusic.client.mixin.MixinBadPacketsAbstractPacketHandler
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
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
 * Lives in the **client** source set because bad packets' S→C receiver registration
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
        // Register S→C receivers
        listOf(
            PacketIds.TRACK_SUBMIT_RESPONSE,
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE,
            PacketIds.SELECTION_SUBMIT_RESPONSE,
            PacketIds.SYNC_RESPONSE,
            PacketIds.SERVER_WELCOME,
            PacketIds.PLAYBACK_SNAPSHOT_PUSH,
            PacketIds.STATE_UPDATE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
            PacketIds.UI_BOOTSTRAP_RESPONSE,
            PacketIds.QUEUE_REMOVE_RESPONSE,
            PacketIds.PLAYBACK_CONTROL_RESPONSE,
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
        ).forEach { packetId ->
            registerReceiver(packetId) { buf ->
                val bytes = ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
                ClientPlaybackHandler.receiveFromServer(packetId, bytes)
            }
        }

        logger.debug("ClientNetworkSetup: channels declared and S→C receivers registered.")
    }

    /** Advertise MoeMusic's S→C channels through vanilla plugin-channel registration. */
    fun advertiseClientChannels() {
        val channels = listOf(
            PacketIds.TRACK_SUBMIT_RESPONSE,
            PacketIds.IDENTIFIER_SUBMIT_RESPONSE,
            PacketIds.SELECTION_SUBMIT_RESPONSE,
            PacketIds.SYNC_RESPONSE,
            PacketIds.SERVER_WELCOME,
            PacketIds.PLAYBACK_SNAPSHOT_PUSH,
            PacketIds.STATE_UPDATE,
            PacketIds.SEARCH_RESPONSE,
            PacketIds.QUEUE_RESPONSE,
            PacketIds.UI_BOOTSTRAP_RESPONSE,
            PacketIds.QUEUE_REMOVE_RESPONSE,
            PacketIds.PLAYBACK_CONTROL_RESPONSE,
            PacketIds.CONTENT_FILTER_ACTION_RESPONSE,
        ).map { ResourceLocation(it.namespace, it.path) }.toSet()

        runCatching {
            (PacketSender.c2s() as MixinBadPacketsAbstractPacketHandler)
                .moemusicSendVanillaChannelRegisterPacket(channels)
        }.onFailure { error ->
            logger.warn("Could not advertise MoeMusic plugin channels: {}", error.message)
        }
    }

    /**
     * Start the periodic clock-sync coroutine (sends [SyncRequest] every [SYNC_INTERVAL_MS] ms).
     * No-op if already running.
     */
    fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
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
        S2CPacketReceiver.register(ResourceLocation(id.namespace, id.path)) { _, _, buf, _ ->
            try {
                if (ClientPlaybackHandler.acceptsServerPacket(id)) {
                    handler(buf)
                } else {
                    logger.debug("Dropping packet {} before accepted MoeMusic server handshake.", id)
                }
            } catch (e: Exception) {
                logger.error("Error handling packet {}: {}", id, e.message)
            }
        }
    }
}
