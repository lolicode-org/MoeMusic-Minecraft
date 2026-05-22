package org.lolicode.moemusic.platform.client.ui.config

import org.lolicode.moemusic.platform.text.McText

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigCategory
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import me.shedaniel.clothconfig2.gui.entries.MultiElementListEntry
import me.shedaniel.clothconfig2.gui.entries.NestedListListEntry
import me.shedaniel.clothconfig2.impl.builders.DropdownMenuBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.controls.KeyBindsScreen
import net.minecraft.network.chat.Component
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.plugin.*
import org.lolicode.moemusic.core.config.*
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuleEditor
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.media.MediaHostListMode
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.plugin.PluginConfigIO
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.platform.client.audio.VanillaSoundBlocker
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.lolicode.moemusic.platform.client.i18n.ClientLocalization
import org.lolicode.moemusic.platform.client.ui.ClientToastIds
import org.lolicode.moemusic.platform.client.ui.showWrappedSystemToast
import org.lolicode.moemusic.platform.runtime.MoePlatform
import java.nio.file.Path
import java.util.*

/**
 * Cloth Config settings screen for MoeMusic.
 *
 * Opened from the MoeMusic player screen, the optional config keybinding,
 * or a mod-menu integration when Cloth Config is present.
 *
 * The screen has one tab for the core MoeMusic settings and one tab per registered plugin.
 * Plugins that expose [org.lolicode.moemusic.api.plugin.Plugin.configSpec] get their settings
 * entries generated automatically. Plugins without a config spec still get a tab that shows
 * an informational "no settings available" message.
 *
 * Changes are saved when the user clicks **Done**:
 * - Core settings → `<configDir>/moemusic.toml` via conflict-aware [ModConfigManager] updates
 * - Plugin settings → `<configDir>/plugins/<configId>.toml` via [PluginConfigIO]
 */
@Suppress("UnstableApiUsage")
object MoeMusicConfigScreen {

    private data class EditableTrackRule(
        var sourceId: String = "",
        var trackId: String = "",
        var note: String = "",
    )

    private data class EditableArtistRule(
        var sourceId: String = "",
        var artistId: String = "",
        var note: String = "",
    )

    private data class EditableTextRule(
        var scope: ContentFilterTextRuleScope = ContentFilterTextRuleScope.ALL,
        var mode: ContentFilterTextRuleMode = ContentFilterTextRuleMode.SUBSTRING,
        var ignoreCase: Boolean = true,
        var pattern: String = "",
    )

    private data class ConfigEdits(
        val defaultSourceId: String,
        val defaultLanguage: String,
        val autoplay: AutoplayConfig,
        val voteRequiredPercent: Int,
        val permissions: PermissionDefaultsConfig,
        val contentFilter: ContentFilterRules,
        val media: MediaPolicyConfig,
        val playbackEnabled: Boolean,
        val blockVanillaMusic: Boolean,
        val blockRecords: Boolean,
        val globalInstancePlaybackLock: Boolean,
        val disabledServers: List<String>,
        val clientContentFilter: ClientContentFilterConfig,
        val clientCoverArt: CoverArtConfig,
        val hud: NowPlayingHudConfig,
    )

    private data class MergeResult(
        val config: MoeMusicConfig,
        val hadConflicts: Boolean,
    )

    private data class PluginConfigUiEntry(
        val specEntry: PluginConfigEntry,
        val uiEntry: AbstractConfigListEntry<*>,
    )

