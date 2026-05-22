package org.lolicode.moemusic.platform.client.playback

import kotlinx.coroutines.Deferred
import org.lolicode.moemusic.api.client.IClientRequestService
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.clientcore.request.ClientRequestTransport
import org.lolicode.moemusic.clientcore.request.DirectClientRequestService
import org.lolicode.moemusic.core.protocol.proto.*

internal object ClientRequestServiceImpl : IClientRequestService by DirectClientRequestService(ClientPlaybackTransport)

private object ClientPlaybackTransport : ClientRequestTransport {
    override fun ensureDirectRequestSessionReady() {
        ClientPlaybackHandler.ensureDirectRequestSessionReady()
    }

    override fun beginSearchRequest(
        query: String,
        sourceId: String,
        limit: Int,
        offset: Int,
    ): Deferred<SearchResponse>? =
        ClientPlaybackHandler.beginSearchRequest(query, sourceId, limit, offset)

    override fun beginQueueRequest(): Deferred<QueueResponse>? =
        ClientPlaybackHandler.beginQueueRequest()

    override fun beginQueueRemoveRequest(sourceId: String, trackId: String): Deferred<QueueRemoveResponse>? =
        ClientPlaybackHandler.beginQueueRemoveRequest(sourceId, trackId)

    override fun beginTrackSubmitRequest(track: TrackInfo, mode: TrackAddMode): Deferred<TrackSubmitResponse>? =
        ClientPlaybackHandler.beginTrackSubmitRequest(track, mode)

    override fun beginTrackSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<TrackSubmitResponse>? =
        ClientPlaybackHandler.beginTrackSubmitRequest(entry, mode)

    override fun beginIdentifierSubmitRequest(identifier: String, mode: TrackAddMode): Deferred<IdentifierSubmitResponse>? =
        ClientPlaybackHandler.beginIdentifierSubmitRequest(identifier, mode)

    override fun beginSelectionSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<SelectionSubmitResponse>? =
        ClientPlaybackHandler.beginSelectionSubmitRequest(entry, mode)

    override fun beginPlaybackControlRequest(
        action: PlaybackControlAction,
        positionMs: Long,
    ): Deferred<PlaybackControlResponse>? =
        ClientPlaybackHandler.beginPlaybackControlRequest(action, positionMs)

    override fun beginContentFilterTrackActionRequest(
        sourceId: String,
        trackId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        ClientPlaybackHandler.beginContentFilterTrackActionRequest(sourceId, trackId, note, ban)

    override fun beginContentFilterArtistActionRequest(
        sourceId: String,
        artistId: String,
        note: String?,
        ban: Boolean,
    ): Deferred<ContentFilterActionResponse>? =
        ClientPlaybackHandler.beginContentFilterArtistActionRequest(sourceId, artistId, note, ban)
}
