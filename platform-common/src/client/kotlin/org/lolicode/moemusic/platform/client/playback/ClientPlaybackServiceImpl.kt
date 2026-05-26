package org.lolicode.moemusic.platform.client.playback

import net.minecraft.client.Minecraft
import org.lolicode.moemusic.api.client.ClientAvailabilityIssue
import org.lolicode.moemusic.api.client.ClientSearchCatalog
import org.lolicode.moemusic.api.client.ClientSearchSource
import org.lolicode.moemusic.api.client.ClientVolumeOverride
import org.lolicode.moemusic.api.client.IClientPlaybackService
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.core.config.ModConfigManager

internal object ClientPlaybackServiceImpl : IClientPlaybackService {

    override val currentContext: TrackContext?
        get() = ClientPlaybackHandler.currentContext

    override val searchCatalog: ClientSearchCatalog?
        get() = ClientPlaybackHandler.sourceCatalog?.let { catalog ->
            ClientSearchCatalog(
                sources = catalog.sources.map { source ->
                    ClientSearchSource(
                        id = source.id,
                        displayName = source.displayName,
                        searchable = source.searchable,
                    )
                },
                defaultSourceId = catalog.defaultSourceId,
            )
        }

    override val currentParticipationState: UserParticipationState?
        get() = ClientPlaybackHandler.currentParticipationState()

    override val currentAvailabilityIssue: ClientAvailabilityIssue?
        get() = when (ClientPlaybackHandler.currentAvailabilityIssue()) {
            null -> null
            org.lolicode.moemusic.clientcore.playback.AvailabilityIssue.SERVER_MISSING ->
                ClientAvailabilityIssue.SERVER_MISSING
            org.lolicode.moemusic.clientcore.playback.AvailabilityIssue.SERVER_REJECTED ->
                ClientAvailabilityIssue.SERVER_MISSING
        }

    override val configuredVolumePercent: Int
        get() = ClientVolumeRuntime.configuredVolumePercent

    override val effectiveVolumePercent: Int
        get() = ClientVolumeRuntime.effectiveVolumePercent

    override fun currentPositionMs(): Long? =
        currentContext?.let(ClientPlaybackHandler::currentPositionMs)

    override fun setConfiguredVolumePercent(percent: Int) {
        ClientVolumeRuntime.setAndPersistConfiguredVolumePercent(percent)
    }

    override fun setTransientVolumeOverride(ownerId: String, override: ClientVolumeOverride) {
        ClientVolumeRuntime.setTransientVolumeOverride(ownerId, override)
    }

    override fun clearTransientVolumeOverride(ownerId: String) {
        ClientVolumeRuntime.clearTransientVolumeOverride(ownerId)
    }

    override fun isPlaybackEnabledForCurrentServer(): Boolean =
        ClientPlaybackHandler.isPlaybackEnabledForCurrentServer(Minecraft.getInstance())

    override fun setPlaybackEnabledForCurrentServer(enabled: Boolean) {
        val minecraft = Minecraft.getInstance()
        val serverScope = ClientPlaybackHandler.currentServerScope(minecraft)
        ModConfigManager.updateClient { client ->
            val disabledServers = client.disabledServers.toMutableList().apply {
                if (serverScope != null) {
                    val key = serverScope.key
                    remove(key)
                    if (!enabled) add(key)
                }
            }
            client.copy(disabledServers = disabledServers.distinct())
        }
        ClientPlaybackHandler.syncParticipationWithCurrentConfig()
    }

    override fun syncParticipationWithCurrentConfig() {
        ClientPlaybackHandler.syncParticipationWithCurrentConfig()
    }
}
