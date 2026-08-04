package org.lolicode.moemusic.spigot

import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.PlaybackSnapshotPush

internal object SpigotPayloadPolicy {
    sealed interface Result {
        data class Send(val payload: ByteArray, val strippedLyrics: Boolean = false) : Result
        data class Oversized(val payloadSize: Int, val trackIdentity: TrackIdentity? = null) : Result
    }

    data class TrackIdentity(val sourceId: String, val trackId: String)

    fun fit(packetId: PacketId, payload: ByteArray, maxPayloadSize: Int): Result {
        if (payload.size <= maxPayloadSize) return Result.Send(payload)
        if (packetId != PacketIds.PLAYBACK_SNAPSHOT_PUSH) return Result.Oversized(payload.size)

        val message = runCatching { PlaybackSnapshotPush.ADAPTER.decode(payload) }.getOrNull()
            ?: return Result.Oversized(payload.size)
        val snapshot = message.snapshot ?: return Result.Oversized(payload.size)
        val track = snapshot.track
        val identity = track
            ?.takeIf { it.source_id.isNotBlank() && it.id.isNotBlank() }
            ?.let { TrackIdentity(it.source_id, it.id) }
        val reduced = message.copy(
            snapshot = snapshot.copy(lyric_lrc = "", secondary_lyric_lrc = ""),
        ).encode()

        return if (reduced.size <= maxPayloadSize) {
            Result.Send(reduced, strippedLyrics = true)
        } else {
            Result.Oversized(reduced.size, identity)
        }
    }
}
