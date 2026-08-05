package org.lolicode.moemusic.velocity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.PlaybackResourceProto
import org.lolicode.moemusic.core.protocol.proto.PlaybackSnapshot
import org.lolicode.moemusic.core.protocol.proto.PlaybackSnapshotPush
import org.lolicode.moemusic.core.protocol.proto.TrackInfoProto

class VelocityPayloadPolicyTest {
    @Test
    fun `oversized playback snapshot fits after optional lyrics are stripped`() {
        val result = VelocityPayloadPolicy.fit(
            PacketIds.PLAYBACK_SNAPSHOT_PUSH,
            snapshot(lyrics = "x".repeat(40_000)).encode(),
            maxPayloadSize = 32_767,
        )

        val send = assertIs<VelocityPayloadPolicy.Result.Send>(result)
        assertTrue(send.strippedLyrics)
        assertTrue(send.payload.size <= 32_767)
        val reduced = PlaybackSnapshotPush.ADAPTER.decode(send.payload)
        assertEquals("", reduced.snapshot?.lyric_lrc)
        assertEquals("", reduced.snapshot?.secondary_lyric_lrc)
    }

    @Test
    fun `irreducible playback snapshot reports stable track identity`() {
        val result = VelocityPayloadPolicy.fit(
            PacketIds.PLAYBACK_SNAPSHOT_PUSH,
            snapshot(url = "x".repeat(40_000)).encode(),
            maxPayloadSize = 32_767,
        )

        val oversized = assertIs<VelocityPayloadPolicy.Result.Oversized>(result)
        assertEquals(VelocityPayloadPolicy.TrackIdentity("test", "track"), oversized.trackIdentity)
        assertTrue(oversized.payloadSize > 32_767)
    }

    @Test
    fun `other oversized packets are not changed`() {
        val payload = ByteArray(40_000)
        val result = VelocityPayloadPolicy.fit(PacketIds.STATE_UPDATE, payload, 32_767)

        assertEquals(VelocityPayloadPolicy.Result.Oversized(payload.size), result)
    }

    private fun snapshot(lyrics: String = "", url: String = "https://example.test/audio"): PlaybackSnapshotPush =
        PlaybackSnapshotPush(
            snapshot = PlaybackSnapshot(
                track = TrackInfoProto(source_id = "test", id = "track", title = "Track"),
                playback = PlaybackResourceProto(url = url),
                lyric_lrc = lyrics,
                secondary_lyric_lrc = lyrics,
            ),
        )
}