    /**
     * Build the config screen.
     *
     * @param parent The screen displayed when the user presses **Cancel** / closes the screen.
     * @return A fully configured [Screen] backed by [ConfigBuilder].
     */
    fun build(parent: Screen?): Screen {
        val current = ModConfigManager.config

        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(McText.translatable("config.moemusic.title"))

        val entryBuilder: ConfigEntryBuilder = builder.entryBuilder()

        val generalCategory: ConfigCategory = builder.getOrCreateCategory(
            McText.translatable("config.moemusic.category.general")
        )
        val hudCategory: ConfigCategory = builder.getOrCreateCategory(
            McText.translatable("config.moemusic.category.now_playing_hud")
        )
        val mediaCategory: ConfigCategory = builder.getOrCreateCategory(
            McText.translatable("config.moemusic.category.media")
        )
        val contentFilterCategory: ConfigCategory = builder.getOrCreateCategory(
            McText.translatable("config.moemusic.category.content_filter")
        )
        val permissionsCategory: ConfigCategory = builder.getOrCreateCategory(
            McText.translatable("config.moemusic.category.permissions")
        )
        val systemCategory: ConfigCategory = builder.getOrCreateCategory(
            McText.translatable("config.moemusic.category.system")
        )
        val defaultConfig = MoeMusicConfig()
        val defaultAutoplay = defaultConfig.autoplay
        val defaultContentFilter = defaultConfig.contentFilter
        val defaultMedia = defaultConfig.media
        val defaultPermissions = defaultConfig.permissions
        val defaultClient = defaultConfig.client
        val defaultClientContentFilter = defaultClient.contentFilter
        val defaultHud = defaultConfig.client.nowPlayingHud
        val knownSourceDisplayNames = knownSourceDisplayNames()
        val defaultSourceOptions = defaultSourceSelectorValues(current.defaultSourceId, knownSourceDisplayNames.keys)
        val defaultLanguageOptions = defaultLanguageSelectorValues(current.defaultLanguage, Localization.availableLocales())
        val currentServerScope = ClientPlaybackHandler.currentServerScope()

        generalCategory.addEntry(
            entryBuilder.startTextDescription(
                McText.translatable("config.moemusic.client_only_notice")
            ).build()
        )
        generalCategory.addEntry(
            ActionButtonListEntry(
                McText.translatable("config.moemusic.keybinds"),
                McText.translatable("config.moemusic.open_keybinds"),
            ) { screen ->
                val mc = Minecraft.getInstance()
                val parent = screen ?: return@ActionButtonListEntry
                mc.setScreen(KeyBindsScreen(parent, mc.options))
            }
        )

        contentFilterCategory.addEntry(
            entryBuilder.startTextDescription(
                McText.translatable("config.moemusic.content_filter.scope_notice")
            ).build()
        )

        var newPlaybackEnabled = current.client.playbackEnabled
        generalCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.client.playback_enabled"),
                current.client.playbackEnabled,
            )
                .setDefaultValue(defaultClient.playbackEnabled)
                .setTooltip(McText.translatable("config.moemusic.client.playback_enabled.tooltip"))
                .setSaveConsumer { newPlaybackEnabled = it }
                .build()
        )

        var newDisabledServers = current.client.disabledServers
        if (currentServerScope != null) {
            generalCategory.addEntry(
                entryBuilder.startTextDescription(
                    McText.translatable(
                        "config.moemusic.client.current_server",
                        currentServerScope.displayName,
                    )
                ).build()
            )

            generalCategory.addEntry(
                entryBuilder.startBooleanToggle(
                    McText.translatable("config.moemusic.client.current_server_playback_enabled"),
                    currentServerScope.key !in current.client.disabledServers,
                )
                    .setDefaultValue(true)
                    .setTooltip(
                        McText.translatable(
                            "config.moemusic.client.current_server_playback_enabled.tooltip",
                            currentServerScope.displayName,
                        )
                    )
                    .setSaveConsumer { enabled ->
                        newDisabledServers = if (enabled) {
                            newDisabledServers - currentServerScope.key
                        } else {
                            (newDisabledServers + currentServerScope.key).distinct()
                        }
                    }
                    .build()
            )
        } else {
            generalCategory.addEntry(
                entryBuilder.startTextDescription(
                    McText.translatable("config.moemusic.client.current_server.unavailable")
                ).build()
            )
        }

        var newBlockVanillaMusic = current.client.blockVanillaMusic
        generalCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.client.block_vanilla_music"),
                current.client.blockVanillaMusic,
            )
                .setDefaultValue(defaultClient.blockVanillaMusic)
                .setTooltip(McText.translatable("config.moemusic.client.block_vanilla_music.tooltip"))
                .setSaveConsumer { newBlockVanillaMusic = it }
                .build()
        )

        var newBlockRecords = current.client.blockRecords
        generalCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.client.block_records"),
                current.client.blockRecords,
            )
                .setDefaultValue(defaultClient.blockRecords)
                .setTooltip(McText.translatable("config.moemusic.client.block_records.tooltip"))
                .setSaveConsumer { newBlockRecords = it }
                .build()
        )

        var newGlobalInstancePlaybackLock = current.client.globalInstancePlaybackLock
        generalCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.client.global_instance_playback_lock"),
                current.client.globalInstancePlaybackLock,
            )
                .setDefaultValue(defaultClient.globalInstancePlaybackLock)
                .setTooltip(McText.translatable("config.moemusic.client.global_instance_playback_lock.tooltip"))
                .setSaveConsumer { newGlobalInstancePlaybackLock = it }
                .build()
        )

        var newDefaultSourceId = current.defaultSourceId.trim()
        generalCategory.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.default_source_id"),
                defaultSourceOptions,
                newDefaultSourceId,
            )
                .setDefaultValue(defaultConfig.defaultSourceId)
                .setNameProvider { sourceId ->
                    defaultSourceSelectorLabel(sourceId, knownSourceDisplayNames)
                }
                .setTooltip(McText.translatable("config.moemusic.default_source_id.tooltip"))
                .setSaveConsumer { newDefaultSourceId = it.trim() }
                .build()
        )

        var newDefaultLanguage = current.defaultLanguage
        generalCategory.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.default_language"),
                defaultLanguageOptions,
                newDefaultLanguage,
            )
                .setDefaultValue(defaultConfig.defaultLanguage)
                .setNameProvider { McText.literal(it) }
                .setTooltip(McText.translatable("config.moemusic.default_language.tooltip"))
                .setSaveConsumer { newDefaultLanguage = normalizeLanguageId(it) }
                .build()
        )

        var newEnabled = current.autoplay.enabled
        generalCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.autoplay.enabled"),
                current.autoplay.enabled,
            )
                .setDefaultValue(defaultAutoplay.enabled)
                .setTooltip(McText.translatable("config.moemusic.autoplay.enabled.tooltip"))
                .setSaveConsumer { newEnabled = it }
                .build()
        )

        var newMaxTracks = current.autoplay.maxTracksPerSource
        generalCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.autoplay.max_tracks_per_source"),
                current.autoplay.maxTracksPerSource,
            )
                .setDefaultValue(defaultAutoplay.maxTracksPerSource)
                .setTooltip(
                    McText.translatable("config.moemusic.autoplay.max_tracks_per_source.tooltip")
                )
                .setSaveConsumer { newMaxTracks = it }
                .build()
        )

        var newContentFilter = current.contentFilter
        var exactTrackRules = current.contentFilter.exactTrackRules.map(::toEditableTrackRule)
        var exactArtistRules = current.contentFilter.exactArtistRules.map(::toEditableArtistRule)
        var textRules = current.contentFilter.textRules.map(::toEditableTextRule)
        contentFilterCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.content_filter.enabled"),
                current.contentFilter.enabled,
            )
                .setDefaultValue(defaultContentFilter.enabled)
                .setTooltip(McText.translatable("config.moemusic.content_filter.enabled.tooltip"))
                .setSaveConsumer { newContentFilter = newContentFilter.copy(enabled = it) }
                .build()
        )

        var newClientContentFilter = current.client.contentFilter
        contentFilterCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.client.content_filter.enabled"),
                current.client.contentFilter.enabled,
            )
                .setDefaultValue(defaultClientContentFilter.enabled)
                .setTooltip(McText.translatable("config.moemusic.client.content_filter.enabled.tooltip"))
                .setSaveConsumer { newClientContentFilter = newClientContentFilter.copy(enabled = it) }
                .build()
        )
        contentFilterCategory.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.client.content_filter.search_list_mode"),
                ContentFilterClientListMode.entries.toTypedArray(),
                current.client.contentFilter.searchListMode,
            )
                .setDefaultValue(defaultClientContentFilter.searchListMode)
                .setNameProvider { value ->
                    McText.translatable("config.moemusic.client.content_filter.list_mode.${value.name.lowercase()}")
                }
                .setTooltip(McText.translatable("config.moemusic.client.content_filter.search_list_mode.tooltip"))
                .setSaveConsumer { newClientContentFilter = newClientContentFilter.copy(searchListMode = it) }
                .build()
        )
        contentFilterCategory.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.client.content_filter.queue_list_mode"),
                ContentFilterClientListMode.entries.toTypedArray(),
                current.client.contentFilter.queueListMode,
            )
                .setDefaultValue(defaultClientContentFilter.queueListMode)
                .setNameProvider { value ->
                    McText.translatable("config.moemusic.client.content_filter.list_mode.${value.name.lowercase()}")
                }
                .setTooltip(McText.translatable("config.moemusic.client.content_filter.queue_list_mode.tooltip"))
                .setSaveConsumer { newClientContentFilter = newClientContentFilter.copy(queueListMode = it) }
                .build()
        )
        addTextRuleEntries(
            category = contentFilterCategory,
            entryBuilder = entryBuilder,
            initialRows = textRules,
            defaultRows = defaultContentFilter.textRules.map(::toEditableTextRule),
        ) { textRules = it }
        addTrackRuleEntries(
            category = contentFilterCategory,
            entryBuilder = entryBuilder,
            initialRows = exactTrackRules,
            defaultRows = defaultContentFilter.exactTrackRules.map(::toEditableTrackRule),
        ) { exactTrackRules = it }
        addArtistRuleEntries(
            category = contentFilterCategory,
            entryBuilder = entryBuilder,
            initialRows = exactArtistRules,
            defaultRows = defaultContentFilter.exactArtistRules.map(::toEditableArtistRule),
        ) { exactArtistRules = it }

        var newMedia = current.media

        mediaCategory.addEntry(
            entryBuilder.startTextDescription(
                McText.translatable(
                    "config.moemusic.media.cover_art.description"
                )
            ).build()
        )
        var newClientCoverArt = current.client.coverArt
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.cover_art.max_download_mebibytes"),
                current.client.coverArt.maxDownloadMebibytes,
            )
                .setDefaultValue(defaultClient.coverArt.maxDownloadMebibytes)
                .setTooltip(McText.translatable("config.moemusic.media.cover_art.max_download_mebibytes.tooltip"))
                .setSaveConsumer { mebibytes ->
                    newClientCoverArt = newClientCoverArt.copy(maxDownloadMebibytes = mebibytes)
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.cover_art.max_source_dimension"),
                current.client.coverArt.maxSourceDimension,
            )
                .setDefaultValue(defaultClient.coverArt.maxSourceDimension)
                .setTooltip(McText.translatable("config.moemusic.media.cover_art.max_source_dimension.tooltip"))
                .setSaveConsumer { dimension ->
                    newClientCoverArt = newClientCoverArt.copy(maxSourceDimension = dimension)
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startLongField(
                McText.translatable("config.moemusic.media.cover_art.max_source_pixels"),
                current.client.coverArt.maxSourcePixels,
            )
                .setDefaultValue(defaultClient.coverArt.maxSourcePixels)
                .setTooltip(McText.translatable("config.moemusic.media.cover_art.max_source_pixels.tooltip"))
                .setSaveConsumer { pixels ->
                    newClientCoverArt = newClientCoverArt.copy(maxSourcePixels = pixels)
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.cover_art.max_decode_downscale_factor"),
                current.client.coverArt.maxDecodeDownscaleFactor,
            )
                .setDefaultValue(defaultClient.coverArt.maxDecodeDownscaleFactor)
                .setTooltip(
                    McText.translatable("config.moemusic.media.cover_art.max_decode_downscale_factor.tooltip")
                )
                .setSaveConsumer { factor ->
                    newClientCoverArt = newClientCoverArt.copy(maxDecodeDownscaleFactor = factor)
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.cover_art.max_texture_size"),
                current.client.coverArt.maxTextureSize,
            )
                .setDefaultValue(defaultClient.coverArt.maxTextureSize)
                .setTooltip(McText.translatable("config.moemusic.media.cover_art.max_texture_size.tooltip"))
                .setSaveConsumer { size ->
                    newClientCoverArt = newClientCoverArt.copy(maxTextureSize = size)
                }
                .build()
        )

        mediaCategory.addEntry(
            entryBuilder.startTextDescription(
                McText.translatable(
                    "config.moemusic.media.rate_limit.description"
                )
            ).build()
        )
        mediaCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.media.rate_limit.enabled"),
                current.media.rateLimit.enabled,
            )
                .setDefaultValue(defaultMedia.rateLimit.enabled)
                .setTooltip(McText.translatable("config.moemusic.media.rate_limit.enabled.tooltip"))
                .setSaveConsumer { enabled ->
                    newMedia = newMedia.copy(rateLimit = newMedia.rateLimit.copy(enabled = enabled))
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.rate_limit.window_seconds"),
                current.media.rateLimit.windowSeconds,
            )
                .setDefaultValue(defaultMedia.rateLimit.windowSeconds)
                .setTooltip(McText.translatable("config.moemusic.media.rate_limit.window_seconds.tooltip"))
                .setSaveConsumer { seconds ->
                    newMedia = newMedia.copy(rateLimit = newMedia.rateLimit.copy(windowSeconds = seconds))
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.rate_limit.search_requests"),
                current.media.rateLimit.searchRequests,
            )
                .setDefaultValue(defaultMedia.rateLimit.searchRequests)
                .setTooltip(McText.translatable("config.moemusic.media.rate_limit.search_requests.tooltip"))
                .setSaveConsumer { count ->
                    newMedia = newMedia.copy(rateLimit = newMedia.rateLimit.copy(searchRequests = count))
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.rate_limit.submit_requests"),
                current.media.rateLimit.submitRequests,
            )
                .setDefaultValue(defaultMedia.rateLimit.submitRequests)
                .setTooltip(McText.translatable("config.moemusic.media.rate_limit.submit_requests.tooltip"))
                .setSaveConsumer { count ->
                    newMedia = newMedia.copy(rateLimit = newMedia.rateLimit.copy(submitRequests = count))
                }
                .build()
        )

        mediaCategory.addEntry(
            entryBuilder.startTextDescription(
                McText.translatable(
                    "config.moemusic.media.misc.description"
                )
            ).build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntField(
                McText.translatable("config.moemusic.media.max_player_track_duration_seconds"),
                current.media.maxPlayerTrackDurationSeconds,
            )
                .setDefaultValue(defaultMedia.maxPlayerTrackDurationSeconds)
                .setTooltip(McText.translatable("config.moemusic.media.max_player_track_duration_seconds.tooltip"))
                .setSaveConsumer { newMedia = newMedia.copy(maxPlayerTrackDurationSeconds = it) }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startIntSlider(
                McText.translatable("config.moemusic.media.max_search_results_per_page"),
                current.media.maxSearchResultsPerPage,
                1,
                200,
            )
                .setDefaultValue(defaultMedia.maxSearchResultsPerPage)
                .setTooltip(McText.translatable("config.moemusic.media.max_search_results_per_page.tooltip"))
                .setSaveConsumer { newMedia = newMedia.copy(maxSearchResultsPerPage = it) }
                .build()
        )

        mediaCategory.addEntry(
            entryBuilder.startTextDescription(
                McText.translatable(
                    "config.moemusic.media.media_firewall.description"
                )
            ).build()
        )
        mediaCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.media.media_firewall.enabled"),
                current.media.firewall.enabled,
            )
                .setDefaultValue(defaultMedia.firewall.enabled)
                .setTooltip(McText.translatable("config.moemusic.media.media_firewall.enabled.tooltip"))
                .setSaveConsumer { enabled ->
                    newMedia = newMedia.copy(firewall = newMedia.firewall.copy(enabled = enabled))
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.media.media_firewall.host_list_mode"),
                MediaHostListMode.entries.toTypedArray(),
                current.media.firewall.hostListMode,
            )
                .setDefaultValue(defaultMedia.firewall.hostListMode)
                .setNameProvider { value ->
                    McText.translatable("config.moemusic.media.media_firewall.host_list_mode.${value.name.lowercase()}")
                }
                .setTooltip(McText.translatable("config.moemusic.media.media_firewall.host_list_mode.tooltip"))
                .setSaveConsumer { hostListMode ->
                    newMedia = newMedia.copy(firewall = newMedia.firewall.copy(hostListMode = hostListMode))
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startStrList(
                McText.translatable("config.moemusic.media.media_firewall.hosts"),
                current.media.firewall.hosts,
            )
                .setDefaultValue(defaultMedia.firewall.hosts)
                .setTooltip(McText.translatable("config.moemusic.media.media_firewall.hosts.tooltip"))
                .setSaveConsumer { hosts ->
                    newMedia = newMedia.copy(firewall = newMedia.firewall.copy(hosts = hosts))
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.media.media_firewall.block_private_ips"),
                current.media.firewall.blockPrivateIps,
            )
                .setDefaultValue(defaultMedia.firewall.blockPrivateIps)
                .setTooltip(McText.translatable("config.moemusic.media.media_firewall.block_private_ips.tooltip"))
                .setSaveConsumer { blockPrivateIps ->
                    newMedia = newMedia.copy(firewall = newMedia.firewall.copy(blockPrivateIps = blockPrivateIps))
                }
                .build()
        )
        mediaCategory.addEntry(
            entryBuilder.startBooleanToggle(
                McText.translatable("config.moemusic.media.media_firewall.allow_local_files"),
                current.media.allowLocalFiles,
            )
                .setDefaultValue(defaultMedia.allowLocalFiles)
                .setTooltip(McText.translatable("config.moemusic.media.media_firewall.allow_local_files.tooltip"))
                .setSaveConsumer { newMedia = newMedia.copy(allowLocalFiles = it) }
                .build()
        )

        permissionsCategory.addEntry(
            entryBuilder.startTextDescription(
                McText.translatable("config.moemusic.permissions.description")
            ).build()
        )

        var newVoteRequiredPercent = current.voteRequiredPercent
        permissionsCategory.addEntry(
            entryBuilder.startIntSlider(
                McText.translatable("config.moemusic.vote_required_percent"),
                current.voteRequiredPercent.coerceIn(1, 100),
                1,
                100,
            )
                .setDefaultValue(defaultConfig.voteRequiredPercent)
                .setTooltip(McText.translatable("config.moemusic.vote_required_percent.tooltip"))
                .setSaveConsumer { newVoteRequiredPercent = it }
                .build()
        )

        var newSubmitLevel = current.permissions.submit
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.submit",
            initialValue = current.permissions.submit,
            defaultValue = defaultPermissions.submit,
        ) { newSubmitLevel = it }

        var newSourceHttpSubmitLevel = current.permissions.sourceHttpSubmit
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.source_http_submit",
            initialValue = current.permissions.sourceHttpSubmit,
            defaultValue = defaultPermissions.sourceHttpSubmit,
        ) { newSourceHttpSubmitLevel = it }

        var newSubmitSkipAutoplayLevel = current.permissions.submitSkipAutoplay
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.submit_skip_autoplay",
            initialValue = current.permissions.submitSkipAutoplay,
            defaultValue = defaultPermissions.submitSkipAutoplay,
        ) { newSubmitSkipAutoplayLevel = it }

        var newQueueControlLevel = current.permissions.queueControl
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.queue_control",
            initialValue = current.permissions.queueControl,
            defaultValue = defaultPermissions.queueControl,
        ) { newQueueControlLevel = it }

        var newSkipVoteLevel = current.permissions.vote
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.vote",
            initialValue = current.permissions.vote,
            defaultValue = defaultPermissions.vote,
        ) { newSkipVoteLevel = it }

        var newPlaybackControlLevel = current.permissions.playbackControl
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.playback_control",
            initialValue = current.permissions.playbackControl,
            defaultValue = defaultPermissions.playbackControl,
        ) { newPlaybackControlLevel = it }

        var newQueueViewLevel = current.permissions.queueView
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.queue_view",
            initialValue = current.permissions.queueView,
            defaultValue = defaultPermissions.queueView,
        ) { newQueueViewLevel = it }

        var newSearchLevel = current.permissions.search
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.search",
            initialValue = current.permissions.search,
            defaultValue = defaultPermissions.search,
        ) { newSearchLevel = it }

        var newContentFilterManageLevel = current.permissions.contentFilterManage
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.content_filter_manage",
            initialValue = current.permissions.contentFilterManage,
            defaultValue = defaultPermissions.contentFilterManage,
        ) { newContentFilterManageLevel = it }

        var newConfigReloadLevel = current.permissions.configReload
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.config_reload",
            initialValue = current.permissions.configReload,
            defaultValue = defaultPermissions.configReload,
        ) { newConfigReloadLevel = it }

        var newSystemInfoLevel = current.permissions.systemInfo
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.system_info",
            initialValue = current.permissions.systemInfo,
            defaultValue = defaultPermissions.systemInfo,
        ) { newSystemInfoLevel = it }

        var newAutoplayRefreshLevel = current.permissions.autoplayRefresh
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.autoplay_refresh",
            initialValue = current.permissions.autoplayRefresh,
            defaultValue = defaultPermissions.autoplayRefresh,
        ) { newAutoplayRefreshLevel = it }

        var newContentFilterBypassLevel = current.permissions.contentFilterBypass
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.content_filter_bypass",
            initialValue = current.permissions.contentFilterBypass,
            defaultValue = defaultPermissions.contentFilterBypass,
        ) { newContentFilterBypassLevel = it }

        var newDurationPolicyBypassLevel = current.permissions.durationPolicyBypass
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.duration_policy_bypass",
            initialValue = current.permissions.durationPolicyBypass,
            defaultValue = defaultPermissions.durationPolicyBypass,
        ) { newDurationPolicyBypassLevel = it }

        var newRateLimitBypassLevel = current.permissions.rateLimitBypass
        addPermissionLevelEntry(
            category = permissionsCategory,
            entryBuilder = entryBuilder,
            labelKey = "config.moemusic.permissions.rate_limit_bypass",
            initialValue = current.permissions.rateLimitBypass,
            defaultValue = defaultPermissions.rateLimitBypass,
        ) { newRateLimitBypassLevel = it }

        var newHud = current.client.nowPlayingHud
        addHudEntries(hudCategory, entryBuilder, current.client.nowPlayingHud, defaultHud) { newHud = it }
        addSystemEntries(systemCategory, entryBuilder)

        val pluginSaveActions = mutableListOf<() -> Unit>()

        for (plugin in PluginManager.plugins) {
            val pluginCategory: ConfigCategory = builder.getOrCreateCategory(toComponent(render(plugin.displayName)))
            val configSpec = plugin.configSpec
            val configFile = PluginManager.pluginConfigFile(plugin)

            if (configSpec != null && configFile != null) {
                addPluginEntries(plugin, pluginCategory, entryBuilder, configFile, pluginSaveActions)
            } else {
                pluginCategory.addEntry(
                    entryBuilder.startTextDescription(
                        McText.translatable("config.moemusic.plugin.no_settings")
                    ).build()
                )
            }
        }

        builder.setSavingRunnable {
            val saveResult = saveCoreConfig(
                initial = current,
                edits = ConfigEdits(
                    defaultSourceId = newDefaultSourceId,
                    defaultLanguage = newDefaultLanguage,
                    autoplay = AutoplayConfig(
                        enabled = newEnabled,
                        maxTracksPerSource = newMaxTracks,
                    ),
                    voteRequiredPercent = newVoteRequiredPercent,
                    permissions = PermissionDefaultsConfig(
                        submit = newSubmitLevel,
                        sourceHttpSubmit = newSourceHttpSubmitLevel,
                        submitSkipAutoplay = newSubmitSkipAutoplayLevel,
                        queueControl = newQueueControlLevel,
                        vote = newSkipVoteLevel,
                        playbackControl = newPlaybackControlLevel,
                        queueView = newQueueViewLevel,
                        search = newSearchLevel,
                        contentFilterManage = newContentFilterManageLevel,
                        configReload = newConfigReloadLevel,
                        systemInfo = newSystemInfoLevel,
                        autoplayRefresh = newAutoplayRefreshLevel,
                        contentFilterBypass = newContentFilterBypassLevel,
                        durationPolicyBypass = newDurationPolicyBypassLevel,
                        rateLimitBypass = newRateLimitBypassLevel,
                    ),
                    contentFilter = newContentFilter.copy(
                        exactTrackRules = exactTrackRules.toTrackRules(),
                        exactArtistRules = exactArtistRules.toArtistRules(),
                        textRules = textRules.toTextRules(),
                    ),
                    media = newMedia,
                    playbackEnabled = newPlaybackEnabled,
                    blockVanillaMusic = newBlockVanillaMusic,
                    blockRecords = newBlockRecords,
                    globalInstancePlaybackLock = newGlobalInstancePlaybackLock,
                    disabledServers = newDisabledServers,
                    clientContentFilter = newClientContentFilter,
                    clientCoverArt = newClientCoverArt,
                    hud = newHud,
                )
            )
            pluginSaveActions.forEach { it() }
            if (saveResult.hadConflicts) {
                showConfigSaveConflictToast()
            }
        }

        return builder.build()
    }

    private fun addSystemEntries(category: ConfigCategory, entryBuilder: ConfigEntryBuilder) {
        val runtimeInfo = MoePlatform.runtimeInfo
        val rows = listOf(
            "config.moemusic.system.mod_version" to runtimeInfo.modVersion,
            "config.moemusic.system.loader" to runtimeInfo.loaderId,
            "config.moemusic.system.api_version" to MoeMusicApi.API_VERSION,
            "config.moemusic.system.core_version" to runtimeInfo.coreVersion,
            "config.moemusic.system.client_core_version" to runtimeInfo.clientCoreVersion,
            "config.moemusic.system.platform_common_version" to runtimeInfo.platformCommonVersion,
            "config.moemusic.system.protocol_version" to MoeMusicProtocol.VERSION.toString(),
            "config.moemusic.system.plugin_count" to PluginManager.plugins.size.toString(),
            "config.moemusic.system.source_count" to PluginManager.musicSourceSnapshot().size.toString(),
        )

        rows.forEach { (key, value) ->
            category.addEntry(
                entryBuilder.startTextDescription(
                    McText.translatable(key, value)
                ).build()
            )
        }
    }

    private fun addTrackRuleEntries(
        category: ConfigCategory,
        entryBuilder: ConfigEntryBuilder,
        initialRows: List<EditableTrackRule>,
        defaultRows: List<EditableTrackRule>,
        saveConsumer: (List<EditableTrackRule>) -> Unit,
    ) {
        category.addEntry(
            NestedListListEntry(
                McText.translatable("config.moemusic.content_filter.exact_track_rules"),
                initialRows,
                true,
                { Optional.of(arrayOf(McText.translatable("config.moemusic.content_filter.exact_track_rules.tooltip"))) },
                saveConsumer,
                { defaultRows.map(EditableTrackRule::copy) },
                entryBuilder.resetButtonKey,
                true,
                false,
            ) { row, _ ->
                buildTrackRuleListEntry(entryBuilder, row ?: EditableTrackRule())
            }
        )
    }

    private fun addArtistRuleEntries(
        category: ConfigCategory,
        entryBuilder: ConfigEntryBuilder,
        initialRows: List<EditableArtistRule>,
        defaultRows: List<EditableArtistRule>,
        saveConsumer: (List<EditableArtistRule>) -> Unit,
    ) {
        category.addEntry(
            NestedListListEntry(
                McText.translatable("config.moemusic.content_filter.exact_artist_rules"),
                initialRows,
                true,
                { Optional.of(arrayOf(McText.translatable("config.moemusic.content_filter.exact_artist_rules.tooltip"))) },
                saveConsumer,
                { defaultRows.map(EditableArtistRule::copy) },
                entryBuilder.resetButtonKey,
                true,
                false,
            ) { row, _ ->
                buildArtistRuleListEntry(entryBuilder, row ?: EditableArtistRule())
            }
        )
    }

    private fun addTextRuleEntries(
        category: ConfigCategory,
        entryBuilder: ConfigEntryBuilder,
        initialRows: List<EditableTextRule>,
        defaultRows: List<EditableTextRule>,
        saveConsumer: (List<EditableTextRule>) -> Unit,
    ) {
        category.addEntry(
            NestedListListEntry(
                McText.translatable("config.moemusic.content_filter.text_rules"),
                initialRows,
                true,
                { Optional.of(arrayOf(McText.translatable("config.moemusic.content_filter.text_rules.tooltip"))) },
                saveConsumer,
                { defaultRows.map(EditableTextRule::copy) },
                entryBuilder.resetButtonKey,
                true,
                false,
            ) { row, _ ->
                buildTextRuleListEntry(entryBuilder, row ?: EditableTextRule())
            }
        )
    }

    private fun buildTrackRuleListEntry(
        entryBuilder: ConfigEntryBuilder,
        row: EditableTrackRule,
    ): MultiElementListEntry<EditableTrackRule> {
        val initial = row.copy()
        val entries = mutableListOf<AbstractConfigListEntry<*>>()
        entries += entryBuilder.startStrField(McText.translatable("config.moemusic.content_filter.rule.source_id"), row.sourceId)
            .setDefaultValue(initial.sourceId)
            .setSaveConsumer { row.sourceId = it }
            .setErrorSupplier { validateRequiredValue(it, "config.moemusic.content_filter.rule.source_id_required") }
            .build()
        entries += entryBuilder.startStrField(McText.translatable("config.moemusic.content_filter.rule.track_id"), row.trackId)
            .setDefaultValue(initial.trackId)
            .setSaveConsumer { row.trackId = it }
            .setErrorSupplier { validateRequiredValue(it, "config.moemusic.content_filter.rule.track_id_required") }
            .build()
        entries += entryBuilder.startStrField(McText.translatable("config.moemusic.content_filter.rule.note"), row.note)
            .setDefaultValue(initial.note)
            .setSaveConsumer { row.note = it }
            .build()
        return MultiElementListEntry(
            McText.translatable("config.moemusic.content_filter.track_rule.label"),
            row,
            entries,
            true,
        )
    }

    private fun buildArtistRuleListEntry(
        entryBuilder: ConfigEntryBuilder,
        row: EditableArtistRule,
    ): MultiElementListEntry<EditableArtistRule> {
        val initial = row.copy()
        val entries = mutableListOf<AbstractConfigListEntry<*>>()
        entries += entryBuilder.startStrField(McText.translatable("config.moemusic.content_filter.rule.source_id"), row.sourceId)
            .setDefaultValue(initial.sourceId)
            .setSaveConsumer { row.sourceId = it }
            .setErrorSupplier { validateRequiredValue(it, "config.moemusic.content_filter.rule.source_id_required") }
            .build()
        entries += entryBuilder.startStrField(McText.translatable("config.moemusic.content_filter.rule.artist_id"), row.artistId)
            .setDefaultValue(initial.artistId)
            .setSaveConsumer { row.artistId = it }
            .setErrorSupplier { validateRequiredValue(it, "config.moemusic.content_filter.rule.artist_id_required") }
            .build()
        entries += entryBuilder.startStrField(McText.translatable("config.moemusic.content_filter.rule.note"), row.note)
            .setDefaultValue(initial.note)
            .setSaveConsumer { row.note = it }
            .build()
        return MultiElementListEntry(
            McText.translatable("config.moemusic.content_filter.artist_rule.label"),
            row,
            entries,
            true,
        )
    }

    private fun buildTextRuleListEntry(
        entryBuilder: ConfigEntryBuilder,
        row: EditableTextRule,
    ): MultiElementListEntry<EditableTextRule> {
        val initial = row.copy()
        val entries = mutableListOf<AbstractConfigListEntry<*>>()
        entries += entryBuilder.startEnumSelector(
            McText.translatable("config.moemusic.content_filter.rule.scope"),
            ContentFilterTextRuleScope::class.java,
            row.scope,
        )
            .setDefaultValue(initial.scope)
            .setEnumNameProvider { value ->
                McText.translatable("config.moemusic.content_filter.scope.${value.name.lowercase()}")
            }
            .setSaveConsumer { row.scope = it }
            .build()
        entries += entryBuilder.startEnumSelector(
            McText.translatable("config.moemusic.content_filter.rule.mode"),
            ContentFilterTextRuleMode::class.java,
            row.mode,
        )
            .setDefaultValue(initial.mode)
            .setEnumNameProvider { value ->
                McText.translatable("config.moemusic.content_filter.mode.${value.name.lowercase()}")
            }
            .setSaveConsumer { row.mode = it }
            .build()
        entries += entryBuilder.startBooleanToggle(
            McText.translatable("config.moemusic.content_filter.rule.ignore_case"),
            row.ignoreCase,
        )
            .setDefaultValue(initial.ignoreCase)
            .setSaveConsumer { row.ignoreCase = it }
            .build()
        entries += entryBuilder.startStrField(McText.translatable("config.moemusic.content_filter.rule.pattern"), row.pattern)
            .setDefaultValue(initial.pattern)
            .setSaveConsumer { row.pattern = it }
            .setErrorSupplier { validateTextRulePattern(it, row.mode) }
            .build()
        return MultiElementListEntry(
            McText.translatable("config.moemusic.content_filter.text_rule.label"),
            row,
            entries,
            true,
        )
    }

    private fun toEditableTrackRule(rule: ExactTrackFilterRule): EditableTrackRule =
        EditableTrackRule(
            sourceId = rule.sourceId,
            trackId = rule.trackId,
            note = rule.note.orEmpty(),
        )

    private fun toEditableArtistRule(rule: ExactArtistFilterRule): EditableArtistRule =
        EditableArtistRule(
            sourceId = rule.sourceId,
            artistId = rule.artistId,
            note = rule.note.orEmpty(),
        )

    private fun toEditableTextRule(rule: ContentFilterTextRule): EditableTextRule =
        EditableTextRule(
            scope = rule.scope,
            mode = rule.mode,
            ignoreCase = rule.ignoreCase,
            pattern = rule.pattern,
        )

    private fun List<EditableTrackRule>.toTrackRules(): List<ExactTrackFilterRule> =
        map { row ->
            ExactTrackFilterRule(
                sourceId = row.sourceId.trim(),
                trackId = row.trackId.trim(),
                note = row.note.trim().takeIf(String::isNotEmpty),
            )
        }

    private fun List<EditableArtistRule>.toArtistRules(): List<ExactArtistFilterRule> =
        map { row ->
            ExactArtistFilterRule(
                sourceId = row.sourceId.trim(),
                artistId = row.artistId.trim(),
                note = row.note.trim().takeIf(String::isNotEmpty),
            )
        }

    private fun List<EditableTextRule>.toTextRules(): List<ContentFilterTextRule> =
        map { row ->
            ContentFilterTextRule(
                pattern = row.pattern.trim(),
                mode = row.mode,
                scope = row.scope,
                ignoreCase = row.ignoreCase,
            )
        }

    private fun validateRequiredValue(raw: String, key: String): Optional<Component> =
        if (raw.trim().isBlank()) Optional.of(McText.translatable(key)) else Optional.empty()

    private fun validateTextRulePattern(raw: String, mode: ContentFilterTextRuleMode): Optional<Component> {
        val pattern = raw.trim()
        if (pattern.isBlank()) {
            return Optional.of(McText.translatable("config.moemusic.content_filter.rule.pattern_required"))
        }
        if (mode != ContentFilterTextRuleMode.REGEX) return Optional.empty()
        return try {
            Regex(pattern)
            Optional.empty()
        } catch (_: Exception) {
            Optional.of(McText.translatable("config.moemusic.content_filter.text_rule.regex_invalid", pattern))
        }
    }

    private fun addHudEntries(
        category: ConfigCategory,
        entryBuilder: ConfigEntryBuilder,
        initial: NowPlayingHudConfig,
        defaults: NowPlayingHudConfig,
        saveConsumer: (NowPlayingHudConfig) -> Unit,
    ) {
        var current = initial

        fun update(transform: (NowPlayingHudConfig) -> NowPlayingHudConfig) {
            current = transform(current)
            saveConsumer(current)
        }

        fun addArgbField(
            key: String,
            value: String,
            defaultValue: String,
            transform: (NowPlayingHudConfig, String) -> NowPlayingHudConfig,
        ) {
            category.addEntry(
                entryBuilder.startStrField(McText.translatable(key), value)
                    .setDefaultValue(defaultValue)
                    .setTooltip(McText.translatable("$key.tooltip"))
                    .setErrorSupplier { input ->
                        if (input.trim().removePrefix("#").uppercase().matches(Regex("[0-9A-F]{8}"))) Optional.empty()
                        else Optional.of(McText.translatable("config.moemusic.now_playing_hud.argb.invalid"))
                    }
                    .setSaveConsumer { update { cfg -> transform(cfg, it) } }
                    .build()
            )
        }

        category.addEntry(
            entryBuilder.startBooleanToggle(McText.translatable("config.moemusic.now_playing_hud.enabled"), initial.enabled)
                .setDefaultValue(defaults.enabled)
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.enabled.tooltip"))
                .setSaveConsumer { value -> update { cfg -> cfg.copy(enabled = value) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.now_playing_hud.anchor"),
                HudAnchor.entries.toTypedArray(),
                initial.anchor,
            )
                .setDefaultValue(defaults.anchor)
                .setNameProvider { value -> McText.translatable("config.moemusic.now_playing_hud.anchor.${value.name.lowercase()}") }
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.anchor.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(anchor = it) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startIntField(McText.translatable("config.moemusic.now_playing_hud.offset_x"), initial.offsetX)
                .setDefaultValue(defaults.offsetX)
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.offset_x.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(offsetX = it) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startIntField(McText.translatable("config.moemusic.now_playing_hud.offset_y"), initial.offsetY)
                .setDefaultValue(defaults.offsetY)
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.offset_y.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(offsetY = it) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startIntSlider(McText.translatable("config.moemusic.now_playing_hud.vertical_size"), initial.verticalSize, 16, 256)
                .setDefaultValue(defaults.verticalSize)
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.vertical_size.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(verticalSize = it) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startIntSlider(McText.translatable("config.moemusic.now_playing_hud.text_max_width"), initial.textMaxWidth, 40, 1024)
                .setDefaultValue(defaults.textMaxWidth)
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.text_max_width.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(textMaxWidth = it) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.now_playing_hud.cover_side"),
                HudCoverSide.entries.toTypedArray(),
                initial.coverSide,
            )
                .setDefaultValue(defaults.coverSide)
                .setNameProvider { value -> McText.translatable("config.moemusic.now_playing_hud.cover_side.${value.name.lowercase()}") }
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.cover_side.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(coverSide = it) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.now_playing_hud.text_alignment"),
                HudTextAlignment.entries.toTypedArray(),
                initial.textAlignment,
            )
                .setDefaultValue(defaults.textAlignment)
                .setNameProvider { value -> McText.translatable("config.moemusic.now_playing_hud.text_alignment.${value.name.lowercase()}") }
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.text_alignment.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(textAlignment = it) } }
                .build()
        )
        category.addEntry(
            entryBuilder.startSelector(
                McText.translatable("config.moemusic.now_playing_hud.progress_bar_position"),
                HudProgressBarPosition.entries.toTypedArray(),
                initial.progressBarPosition,
            )
                .setDefaultValue(defaults.progressBarPosition)
                .setNameProvider { value -> McText.translatable("config.moemusic.now_playing_hud.progress_bar_position.${value.name.lowercase()}") }
                .setTooltip(McText.translatable("config.moemusic.now_playing_hud.progress_bar_position.tooltip"))
                .setSaveConsumer { update { cfg -> cfg.copy(progressBarPosition = it) } }
                .build()
        )

        fun addToggle(
            key: String,
            value: Boolean,
            defaultValue: Boolean,
            transform: (NowPlayingHudConfig, Boolean) -> NowPlayingHudConfig,
        ) {
            category.addEntry(
                entryBuilder.startBooleanToggle(McText.translatable(key), value)
                    .setDefaultValue(defaultValue)
                    .setTooltip(McText.translatable("$key.tooltip"))
                    .setSaveConsumer { newValue -> update { cfg -> transform(cfg, newValue) } }
                    .build()
            )
        }

        addToggle("config.moemusic.now_playing_hud.show_background", initial.showBackground, defaults.showBackground) { cfg, value -> cfg.copy(showBackground = value) }
        addToggle("config.moemusic.now_playing_hud.show_cover", initial.showCover, defaults.showCover) { cfg, value -> cfg.copy(showCover = value) }
        addToggle("config.moemusic.now_playing_hud.show_title", initial.showTitle, defaults.showTitle) { cfg, value -> cfg.copy(showTitle = value) }
        addToggle("config.moemusic.now_playing_hud.show_artist", initial.showArtist, defaults.showArtist) { cfg, value -> cfg.copy(showArtist = value) }
        addToggle("config.moemusic.now_playing_hud.show_album", initial.showAlbum, defaults.showAlbum) { cfg, value -> cfg.copy(showAlbum = value) }
        addToggle("config.moemusic.now_playing_hud.show_time", initial.showTime, defaults.showTime) { cfg, value -> cfg.copy(showTime = value) }
        addToggle("config.moemusic.now_playing_hud.show_progress_bar", initial.showProgressBar, defaults.showProgressBar) { cfg, value -> cfg.copy(showProgressBar = value) }
        addToggle("config.moemusic.now_playing_hud.show_lyrics", initial.showLyrics, defaults.showLyrics) { cfg, value -> cfg.copy(showLyrics = value) }
        addToggle("config.moemusic.now_playing_hud.circular_cover", initial.circularCover, defaults.circularCover) { cfg, value -> cfg.copy(circularCover = value) }
        addToggle("config.moemusic.now_playing_hud.show_center_dot", initial.showCenterDot, defaults.showCenterDot) { cfg, value -> cfg.copy(showCenterDot = value) }
        addToggle("config.moemusic.now_playing_hud.spin_cover", initial.spinCover, defaults.spinCover) { cfg, value -> cfg.copy(spinCover = value) }

        addArgbField("config.moemusic.now_playing_hud.text_color_argb", initial.textColorArgb, defaults.textColorArgb) { cfg, value -> cfg.copy(textColorArgb = value) }
        addArgbField("config.moemusic.now_playing_hud.secondary_text_color_argb", initial.secondaryTextColorArgb, defaults.secondaryTextColorArgb) { cfg, value -> cfg.copy(secondaryTextColorArgb = value) }
        addArgbField("config.moemusic.now_playing_hud.background_color_argb", initial.backgroundColorArgb, defaults.backgroundColorArgb) { cfg, value -> cfg.copy(backgroundColorArgb = value) }
        addArgbField("config.moemusic.now_playing_hud.progress_bar_color_argb", initial.progressBarColorArgb, defaults.progressBarColorArgb) { cfg, value -> cfg.copy(progressBarColorArgb = value) }
        addArgbField("config.moemusic.now_playing_hud.paused_progress_bar_color_argb", initial.pausedProgressBarColorArgb, defaults.pausedProgressBarColorArgb) { cfg, value -> cfg.copy(pausedProgressBarColorArgb = value) }
        addArgbField("config.moemusic.now_playing_hud.progress_bar_background_color_argb", initial.progressBarBackgroundColorArgb, defaults.progressBarBackgroundColorArgb) { cfg, value -> cfg.copy(progressBarBackgroundColorArgb = value) }
        addArgbField("config.moemusic.now_playing_hud.record_ring_color_argb", initial.recordRingColorArgb, defaults.recordRingColorArgb) { cfg, value -> cfg.copy(recordRingColorArgb = value) }
    }

    private fun addPermissionLevelEntry(
        category: ConfigCategory,
        entryBuilder: ConfigEntryBuilder,
        labelKey: String,
        initialValue: Int,
        defaultValue: Int,
        saveConsumer: (Int) -> Unit,
    ) {
        category.addEntry(
            entryBuilder.startIntSlider(
                McText.translatable(labelKey),
                initialValue.coerceIn(PermissionNodes.MIN_DEFAULT_LEVEL, PermissionNodes.MAX_DEFAULT_LEVEL),
                PermissionNodes.MIN_DEFAULT_LEVEL,
                PermissionNodes.MAX_DEFAULT_LEVEL,
            )
                .setDefaultValue(defaultValue)
                .setTooltip(McText.translatable("config.moemusic.permissions.level.tooltip"))
                .setSaveConsumer(saveConsumer)
                .build()
        )
    }

    private fun knownSourceDisplayNames(): Map<String, String> = buildMap {
        PluginManager.musicSourceSnapshot().forEach { source ->
            val sourceId = source.id.trim()
            if (sourceId.isEmpty() || containsKey(sourceId)) return@forEach
            put(sourceId, ClientLocalization.render(source.displayName).trim())
        }
    }

    private fun defaultSourceSelectorValues(
        currentValue: String,
        registeredSourceIds: Collection<String>,
    ): Array<String> = buildList {
        add("")
        addAll(registeredSourceIds)
        currentValue.trim().takeIf(String::isNotEmpty)?.let { configuredValue ->
            if (configuredValue !in registeredSourceIds) add(configuredValue)
        }
    }.toTypedArray()

    private fun defaultSourceSelectorLabel(
        sourceId: String,
        knownSourceDisplayNames: Map<String, String>,
    ): Component {
        val normalizedSourceId = sourceId.trim()
        if (normalizedSourceId.isEmpty()) {
            return McText.translatable("config.moemusic.default_source_id.automatic")
        }
        val displayName = knownSourceDisplayNames[normalizedSourceId].orEmpty()
        return when {
            displayName.isBlank() || displayName == normalizedSourceId -> McText.literal(normalizedSourceId)
            else -> McText.literal("$displayName ($normalizedSourceId)")
        }
    }

    private fun defaultLanguageSelectorValues(
        currentValue: String,
        availableLocales: Collection<String>,
    ): Array<String> = (availableLocales.asSequence()
        .map(::normalizeLanguageId)
        .filter(String::isNotBlank)
        .toList() + DEFAULT_SERVER_LANGUAGE + normalizeLanguageId(currentValue))
        .distinct()
        .sorted()
        .toTypedArray()

    @Suppress("UNCHECKED_CAST")
    private fun addPluginEntries(
        plugin: Plugin,
        category: ConfigCategory,
        entryBuilder: ConfigEntryBuilder,
        configFile: Path,
        saveActions: MutableList<() -> Unit>,
    ) {
        val spec = plugin.configSpec ?: run {
            category.addEntry(
                entryBuilder.startTextDescription(
                    McText.translatable("config.moemusic.plugin.no_settings")
                ).build()
            )
            return
        }

        val initialConfig = PluginConfigIO.loadAny(configFile, spec)
        val defaultConfig = spec.createDefault()
        // Cloth save consumers run only during saveAll; validators need the live entry snapshot.
        val uiEntries = mutableListOf<PluginConfigUiEntry>()
        var hasEntries = false

        for (entry in spec.entries) {
            hasEntries = true
            when (entry) {
                is BooleanPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startBooleanToggle(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as Boolean,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Boolean)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is IntPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startIntField(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as Int,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Int)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is IntSliderPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startIntSlider(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as Int,
                        entry.min,
                        entry.max,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Int)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is LongPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startLongField(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as Long,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Long)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is LongSliderPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startLongSlider(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as Long,
                        entry.min,
                        entry.max,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Long)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is FloatPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startFloatField(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as Float,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Float)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is DoublePluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startDoubleField(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as Double,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Double)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is StringPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startStrField(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as String,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as String)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is StringListPluginConfigEntry<*> -> {
                    val builderForEntry = entryBuilder.startStrList(
                        entryLabel(plugin, entry.key),
                        entry.read(initialConfig) as List<String>,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as List<String>)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is EnumPluginConfigEntry<*, *> -> {
                    val enumValues = entry.enumClass.enumConstants.map { it as Enum<*> }.toTypedArray()
                    val builderForEntry = entryBuilder.startSelector(
                        entryLabel(plugin, entry.key),
                        enumValues,
                        entry.read(initialConfig) as Enum<*>,
                    )
                        .setDefaultValue(entry.read(defaultConfig) as Enum<*>)
                        .setNameProvider { enumValue -> enumValueLabel(plugin, entry.key, enumValue as Enum<*>) }
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                is EnumDropdownPluginConfigEntry<*, *> -> {
                    val enumValues = entry.enumClass.enumConstants.map { it as Enum<*> }.toTypedArray()
                    val currentValue = entry.read(initialConfig) as Enum<*>
                    val builderForEntry = entryBuilder.startDropdownMenu(
                        entryLabel(plugin, entry.key),
                        DropdownMenuBuilder.TopCellElementBuilder.of(
                            currentValue,
                            { rawValue -> enumDropdownValue(plugin, entry.key, rawValue, enumValues, currentValue) },
                            { enumValue -> enumValueLabel(plugin, entry.key, enumValue) },
                        ),
                        DropdownMenuBuilder.CellCreatorBuilder.of { enumValue ->
                            enumValueLabel(plugin, entry.key, enumValue)
                        },
                    )
                        .setSelections(enumValues.toSet())
                        .setDefaultValue(entry.read(defaultConfig) as Enum<*>)
                        .setErrorSupplier { validationMessage(entry, initialConfig, uiEntries, it) }
                    entryTooltip(plugin, entry.key)?.let { builderForEntry.setTooltip(it) }
                    val uiEntry = builderForEntry.build()
                    uiEntries += PluginConfigUiEntry(entry, uiEntry)
                    category.addEntry(uiEntry)
                }

                else -> {
                    category.addEntry(
                        entryBuilder.startTextDescription(
                            McText.literal("Unsupported setting: ${humanizeKey(entry.key)}")
                        ).build()
                    )
                }
            }
        }

        if (!hasEntries) {
            category.addEntry(
                entryBuilder.startTextDescription(
                    McText.translatable("config.moemusic.plugin.no_settings")
                ).build()
            )
            return
        }

        saveActions.add {
            val currentConfig = pluginConfigFromUi(initialConfig, uiEntries)
            PluginConfigIO.saveAny(configFile, currentConfig, spec)
            PluginManager.notifyConfigChanged(plugin.id, currentConfig)
        }
    }

    private fun saveCoreConfig(initial: MoeMusicConfig, edits: ConfigEdits): MergeResult {
        var mergeResult = MergeResult(initial, hadConflicts = false)
        ModConfigManager.update { latest ->
            mergeResult = mergeConfigEdits(initial, latest, edits)
            mergeResult.config
        }
        if (MoePlatform.serverRuntimeInitialized) {
            MoePlatform.applyReloadableServerConfig()
        } else {
            ContentFilterRuleEditor.applyCurrentConfig()
        }
        ClientPlaybackHandler.syncParticipationWithCurrentConfig()
        VanillaSoundBlocker.stopBlockedSoundsIfNeeded()
        return mergeResult
    }

    private fun mergeConfigEdits(initial: MoeMusicConfig, latest: MoeMusicConfig, edits: ConfigEdits): MergeResult {
        var merged = latest
        var hadConflicts = false

        hadConflicts = applyEditedValue(
            initial = initial.defaultSourceId,
            latest = latest.defaultSourceId,
            edited = edits.defaultSourceId,
        ) { merged = merged.copy(defaultSourceId = it) } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.defaultLanguage,
            latest = latest.defaultLanguage,
            edited = edits.defaultLanguage,
        ) { merged = merged.copy(defaultLanguage = it) } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.autoplay,
            latest = latest.autoplay,
            edited = edits.autoplay,
        ) { merged = merged.copy(autoplay = it) } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.voteRequiredPercent,
            latest = latest.voteRequiredPercent,
            edited = edits.voteRequiredPercent,
        ) { merged = merged.copy(voteRequiredPercent = it) } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.permissions,
            latest = latest.permissions,
            edited = edits.permissions,
        ) { merged = merged.copy(permissions = it) } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.contentFilter,
            latest = latest.contentFilter,
            edited = edits.contentFilter,
        ) { merged = merged.copy(contentFilter = it) } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.media,
            latest = latest.media,
            edited = edits.media,
        ) { merged = merged.copy(media = it) } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.playbackEnabled,
            latest = latest.client.playbackEnabled,
            edited = edits.playbackEnabled,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(playbackEnabled = value))
        } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.blockVanillaMusic,
            latest = latest.client.blockVanillaMusic,
            edited = edits.blockVanillaMusic,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(blockVanillaMusic = value))
        } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.blockRecords,
            latest = latest.client.blockRecords,
            edited = edits.blockRecords,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(blockRecords = value))
        } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.globalInstancePlaybackLock,
            latest = latest.client.globalInstancePlaybackLock,
            edited = edits.globalInstancePlaybackLock,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(globalInstancePlaybackLock = value))
        } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.disabledServers,
            latest = latest.client.disabledServers,
            edited = edits.disabledServers,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(disabledServers = value))
        } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.contentFilter,
            latest = latest.client.contentFilter,
            edited = edits.clientContentFilter,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(contentFilter = value))
        } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.coverArt,
            latest = latest.client.coverArt,
            edited = edits.clientCoverArt,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(coverArt = value))
        } || hadConflicts

        hadConflicts = applyEditedValue(
            initial = initial.client.nowPlayingHud,
            latest = latest.client.nowPlayingHud,
            edited = edits.hud,
        ) { value ->
            merged = merged.copy(client = merged.client.copy(nowPlayingHud = value))
        } || hadConflicts

        return MergeResult(config = merged, hadConflicts = hadConflicts)
    }

    private fun <T> applyEditedValue(
        initial: T,
        latest: T,
        edited: T,
        apply: (T) -> Unit,
    ): Boolean {
        if (edited == initial || edited == latest) return false
        if (latest != initial) return true
        apply(edited)
        return false
    }

    private fun showConfigSaveConflictToast() {
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            showWrappedSystemToast(
                minecraft,
                ClientToastIds.configSaveConflict,
                McText.translatable("config.moemusic.save_conflict.title"),
                McText.translatable("config.moemusic.save_conflict.body"),
            )
        }
    }

    private fun entryLabel(plugin: Plugin, key: String): Component {
        val translationKey = "${translationPrefix(plugin)}.$key"
        return ClientLocalization.componentIfPresent(translationKey) ?: McText.literal(humanizeKey(key))
    }

    private fun entryTooltip(plugin: Plugin, key: String): Component? {
        val translationKey = "${translationPrefix(plugin)}.$key.tooltip"
        return ClientLocalization.componentIfPresent(translationKey)
    }

    private fun enumValueLabel(plugin: Plugin, entryKey: String, value: Enum<*>): Component {
        val translationKey = "${translationPrefix(plugin)}.$entryKey.${value.name.lowercase()}"
        return ClientLocalization.componentIfPresent(translationKey) ?: McText.literal(humanizeKey(value.name.lowercase()))
    }

    private fun enumDropdownValue(
        plugin: Plugin,
        entryKey: String,
        rawValue: String,
        enumValues: Array<Enum<*>>,
        fallback: Enum<*>,
    ): Enum<*> {
        val trimmed = rawValue.trim()
        if (trimmed.isEmpty()) return fallback
        return enumValues.firstOrNull { enumValue ->
            enumValue.name.equals(trimmed, ignoreCase = true) ||
                enumValueLabel(plugin, entryKey, enumValue).string.equals(trimmed, ignoreCase = true)
        } ?: fallback
    }

    private fun validationMessage(
        entry: PluginConfigEntry,
        initialConfig: Any,
        uiEntries: List<PluginConfigUiEntry>,
        value: Any,
    ): Optional<Component> {
        val configWithoutCurrentEntry = pluginConfigFromUi(
            initialConfig = initialConfig,
            uiEntries = uiEntries,
            skipEntry = entry,
        )
        val message = entry.validate(configWithoutCurrentEntry, value) ?: return Optional.empty()
        return Optional.of(toComponent(message))
    }

    private fun pluginConfigFromUi(
        initialConfig: Any,
        uiEntries: List<PluginConfigUiEntry>,
        skipEntry: PluginConfigEntry? = null,
    ): Any {
        var currentConfig = initialConfig
        for ((specEntry, uiEntry) in uiEntries) {
            if (specEntry === skipEntry) continue
            val value = requireNotNull(uiEntry.value) {
                "Plugin config entry '${specEntry.key}' produced a null UI value."
            }
            currentConfig = specEntry.write(currentConfig, value)
        }
        return currentConfig
    }

    private fun render(text: LocalizedText): LocalizedText =
        LocalizedText.plain(ClientLocalization.render(text))

    private fun toComponent(text: LocalizedText): Component =
        ClientLocalization.component(text)

    private fun translationPrefix(plugin: Plugin): String =
        "config." + plugin.id.replace(Regex("[^A-Za-z0-9_]+"), ".")

    private fun humanizeKey(key: String): String =
        key.split(Regex("[_\\-.]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }
}
