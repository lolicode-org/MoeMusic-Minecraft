package org.lolicode.moemusic.platform.command

import org.lolicode.moemusic.platform.text.McText

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.*
import net.minecraft.server.level.ServerPlayer
import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.*
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuleEditor
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.error.UserFacingErrors
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.source.builtin.HttpMusicSource
import org.lolicode.moemusic.platform.chat.LocalizedChatRenderer
import org.lolicode.moemusic.platform.command.MusicCommands.canManageContentFilter
import org.lolicode.moemusic.platform.command.MusicCommands.register
import org.lolicode.moemusic.platform.permission.PermissionResolver
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.lolicode.moemusic.platform.runtime.MoePlatform
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Registers all `/music` subcommands with the Brigadier dispatcher.
 *
 * Call [register] from `CommandRegistrationCallback.EVENT` in the `:fabric` initializer.
 *
 * Subcommands:
 * - `/music <linkOrId>` or `/music add <linkOrId>` — queues a song from a share link or ID
 * - `/music add --skip-autoplay <linkOrId>` — queues it and skips current playback only when it came from Autoplay
 * - `/music add --now <linkOrId>` — starts the song immediately
 * - `/music addById <source> <trackId>` — queues a track from a specific source
 * - `/music select <source> <choiceId> [--skip-autoplay|--now]` — plays a choice from search results
 * - `/music pause` — pauses current playback
 * - `/music resume` — resumes paused playback
 * - `/music skip` or `/music next` — skips immediately for privileged users, otherwise casts a vote to skip
 * - `/music stop` — stops all playback
 * - `/music queue` or `/music list` — lists the user queue
 * - `/music remove <index>` — removes a queued track using the human-facing queue numbering
 * - `/music remove <source> <trackId>` — removes a queued track by its stable track identity
 * - `/music search [--source <source>] [--page <page>] <query>` — searches a single source page and sends results as chat
 * - `/music system` — lists runtime versions plus registered plugins and source capabilities
 * - `/music reload all` — reloads the reloadable MoeMusic server config from disk
 * - `/music reload filter` — reloads only the shared content-filter rules from disk
 * - `/music reload autoplay` — refreshes Autoplay using the current in-memory config
 * - `/music filter track <ban|unban|toggle> <source> <trackId>` — mutates an exact track rule
 * - `/music filter artist <ban|unban|toggle> <source> <artistId>` — mutates an exact artist rule
 *
 * Access is controlled by MoeMusic permission nodes, with configurable vanilla fallback levels.
 */
object MusicCommands {

    private val logger = LoggerFactory.getLogger(MusicCommands::class.java)
    private const val COMMAND_SEARCH_PAGE_SIZE = 8  // Since we're occupying two lines for each search result and chat panel can only show 20 lines, we want to keep the page size small enough to allow viewing all results without scrolling.

    /** Scope for background search jobs. SupervisorJob so one failing search doesn't kill others. */
    private val searchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("music")
                .then(
                    Commands.argument("linkOrId", StringArgumentType.greedyString())
                        .executes { ctx ->
                            cmdAdd(ctx.source, StringArgumentType.getString(ctx, "linkOrId"), TrackAddMode.NORMAL)
                        }
                )
                .then(addCommandNode())
                .then(addByIdCommandNode())
                .then(selectionCommandNode())
                .then(systemCommandNode())
                .then(reloadCommandNode())
                .then(filterCommandNode())
                // /music pause
                .then(
                    Commands.literal("pause")
                        .requires(requiresPermission(PermissionNodes.PLAYBACK_CONTROL))
                        .executes { ctx ->
                            cmdPlaybackControl(ctx.source, PlaybackAction.PAUSE, LocalizedText.key("action.moemusic.playback.paused"))
                        }
                )
                // /music resume
                .then(
                    Commands.literal("resume")
                        .requires(requiresPermission(PermissionNodes.PLAYBACK_CONTROL))
                        .executes { ctx ->
                            cmdPlaybackControl(ctx.source, PlaybackAction.RESUME, LocalizedText.key("action.moemusic.playback.resumed"))
                        }
                )
                // /music skip, /music next
                .then(
                    Commands.literal("skip")
                        .requires(requiresSkipPermission())
                        .executes { ctx -> cmdSkip(ctx.source) }
                )
                .then(
                    Commands.literal("next")
                        .requires(requiresSkipPermission())
                        .executes { ctx -> cmdSkip(ctx.source) }
                )
                // /music stop
                .then(
                    Commands.literal("stop")
                        .requires(requiresPermission(PermissionNodes.PLAYBACK_CONTROL))
                        .executes { ctx ->
                            cmdPlaybackControl(ctx.source, PlaybackAction.STOP, LocalizedText.key("action.moemusic.playback.stopped"))
                        }
                )
                // /music queue, /music list
                .then(
                    Commands.literal("queue")
                        .requires(requiresPermission(PermissionNodes.QUEUE_VIEW))
                        .executes { ctx -> cmdQueue(ctx.source) }
                )
                .then(
                    Commands.literal("list")
                        .requires(requiresPermission(PermissionNodes.QUEUE_VIEW))
                        .executes { ctx -> cmdQueue(ctx.source) }
                )
                .then(removeCommandNode())
                .then(searchCommandNode())
        )
    }

    // -------------------------------------------------------------------------
    // Command implementations
    // -------------------------------------------------------------------------

    private fun addCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("add")
            .requires(requiresPermission(PermissionNodes.SUBMIT))
            .then(
                Commands.argument("linkOrId", StringArgumentType.greedyString())
                    .executes { ctx ->
                        cmdAdd(ctx.source, StringArgumentType.getString(ctx, "linkOrId"), TrackAddMode.NORMAL)
                    }
            )
            .then(
                Commands.literal("--skip-autoplay")
                    .requires(requiresPermission(PermissionNodes.SUBMIT_SKIP_AUTOPLAY))
                    .then(
                        Commands.argument("linkOrId", StringArgumentType.greedyString())
                            .executes { ctx ->
                                cmdAdd(ctx.source, StringArgumentType.getString(ctx, "linkOrId"), TrackAddMode.SKIP_AUTOPLAY)
                            }
                    )
            )
            .then(
                Commands.literal("--now")
                    .requires(requiresPermission(PermissionNodes.QUEUE_CONTROL))
                    .then(
                        Commands.argument("linkOrId", StringArgumentType.greedyString())
                            .executes { ctx ->
                                cmdAdd(ctx.source, StringArgumentType.getString(ctx, "linkOrId"), TrackAddMode.PLAY_NOW)
                            }
                    )
            )

    private fun addByIdCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("addById")
            .requires(requiresPermission(PermissionNodes.SUBMIT))
            .then(
                sourceIdArgument()
                    .then(
                        Commands.argument("trackId", StringArgumentType.string())
                            .executes { ctx ->
                                cmdAddById(
                                    source = ctx.source,
                                    sourceId = StringArgumentType.getString(ctx, "source"),
                                    trackId = StringArgumentType.getString(ctx, "trackId"),
                                    mode = TrackAddMode.NORMAL,
                                )
                            }
                            .then(
                                Commands.literal("--skip-autoplay")
                                    .requires(requiresPermission(PermissionNodes.SUBMIT_SKIP_AUTOPLAY))
                                    .executes { ctx ->
                                        cmdAddById(
                                            source = ctx.source,
                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                            trackId = StringArgumentType.getString(ctx, "trackId"),
                                            mode = TrackAddMode.SKIP_AUTOPLAY,
                                        )
                                    }
                            )
                            .then(
                                Commands.literal("--now")
                                    .requires(requiresPermission(PermissionNodes.QUEUE_CONTROL))
                                    .executes { ctx ->
                                        cmdAddById(
                                            source = ctx.source,
                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                            trackId = StringArgumentType.getString(ctx, "trackId"),
                                            mode = TrackAddMode.PLAY_NOW,
                                        )
                                    }
                            )
                    )
            )

    private fun searchCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("search")
            .requires(requiresPermission(PermissionNodes.SEARCH))
            .then(
                Commands.argument("query", StringArgumentType.greedyString())
                    .executes { ctx -> executeSearch(ctx) }
            )
            .then(
                Commands.literal("--source")
                    .then(
                        sourceIdArgument(searchableOnly = true)
                            .then(
                                Commands.argument("query", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        executeSearch(
                                            ctx,
                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                        )
                                    }
                            )
                            .then(
                                Commands.literal("--page")
                                    .then(
                                        Commands.argument("page", IntegerArgumentType.integer(1))
                                            .then(
                                                Commands.argument("query", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        executeSearch(
                                                            ctx,
                                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                                            page = IntegerArgumentType.getInteger(ctx, "page"),
                                                        )
                                                    }
                                            )
                                    )
                            )
                    )
            )
            .then(
                Commands.literal("--page")
                    .then(
                        Commands.argument("page", IntegerArgumentType.integer(1))
                            .then(
                                Commands.argument("query", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        executeSearch(
                                            ctx,
                                            page = IntegerArgumentType.getInteger(ctx, "page"),
                                        )
                                    }
                            )
                            .then(
                                Commands.literal("--source")
                                    .then(
                                        sourceIdArgument(searchableOnly = true)
                                            .then(
                                                Commands.argument("query", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        executeSearch(
                                                            ctx,
                                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                                            page = IntegerArgumentType.getInteger(ctx, "page"),
                                                        )
                                                    }
                                            )
                                    )
                            )
                    )
            )

    private fun executeSearch(
        ctx: CommandContext<CommandSourceStack>,
        sourceId: String? = null,
        page: Int = 1,
    ): Int = cmdSearch(
        source = ctx.source,
        queryText = StringArgumentType.getString(ctx, "query"),
        sourceId = sourceId,
        page = page,
    )

    private fun selectionCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("select")
            .requires(requiresPermission(PermissionNodes.SUBMIT))
            .then(
                sourceIdArgument()
                    .then(
                        Commands.argument("choiceId", StringArgumentType.string())
                            .executes { ctx ->
                                cmdSelect(
                                    source = ctx.source,
                                    sourceId = StringArgumentType.getString(ctx, "source"),
                                    selectionId = StringArgumentType.getString(ctx, "choiceId"),
                                    mode = TrackAddMode.NORMAL,
                                )
                            }
                            .then(
                                Commands.literal("--skip-autoplay")
                                    .requires(requiresPermission(PermissionNodes.SUBMIT_SKIP_AUTOPLAY))
                                    .executes { ctx ->
                                        cmdSelect(
                                            source = ctx.source,
                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                            selectionId = StringArgumentType.getString(ctx, "choiceId"),
                                            mode = TrackAddMode.SKIP_AUTOPLAY,
                                        )
                                    }
                            )
                            .then(
                                Commands.literal("--now")
                                    .requires(requiresPermission(PermissionNodes.QUEUE_CONTROL))
                                    .executes { ctx ->
                                        cmdSelect(
                                            source = ctx.source,
                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                            selectionId = StringArgumentType.getString(ctx, "choiceId"),
                                            mode = TrackAddMode.PLAY_NOW,
                                        )
                                    }
                            )
                    )
            )

    private fun reloadCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("reload")
            .then(
                Commands.literal("all")
                    .requires(requiresPermission(PermissionNodes.CONFIG_RELOAD))
                    .executes { ctx -> cmdReloadAll(ctx.source) }
            )
            .then(
                Commands.literal("filter")
                    .requires(requiresPermission(PermissionNodes.CONTENT_FILTER_MANAGE))
                    .executes { ctx -> cmdFilterReload(ctx.source) }
            )
            .then(
                Commands.literal("autoplay")
                    .requires(requiresPermission(PermissionNodes.AUTOPLAY_REFRESH))
                    .executes { ctx -> cmdReloadAutoplay(ctx.source) }
            )

    private fun systemCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("system")
            .requires(requiresPermission(PermissionNodes.SYSTEM_INFO))
            .executes { ctx -> cmdSystem(ctx.source) }

    private fun filterCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("filter")
            .requires(requiresPermission(PermissionNodes.CONTENT_FILTER_MANAGE))
            .then(filterTrackCommandNode())
            .then(filterArtistCommandNode())

    private fun filterTrackCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("track")
            .then(filterTrackActionNode("ban", ContentFilterRuleAction.BAN))
            .then(filterTrackActionNode("unban", ContentFilterRuleAction.UNBAN))
            .then(filterTrackActionNode("toggle", ContentFilterRuleAction.TOGGLE))

    private fun filterTrackActionNode(
        literal: String,
        action: ContentFilterRuleAction,
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(literal)
            .then(
                sourceIdArgument()
                    .then(
                        Commands.argument("trackId", StringArgumentType.string())
                            .executes { ctx ->
                                cmdFilterTrack(
                                    source = ctx.source,
                                    action = action,
                                    sourceId = StringArgumentType.getString(ctx, "source"),
                                    trackId = StringArgumentType.getString(ctx, "trackId"),
                                    note = null,
                                )
                            }
                            .then(
                                Commands.argument("note", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        cmdFilterTrack(
                                            source = ctx.source,
                                            action = action,
                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                            trackId = StringArgumentType.getString(ctx, "trackId"),
                                            note = StringArgumentType.getString(ctx, "note"),
                                        )
                                    }
                            )
                    )
            )

    private fun removeCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("remove")
            .requires(requiresPermission(PermissionNodes.QUEUE_VIEW))
            .then(
                Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes { ctx ->
                        cmdQueueRemoveByIndex(
                            source = ctx.source,
                            index = IntegerArgumentType.getInteger(ctx, "index"),
                        )
                    }
            )
            .then(
                sourceIdArgument()
                    .then(
                        Commands.argument("trackId", StringArgumentType.string())
                            .executes { ctx ->
                                cmdQueueRemove(
                                    source = ctx.source,
                                    sourceId = StringArgumentType.getString(ctx, "source"),
                                    trackId = StringArgumentType.getString(ctx, "trackId"),
                                )
                            }
                    )
            )

    private fun filterArtistCommandNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("artist")
            .then(filterArtistActionNode("ban", ContentFilterRuleAction.BAN))
            .then(filterArtistActionNode("unban", ContentFilterRuleAction.UNBAN))
            .then(filterArtistActionNode("toggle", ContentFilterRuleAction.TOGGLE))

    private fun filterArtistActionNode(
        literal: String,
        action: ContentFilterRuleAction,
    ): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(literal)
            .then(
                sourceIdArgument()
                    .then(
                        Commands.argument("artistId", StringArgumentType.string())
                            .executes { ctx ->
                                cmdFilterArtist(
                                    source = ctx.source,
                                    action = action,
                                    sourceId = StringArgumentType.getString(ctx, "source"),
                                    artistId = StringArgumentType.getString(ctx, "artistId"),
                                    note = null,
                                )
                            }
                            .then(
                                Commands.argument("note", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        cmdFilterArtist(
                                            source = ctx.source,
                                            action = action,
                                            sourceId = StringArgumentType.getString(ctx, "source"),
                                            artistId = StringArgumentType.getString(ctx, "artistId"),
                                            note = StringArgumentType.getString(ctx, "note"),
                                        )
                                    }
                            )
                    )
            )

    private fun cmdAdd(source: CommandSourceStack, linkOrId: String, mode: TrackAddMode): Int {
        val trimmed = linkOrId.trim()
        if (trimmed.isEmpty()) {
            sendFailure(source, LocalizedText.key("error.moemusic.identifier.blank"))
            return 0
        }

        searchScope.launch {
            source.server.execute {
                sendSuccess(source, LocalizedText.key("action.moemusic.identifier.resolving"))
            }

            try {
                when (val outcome = MoePlatform.userActionService.submitIdentifier(
                    identifier = trimmed,
                    submitter = sourceUser(source),
                    mode = mode,
                )) {
                    is IdentifierSubmitOutcome.Submitted -> {
                        source.server.execute {
                            sendSuccess(source, successMessage(outcome.track, outcome.result))
                        }
                    }

                    is IdentifierSubmitOutcome.Choices -> {
                        source.server.execute {
                            renderSelectionChoices(source, outcome.entries, intro = selectionPrompt())
                        }
                    }
                }
            } catch (e: Exception) {
                logHandledFailure("Identifier submit", trimmed, e)
                source.server.execute {
                    sendFailure(source, classifyForSource(source, e))
                }
            }
        }
        return 1
    }

    private fun cmdAddById(source: CommandSourceStack, sourceId: String, trackId: String, mode: TrackAddMode): Int {
        if (trackId.isBlank()) {
            sendFailure(source, LocalizedText.key("error.moemusic.track_id.blank"))
            return 0
        }

        searchScope.launch {
            source.server.execute {
                sendSuccess(source, LocalizedText.key("action.moemusic.track.lookup"))
            }
            try {
                val outcome = MoePlatform.userActionService.submitBySourceAndId(
                    sourceId = sourceId,
                    trackId = trackId,
                    submitter = sourceUser(source),
                    mode = mode,
                )
                source.server.execute {
                    sendSuccess(source, successMessage(outcome.track, outcome.result))
                }
            } catch (e: Exception) {
                logHandledFailure("Track submit", "$sourceId:$trackId", e)
                source.server.execute {
                    sendFailure(source, classifyForSource(source, e))
                }
            }
        }
        return 1
    }

    private fun cmdSelect(source: CommandSourceStack, sourceId: String, selectionId: String, mode: TrackAddMode): Int {
        if (selectionId.isBlank()) {
            sendFailure(source, LocalizedText.key("error.moemusic.selection.bad_request"))
            return 0
        }

        searchScope.launch {
            try {
                when (val outcome = MoePlatform.userActionService.submitBySelection(
                    sourceId = sourceId,
                    selectionId = selectionId,
                    submitter = sourceUser(source),
                    mode = mode,
                )) {
                    is SelectionSubmitOutcome.Submitted -> {
                        source.server.execute {
                            sendSuccess(source, successMessage(outcome.track, outcome.result))
                        }
                    }

                    is SelectionSubmitOutcome.Choices -> {
                        source.server.execute {
                            renderSelectionChoices(source, outcome.entries)
                        }
                    }
                }
            } catch (e: Exception) {
                logHandledFailure("Selection submit", "$sourceId:$selectionId", e)
                source.server.execute {
                    sendFailure(source, classifyForSource(source, e))
                }
            }
        }
        return 1
    }

    private fun cmdQueue(source: CommandSourceStack): Int {
        val currentTrack = MoePlatform.playbackController.currentContext?.track
        val snapshot = MoePlatform.queue.userQueueSnapshot()

        if (currentTrack == null && snapshot.isEmpty()) {
            sendSuccess(source, LocalizedText.key("action.moemusic.queue.empty"))
            return 1
        }

        val totalShown = snapshot.size + (if (currentTrack != null) 1 else 0)
        sendMultilineSuccess(
            source,
            buildList {
                add(prefixedSuccessLine(source, LocalizedText.key("action.moemusic.queue.header", totalShown)))
                currentTrack?.let { track ->
                    add(buildQueueTrackLineComponent(source, null, track, isCurrent = true))
                }
                snapshot.forEachIndexed { i, track ->
                    add(buildQueueTrackLineComponent(source, i + 1, track, isCurrent = false))
                }
            }
        )
        return 1
    }

    private fun cmdQueueRemove(source: CommandSourceStack, sourceId: String, trackId: String): Int {
        val trimmedSourceId = sourceId.trim()
        val trimmedTrackId = trackId.trim()
        if (trimmedSourceId.isBlank() || trimmedTrackId.isBlank()) {
            sendFailure(source, LocalizedText.key("error.moemusic.track.bad_request"))
            return 0
        }

        return when (
            MoePlatform.userActionService.removeQueuedTrack(
                sourceId = trimmedSourceId,
                trackId = trimmedTrackId,
                requester = sourceUser(source),
            ).result
        ) {
            QueueRemoveResult.REMOVED -> {
                sendSuccess(source, LocalizedText.key("action.moemusic.queue.removed", trimmedTrackId))
                1
            }

            QueueRemoveResult.NOT_FOUND -> {
                sendFailure(source, LocalizedText.key("error.moemusic.queue.track_not_found"))
                0
            }

            QueueRemoveResult.FORBIDDEN -> {
                sendFailure(source, LocalizedText.key("error.moemusic.queue.remove_forbidden"))
                0
            }
        }
    }

    private fun cmdQueueRemoveByIndex(source: CommandSourceStack, index: Int): Int {
        val snapshot = MoePlatform.queue.userQueueSnapshot()
        val track = snapshot.getOrNull(index - 1)
        if (track == null) {
            sendFailure(source, LocalizedText.key("error.moemusic.queue.invalid_index"))
            return 0
        }

        val sourceId = track.sourceId
        if (sourceId.isNullOrBlank() || track.id.isBlank()) {
            sendFailure(source, LocalizedText.key("error.moemusic.queue.track_not_found"))
            return 0
        }

        return cmdQueueRemove(source, sourceId, track.id)
    }

    private fun cmdFilterReload(source: CommandSourceStack): Int {
        return try {
            ContentFilterRuleEditor.reloadFromDisk(MoePlatform.configDir)
            sendSuccess(source, LocalizedText.key("action.moemusic.filter.reloaded"))
            1
        } catch (e: IllegalStateException) {
            logHandledFailure("Content filter reload", "server config", e)
            sendFailure(source, reloadFailureMessage(e))
            0
        } catch (e: Exception) {
            logHandledFailure("Content filter reload", "server config", e)
            sendFailure(source, classifyForSource(source, e))
            0
        }
    }

    private fun cmdReloadAll(source: CommandSourceStack): Int {
        val report = try {
            MoePlatform.reloadServerConfigFromDisk()
        } catch (e: IllegalStateException) {
            logHandledFailure("MoeMusic reload", "server config", e)
            sendFailure(source, reloadFailureMessage(e))
            return 0
        } catch (e: Exception) {
            logHandledFailure("MoeMusic reload", "server config", e)
            sendFailure(source, classifyForSource(source, e))
            return 0
        }

        sendMultilineSuccess(
            source,
            buildList {
                add(prefixedSuccessLine(source, LocalizedText.key("action.moemusic.reload.reloaded")))
                if (report.pluginConfigsNotified.isNotEmpty()) {
                    add(
                        prefixedSuccessLine(
                            source,
                            LocalizedText.key(
                                "action.moemusic.reload.plugin_notified",
                                report.pluginConfigsNotified.joinToString(", "),
                            ),
                        )
                    )
                }
            }
        )
        if (report.pluginConfigFailures.isNotEmpty()) {
            sendFailure(
                source,
                LocalizedText.key(
                    "error.moemusic.reload.plugin_failures",
                    report.pluginConfigFailures.keys.joinToString(", "),
                ),
            )
        }
        return 1
    }

    private fun cmdReloadAutoplay(source: CommandSourceStack): Int {
        return try {
            MoePlatform.refreshAutoplayRuntime()
            sendSuccess(source, LocalizedText.key("action.moemusic.reload.autoplay_reloaded"))
            1
        } catch (e: Exception) {
            logHandledFailure("Autoplay refresh", "server runtime", e)
            sendFailure(source, classifyForSource(source, e))
            0
        }
    }

    private fun cmdSystem(source: CommandSourceStack): Int {
        val plugins = PluginManager.plugins.sortedBy { it.id.lowercase(Locale.ROOT) }
        val musicSources = PluginManager.musicSourceSnapshot().sortedBy { it.id.lowercase(Locale.ROOT) }
        val runtimeInfo = MoePlatform.runtimeInfo

        sendMultilineSuccess(
            source,
            buildList {
                add(
                    prefixedSuccessLine(
                        source,
                        LocalizedText.key(
                            "action.moemusic.system.summary",
                            runtimeInfo.modVersion,
                            runtimeInfo.loaderId,
                            MoeMusicApi.API_VERSION,
                            runtimeInfo.coreVersion,
                            runtimeInfo.clientCoreVersion,
                            runtimeInfo.platformCommonVersion,
                            plugins.size.toString(),
                            musicSources.size.toString(),
                        ),
                    )
                )

                plugins.forEach { plugin ->
                    add(
                        McText.literal("  ").append(
                            localizedComponent(
                                source,
                                LocalizedText.key(
                                    "action.moemusic.system.plugin",
                                    render(source, plugin.displayName),
                                    plugin.id,
                                    plugin.version,
                                    plugin.configId,
                                    localizedBoolean(source, plugin.configSpec != null),
                                ),
                            )
                        )
                    )
                }

                add(McText.literal(""))

                musicSources.forEach { musicSource ->
                    val idResolver = musicSource is IdentifierResolvableMusicSource
                    add(
                        McText.literal("  ").append(
                            localizedComponent(
                                source,
                                LocalizedText.key(
                                    "action.moemusic.system.source",
                                    render(source, musicSource.displayName),
                                    musicSource.id,
                                    localizedBoolean(source, musicSource is SearchableMusicSource),
                                    localizedBoolean(source, idResolver),
                                    localizedBoolean(source, idResolver && musicSource.isFallbackResolver),
                                ),
                            )
                        )
                    )
                }
            }
        )

        return 1
    }

    private fun cmdFilterTrack(
        source: CommandSourceStack,
        action: ContentFilterRuleAction,
        sourceId: String,
        trackId: String,
        note: String?,
    ): Int {
        val trimmedTrackId = trackId.trim()
        if (sourceId.isBlank() || trimmedTrackId.isBlank()) {
            sendFailure(source, LocalizedText.key("error.moemusic.track.bad_request"))
            return 0
        }
        val result = ContentFilterRuleEditor.updateTrackRule(sourceId, trimmedTrackId, action, note)
        if (result.nowBlocked) {
            val removal = MoePlatform.playbackController.removeQueuedTrack(
                sourceId = sourceId,
                trackId = trimmedTrackId,
                requester = null,
                bypassOwnership = true,
            )
            if (removal == QueueRemoveResult.NOT_FOUND) {
                MoePlatform.playbackController.skipIfCurrentTrackMatches(sourceId, trimmedTrackId)
            }
        }
        sendSuccess(source, trackFilterMessage(action, result.nowBlocked, trimmedTrackId, result.changed))
        return 1
    }

    private fun cmdFilterArtist(
        source: CommandSourceStack,
        action: ContentFilterRuleAction,
        sourceId: String,
        artistId: String,
        note: String?,
    ): Int {
        val trimmedArtistId = artistId.trim()
        if (sourceId.isBlank() || trimmedArtistId.isBlank()) {
            sendFailure(source, LocalizedText.key("error.moemusic.track.bad_request"))
            return 0
        }
        val result = ContentFilterRuleEditor.updateArtistRules(sourceId, listOf(trimmedArtistId), action, note)
        sendSuccess(source, artistFilterMessage(action, result.nowBlocked, trimmedArtistId, result.changed))
        return 1
    }

    private fun cmdSkip(source: CommandSourceStack): Int {
        val outcome = try {
            MoePlatform.userActionService.controlPlayback(
                action = PlaybackAction.SKIP,
                requester = sourceUser(source),
            )
        } catch (e: Exception) {
            logHandledFailure("Playback control", "skip", e)
            sendFailure(source, classifyForSource(source, e))
            return 0
        }

        outcome.failure?.let {
            sendFailure(source, it)
            return 0
        }
        sendSuccess(source, outcome.success ?: LocalizedText.key("action.moemusic.playback.skipped"))
        return 1
    }

    private fun cmdPlaybackControl(
        source: CommandSourceStack,
        action: PlaybackAction,
        successMessage: LocalizedText,
    ): Int {
        val outcome = try {
            MoePlatform.userActionService.controlPlayback(
                action = action,
                requester = sourceUser(source),
            )
        } catch (e: Exception) {
            logHandledFailure("Playback control", action.name.lowercase(), e)
            sendFailure(source, classifyForSource(source, e))
            return 0
        }

        outcome.failure?.let {
            sendFailure(source, it)
            return 0
        }
        sendSuccess(source, outcome.success ?: successMessage)
        return 1
    }

    private fun cmdSearch(
        source: CommandSourceStack,
        queryText: String,
        sourceId: String? = null,
        page: Int = 1,
    ): Int {
        if (PluginManager.musicSources.none { it is SearchableMusicSource }) {
            sendFailure(source, LocalizedText.key("error.moemusic.search.no_sources"))
            return 0
        }

        val selectedSourceId = sourceId?.trim()?.takeIf(String::isNotEmpty)
        val selectedSource = selectedSourceId?.let { requestedSourceId ->
            PluginManager.musicSources.firstOrNull { it.id == requestedSourceId }
        }
        if (selectedSourceId != null && selectedSource == null) {
            sendFailure(source, LocalizedText.key("error.moemusic.source.not_found", selectedSourceId))
            return 0
        }
        if (selectedSource != null && selectedSource !is SearchableMusicSource) {
            sendFailure(source, LocalizedText.key("error.moemusic.source.bad_format"))
            return 0
        }

        val normalizedQueryText = queryText.trim()
        val requestedPage = page.coerceAtLeast(1)
        val pageSize = commandSearchPageSize()
        val query = SearchQuery(
            query = normalizedQueryText,
            sourceId = selectedSource?.id,
            limit = pageSize,
            offset = (requestedPage - 1) * pageSize,
        )
        sendSuccess(source, LocalizedText.key("action.moemusic.search.searching", normalizedQueryText))

        // Search is suspend — run on Dispatchers.IO, dispatch results back on the server thread
        searchScope.launch {
            try {
                val user = runCatching { source.playerOrException.uuid }.getOrNull()?.let { MinecraftUserRegistry.getActive(it) }
                val outcome = MoePlatform.userActionService.search(query, user)
                source.server.execute {
                    val failure = outcome.failure
                    if (failure != null) {
                        sendFailure(source, failure)
                    } else if (outcome.entries.isEmpty()) {
                        sendMultilineSuccess(
                            source,
                            buildList {
                                add(prefixedSuccessLine(source, LocalizedText.key("action.moemusic.search.no_results")))
                                buildSearchPaginationFooter(
                                    source = source,
                                    queryText = normalizedQueryText,
                                    sourceId = outcome.sourceId.ifBlank { query.sourceId.orEmpty() },
                                    currentPage = requestedPage,
                                    pageSize = pageSize,
                                    total = outcome.total,
                                    hasNext = outcome.hasMore,
                                )?.let(::add)
                            }
                        )
                    } else {
                        val canBypassFilter = canBypassContentFilter(source)
                        val canSeeFilterDetail = canManageContentFilter(source)
                        sendMultilineSuccess(
                            source,
                            buildList {
                                add(prefixedSuccessLine(source, LocalizedText.key("action.moemusic.search.header", outcome.entries.size)))
                                outcome.entries.forEachIndexed { i, entry ->
                                    add(renderSelectionChoiceLine(source, i, entry, canBypassFilter, canSeeFilterDetail))
                                }
                                buildSearchPaginationFooter(
                                    source = source,
                                    queryText = normalizedQueryText,
                                    sourceId = outcome.sourceId.ifBlank { query.sourceId.orEmpty() },
                                    currentPage = requestedPage,
                                    pageSize = pageSize,
                                    total = outcome.total,
                                    hasNext = outcome.hasMore,
                                )?.let(::add)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                logHandledFailure("Search", query.query, e)
                source.server.execute {
                    sendFailure(source, classifyForSource(source, e))
                }
            }
        }

        return 1
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun requiresPermission(permission: PermissionNodes.Node): (CommandSourceStack) -> Boolean = { source ->
        PermissionResolver.hasPermission(source, permission)
    }

    private fun requiresSkipPermission(): (CommandSourceStack) -> Boolean = { source ->
        PermissionResolver.hasPermission(source, PermissionNodes.QUEUE_CONTROL) ||
            (sourceServerPlayer(source) != null && PermissionResolver.hasPermission(source, PermissionNodes.VOTE))
    }

    private fun sourceIdArgument(searchableOnly: Boolean = false) =
        Commands.argument("source", StringArgumentType.string())
            .suggests { _, builder -> suggestSourceIds(builder, searchableOnly) }

    internal fun suggestSourceIds(
        builder: SuggestionsBuilder,
        searchableOnly: Boolean = false,
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remainingLowerCase
        sourceIdSuggestionCandidates(searchableOnly).forEach { sourceId ->
            val suggestedToken = sourceIdSuggestionToken(sourceId, builder.remaining)
            if (matchesSourceIdSuggestion(sourceId, suggestedToken, remaining)) {
                builder.suggest(suggestedToken)
            }
        }
        return builder.buildFuture()
    }

    private fun sourceIdSuggestionCandidates(searchableOnly: Boolean): List<String> = buildList {
        val seen = HashSet<String>()
        PluginManager.musicSourceSnapshot().forEach { source ->
            if (searchableOnly && source !is SearchableMusicSource) return@forEach
            val sourceId = source.id.trim()
            if (sourceId.isNotEmpty() && seen.add(sourceId)) {
                add(sourceId)
            }
        }
    }

    private fun sourceIdSuggestionToken(sourceId: String, remaining: String): String =
        if (remaining.startsWith("\"")) quotedCommandToken(sourceId) else quoteCommandToken(sourceId)

    private fun matchesSourceIdSuggestion(
        sourceId: String,
        suggestedToken: String,
        remainingLowerCase: String,
    ): Boolean {
        if (remainingLowerCase.isBlank()) return true
        val normalizedSourceId = sourceId.lowercase(Locale.ROOT)
        if (normalizedSourceId.startsWith(remainingLowerCase)) return true
        if (suggestedToken.lowercase(Locale.ROOT).startsWith(remainingLowerCase)) return true
        return quotedCommandToken(sourceId).lowercase(Locale.ROOT).startsWith(remainingLowerCase)
    }

    private fun renderSelectionChoices(
        source: CommandSourceStack,
        entries: List<SelectionEntry>,
        intro: LocalizedText? = null,
    ) {
        val canBypassFilter = canBypassContentFilter(source)
        val canSeeFilterDetail = canManageContentFilter(source)
        sendMultilineSuccess(
            source,
            buildList {
                intro?.let { add(prefixedSuccessLine(source, it)) }
                add(prefixedSuccessLine(source, LocalizedText.key("action.moemusic.selection.header", entries.size)))
                entries.forEachIndexed { index, entry ->
                    add(renderSelectionChoiceLine(source, index, entry, canBypassFilter, canSeeFilterDetail))
                }
            }
        )
    }

    private fun renderSelectionChoiceLine(
        source: CommandSourceStack,
        index: Int,
        entry: SelectionEntry,
        canBypassFilter: Boolean = canBypassContentFilter(source),
        canSeeFilterDetail: Boolean = canManageContentFilter(source),
    ): Component {
        // Resolve filter verdict for the command display path.
        // Bypass players see every entry as selectable, matching what the submission gate will do.
        val filterReason: LocalizedText? = when {
            canBypassFilter -> null
            else -> when (val verdict = ContentFilterRuntime.selectionFilterVerdict(entry)) {
                FilterVerdict.Allow -> null
                is FilterVerdict.Reject -> if (canSeeFilterDetail) verdict.reason
                    else LocalizedText.key("error.moemusic.content_filter.managed")
            }
        }

        // An entry is considered unavailable if either the source marked it unavailable or the
        // filter blocked it (and the sender can't see it as selectable).
        val isSelectable = entry.isSelectable && filterReason == null
        val unavailabilityText: LocalizedText = filterReason ?: entry.unavailabilityMessage()
        return buildSelectionEntryLineComponent(source, index, entry, isSelectable, unavailabilityText)
    }

    private fun successMessage(track: TrackInfo, result: TrackAddResult): LocalizedText {
        val title = track.title.ifBlank { track.id.ifBlank { "track" } }
        return LocalizedText.key("action.moemusic.track.queued", title)
    }

    private fun selectionPrompt(): LocalizedText =
        LocalizedText.key("action.moemusic.selection.choose_prompt")

    private fun buildSelectionEntryLineComponent(
        source: CommandSourceStack,
        index: Int,
        entry: SelectionEntry,
        isSelectable: Boolean,
        unavailabilityText: LocalizedText,
    ): MutableComponent {
        val defaultAction = selectionDefaultAction(source, entry, isSelectable)
        val firstLine = McText.literal("")
            .append(buildSelectionEntryPrimaryLineComponent(source, index, entry, isSelectable, unavailabilityText, defaultAction))
            .append(selectionActionSuffixForSelectionEntry(source, entry, defaultAction))
            .append(moderationSuffixForSelectionEntry(source, entry))
        val secondLine = buildSelectionEntryMetaLineComponent(source, entry, isSelectable, unavailabilityText, defaultAction)
        return McText.literal("")
            .append(firstLine)
            .append(McText.literal("\n"))
            .append(secondLine)
    }

    private fun buildQueueTrackLineComponent(
        source: CommandSourceStack,
        index: Int?,
        track: TrackInfo,
        isCurrent: Boolean,
    ): MutableComponent {
        val firstLine = McText.literal("")
            .append(buildQueueTrackPrimaryLineComponent(source, index, track, isCurrent))
            .apply {
                if (!isCurrent) append(queueActionSuffixForTrack(source, track))
                append(moderationSuffixForTrack(source, track))
            }
        val secondLine = buildQueueTrackMetaLineComponent(source, track)
        return McText.literal("")
            .append(firstLine)
            .append(McText.literal("\n"))
            .append(secondLine)
    }

    private fun buildSelectionEntryPrimaryLineComponent(
        source: CommandSourceStack,
        index: Int,
        entry: SelectionEntry,
        isSelectable: Boolean,
        unavailabilityText: LocalizedText,
        defaultAction: CommandActionTarget?,
    ): MutableComponent {
        val titleColor = if (isSelectable) "§b" else "§7"
        val title = truncateForChat(
            entry.title.ifBlank { "-" },
            selectionPrimaryTitleLimit(source, entry, defaultAction),
        )
        val line = McText.literal("  ")
            .append(McText.literal("§e${index + 1}. "))
            .append(McText.literal("$titleColor$title"))
        return when {
            defaultAction != null -> applyCommandAction(line, source, defaultAction)
            !isSelectable -> applyHoverText(line, source, unavailabilityText)
            else -> line
        }
    }

    private fun buildSelectionEntryMetaLineComponent(
        source: CommandSourceStack,
        entry: SelectionEntry,
        isSelectable: Boolean,
        unavailabilityText: LocalizedText,
        defaultAction: CommandActionTarget?,
    ): MutableComponent {
        val line = McText.literal("  ")
        var hasMeta = false
        hasMeta = appendMetaField(
            line,
            hasMeta,
            truncateForChat(compactMetaText(entry.artistDisplay.takeIf { it.isNotBlank() && it != "-" }), 20),
            "§7",
        )
        hasMeta = if (isSelectable) {
            appendMetaField(
                line,
                hasMeta,
                truncateForChat(compactMetaText(entry.album?.takeIf { it.isNotBlank() }), 18),
                "§9",
            )
        } else {
            appendMetaField(line, hasMeta, truncateForChat(render(source, unavailabilityText), 36), "§c")
        }
        appendMetaField(line, hasMeta, formatDuration(entry.durationMs), "§8")
        return when {
            defaultAction != null -> applyCommandAction(line, source, defaultAction)
            !isSelectable -> applyHoverText(line, source, unavailabilityText)
            else -> line
        }
    }

    private fun buildQueueTrackPrimaryLineComponent(
        source: CommandSourceStack,
        index: Int?,
        track: TrackInfo,
        isCurrent: Boolean,
    ): MutableComponent {
        val titleColor = if (isCurrent) "§6" else if (track.isAvailable) "§b" else "§7"
        val prefix = if (isCurrent) {
            val nowPlaying = render(source, LocalizedText.key("screen.moemusic.queue.now_playing"))
            "§6$nowPlaying§8: "
        } else {
            "§e${requireNotNull(index)}. "
        }
        val title = truncateForChat(
            track.title.ifBlank { "-" },
            queuePrimaryTitleLimit(source, track, isCurrent),
        )
        val line = McText.literal("  ")
            .append(McText.literal(prefix))
            .append(McText.literal("$titleColor$title"))
        return if (track.isAvailable) line else applyHoverText(line, source, track.unavailabilityMessage())
    }

    private fun buildQueueTrackMetaLineComponent(
        source: CommandSourceStack,
        track: TrackInfo,
    ): MutableComponent {
        val line = McText.literal("  ")
        var hasMeta = false
        hasMeta = appendMetaField(
            line,
            hasMeta,
            truncateForChat(compactMetaText(track.artistDisplay.takeIf { it.isNotBlank() && it != "-" }), 20),
            "§7",
        )
        hasMeta = appendMetaField(line, hasMeta, formatDuration(track.durationMs), "§8")
        if (track.isAvailable) {
            hasMeta = appendMetaField(
                line,
                hasMeta,
                truncateForChat(track.submittedByUserName?.trim()?.takeIf { it.isNotEmpty() }?.let { "@$it" }, 12),
                "§8",
            )
            appendMetaField(
                line,
                hasMeta,
                truncateForChat(compactMetaText(sourceDisplayName(source, track.sourceId).takeIf { it.isNotBlank() }), 12),
                "§3",
            )
        } else {
            appendMetaField(line, hasMeta, truncateForChat(render(source, track.unavailabilityMessage()), 36), "§c")
        }
        return if (track.isAvailable) line else applyHoverText(line, source, track.unavailabilityMessage())
    }

    private fun appendMetaField(
        line: MutableComponent,
        hasPrevious: Boolean,
        text: String?,
        colorCode: String,
    ): Boolean {
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() } ?: return hasPrevious
        if (hasPrevious) line.append(McText.literal("§8・"))
        line.append(McText.literal("$colorCode$normalized"))
        return true
    }

    private fun compactMetaText(text: String?): String? =
        text?.trim()?.takeIf { it.isNotEmpty() }?.replace(", ", ",")

    private fun truncateForChat(text: String?, maxChars: Int): String? {
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.length <= maxChars) return normalized
        if (maxChars <= 1) return normalized.take(maxChars)
        return normalized.take(maxChars - 1) + "…"
    }

    private fun selectionPrimaryTitleLimit(
        source: CommandSourceStack,
        entry: SelectionEntry,
        defaultAction: CommandActionTarget?,
    ): Int {
        var chipCount = 0
        if (defaultAction != null) {
            chipCount += 1
            if (canSubmitSkipAutoplay(source)) chipCount += 1
            if (canPlayNow(source)) chipCount += 1
        }
        if (hasSelectionModerationAction(source, entry)) chipCount += 1
        return (48 - chipCount * 3).coerceAtLeast(24)
    }

    private fun queuePrimaryTitleLimit(
        source: CommandSourceStack,
        track: TrackInfo,
        isCurrent: Boolean,
    ): Int {
        var chipCount = if (isCurrent) 0 else 1
        if (!isCurrent && canPlayNow(source)) {
            chipCount += 1
        }
        if (hasTrackModerationAction(source, track)) chipCount += 1
        val base = if (isCurrent) 40 else 48
        return (base - chipCount * 3).coerceAtLeast(if (isCurrent) 20 else 24)
    }

    private fun selectionActionSuffixForSelectionEntry(
        source: CommandSourceStack,
        entry: SelectionEntry,
        defaultAction: CommandActionTarget?,
    ): MutableComponent {
        if (defaultAction == null) return McText.literal("")

        val suffix = McText.literal("")
        suffix.append(McText.literal(" "))
        suffix.append(
            selectionActionComponent(
                source = source,
                action = defaultAction,
                label = "+",
                labelColor = "§a",
            )
        )

        if (canSubmitSkipAutoplay(source)) {
            suffix.append(
                selectionActionComponent(
                    source = source,
                    action = selectionActionTarget(source, entry, TrackAddMode.SKIP_AUTOPLAY),
                    label = "»",
                    labelColor = "§e",
                )
            )
        }
        if (canPlayNow(source)) {
            suffix.append(
                selectionActionComponent(
                    source = source,
                    action = selectionActionTarget(source, entry, TrackAddMode.PLAY_NOW),
                    label = "▶",
                    labelColor = "§c",
                )
            )
        }
        return suffix
    }

    private fun selectionActionComponent(
        source: CommandSourceStack,
        action: CommandActionTarget,
        label: String,
        labelColor: String,
    ): MutableComponent {
        return commandActionComponent(
            source = source,
            label = label,
            labelColor = labelColor,
            hover = action.hover,
            command = action.command,
        )
    }

    private fun selectionDefaultAction(
        source: CommandSourceStack,
        entry: SelectionEntry,
        isSelectable: Boolean,
    ): CommandActionTarget? {
        if (!isSelectable) return null
        if (!canSubmitTracks(source)) return null
        return selectionActionTarget(source, entry, TrackAddMode.NORMAL)
    }

    private fun selectionActionTarget(
        source: CommandSourceStack,
        entry: SelectionEntry,
        mode: TrackAddMode,
    ): CommandActionTarget {
        val sourceId = entry.sourceId ?: HttpMusicSource.id
        val command = entry.directTrackId?.let { trackId ->
            trackSubmitCommand(sourceId, trackId, mode)
        } ?: selectionCommand(sourceId, entry.selectionId, mode)
        return CommandActionTarget(
            command = command,
            hover = selectionActionHover(entry, mode),
        )
    }

    private fun selectionActionHover(entry: SelectionEntry, mode: TrackAddMode): LocalizedText = when (mode) {
        TrackAddMode.NORMAL -> if (entry.isDirectTrack) {
            LocalizedText.key("action.moemusic.search.click_to_queue", entry.title)
        } else {
            LocalizedText.key("action.moemusic.search.click_to_select", entry.title)
        }

        TrackAddMode.SKIP_AUTOPLAY -> if (entry.isDirectTrack) {
            LocalizedText.key("action.moemusic.search.click_to_queue_skip_autoplay", entry.title)
        } else {
            LocalizedText.key("action.moemusic.search.click_to_select_skip_autoplay", entry.title)
        }

        TrackAddMode.PLAY_NOW -> if (entry.isDirectTrack) {
            LocalizedText.key("action.moemusic.search.click_to_play_now", entry.title)
        } else {
            LocalizedText.key("action.moemusic.search.click_to_select_play_now", entry.title)
        }
    }

    private fun render(source: CommandSourceStack, text: LocalizedText): String =
        Localization.render(sourceLocale(source), text)

    private fun sourceUser(source: CommandSourceStack) =
        sourceServerPlayer(source)?.let(MinecraftUserRegistry::snapshot)

    private fun sourceLocale(source: CommandSourceStack): String =
        sourceLocale(sourceServerPlayer(source)?.uuid)

    private fun sourceServerPlayer(source: CommandSourceStack): ServerPlayer? =
        runCatching { source.playerOrException }.getOrNull()

    internal fun sourceLocale(userId: UUID?): String =
        Localization.resolveLocale(userId?.let(UserSessionRegistry::localeFor))

    private fun sendSuccess(source: CommandSourceStack, text: LocalizedText) =
        sendSuccess(source, prefixedSuccessLine(source, text))

    private fun sendSuccess(source: CommandSourceStack, component: Component) {
        source.sendSuccess(component, false)
    }

    private fun sendMultilineSuccess(source: CommandSourceStack, lines: List<Component>) {
        sendSuccess(source, joinMessageLines(lines))
    }

    private fun sendFailure(source: CommandSourceStack, text: LocalizedText) {
        source.sendFailure(LocalizedChatRenderer.prefixed(sourceLocale(source), text, LocalizedChatRenderer.Tone.FAILURE))
    }

    /**
     * Classify an exception for command output, applying filter-detail masking.
     *
     * [FilterBlockException] reveals the full rejection reason only to [canManageContentFilter]
     * players; everyone else receives the generic managed message.
     */
    private fun classifyForSource(source: CommandSourceStack, e: Exception): LocalizedText =
        if (e is FilterBlockException && !canManageContentFilter(source)) e.maskedReason
        else UserFacingErrors.classify(e)

    private fun reloadFailureMessage(error: IllegalStateException): LocalizedText =
        LocalizedText.key("error.moemusic.reload.failed", error.message ?: "unknown error")

    private fun logHandledFailure(action: String, subject: String, error: Exception) {
        if (UserFacingErrors.isExpected(error)) {
            logger.debug("{} rejected for {}: {}", action, subject, error.message)
        } else {
            logger.error("{} failed for {}: {}", action, subject, error.message, error)
        }
    }

    internal fun quoteCommandToken(text: String): String =
        if (text.isNotEmpty() && text.all(StringReader::isAllowedInUnquotedString)) text
        else '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

    private fun quotedCommandToken(text: String): String =
        '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"'

    /**
     * Brigadier `greedyString()` consumes the entire remaining input literally.
     *
     * Do not quote or escape the value here, or those characters become part of the parsed string.
     */
    internal fun appendGreedyCommandTail(commandPrefix: String, text: String?): String {
        val trimmed = text?.trim().orEmpty()
        return if (trimmed.isEmpty()) commandPrefix else "$commandPrefix $trimmed"
    }

    internal fun searchCommand(
        queryText: String,
        sourceId: String? = null,
        page: Int = 1,
    ): String {
        val normalizedSourceId = sourceId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedPage = page.coerceAtLeast(1)
        val base = buildString {
            append("/music search")
            normalizedSourceId?.let {
                append(" --source ")
                append(quoteCommandToken(it))
            }
            if (normalizedPage > 1) {
                append(" --page ")
                append(normalizedPage)
            }
        }
        return appendGreedyCommandTail(base, queryText)
    }

    private fun sourceDisplayName(source: CommandSourceStack, sourceId: String?): String {
        if (sourceId.isNullOrBlank()) return ""
        val musicSource = PluginManager.musicSources.firstOrNull { it.id == sourceId } ?: return sourceId
        return render(source, musicSource.displayName)
    }

    private fun buildSearchPaginationFooter(
        source: CommandSourceStack,
        queryText: String,
        sourceId: String?,
        currentPage: Int,
        pageSize: Int,
        total: Int,
        hasNext: Boolean,
    ): MutableComponent? {
        if (total <= 0 || pageSize <= 0) return null

        val normalizedSourceId = sourceId?.trim()?.takeIf(String::isNotEmpty)
        val footer = McText.literal("  ")
        if (currentPage > 1) {
            footer.append(
                commandActionComponent(
                    source = source,
                    label = "<",
                    labelColor = "§e",
                    hover = LocalizedText.key("action.moemusic.search.prev_page"),
                    command = searchCommand(queryText, normalizedSourceId, currentPage - 1),
                )
            )
            footer.append(McText.literal(" "))
        }

        footer.append(McText.literal("§8(§7$currentPage§8 / §7${totalPages(total, pageSize)}§8)"))
        val sourceLabel = sourceDisplayName(source, normalizedSourceId).trim().takeIf(String::isNotEmpty)
        if (sourceLabel != null) {
            footer.append(McText.literal(" §8· §3$sourceLabel"))
        }

        if (hasNext) {
            footer.append(McText.literal(" "))
            footer.append(
                commandActionComponent(
                    source = source,
                    label = ">",
                    labelColor = "§e",
                    hover = LocalizedText.key("action.moemusic.search.next_page"),
                    command = searchCommand(queryText, normalizedSourceId, currentPage + 1),
                )
            )
        }
        return footer
    }

    private fun localizedComponent(
        source: CommandSourceStack,
        text: LocalizedText,
        tone: LocalizedChatRenderer.Tone = LocalizedChatRenderer.Tone.NEUTRAL,
    ): MutableComponent = LocalizedChatRenderer.component(sourceLocale(source), text, tone)

    private fun prefixedSuccessLine(source: CommandSourceStack, text: LocalizedText): MutableComponent =
        LocalizedChatRenderer.prefixed(localizedComponent(source, text, LocalizedChatRenderer.Tone.SUCCESS), LocalizedChatRenderer.Tone.SUCCESS)

    private fun joinMessageLines(lines: List<Component>): MutableComponent {
        val iterator = lines.iterator()
        val message = McText.literal("")
        if (!iterator.hasNext()) return message
        message.append(iterator.next())
        while (iterator.hasNext()) {
            message.append(McText.literal("\n"))
            message.append(iterator.next())
        }
        return message
    }

    private fun localizedBoolean(source: CommandSourceStack, value: Boolean): String =
        render(source, LocalizedText.key(if (value) "label.moemusic.yes" else "label.moemusic.no"))

    private fun canManageContentFilter(source: CommandSourceStack): Boolean =
        PermissionResolver.hasPermission(source, PermissionNodes.CONTENT_FILTER_MANAGE)

    private fun canBypassContentFilter(source: CommandSourceStack): Boolean =
        PermissionResolver.hasPermission(source, PermissionNodes.CONTENT_FILTER_BYPASS)

    private fun canSubmitTracks(source: CommandSourceStack): Boolean =
        PermissionResolver.hasPermission(source, PermissionNodes.SUBMIT)

    private fun canSubmitSkipAutoplay(source: CommandSourceStack): Boolean =
        canSubmitTracks(source) && PermissionResolver.hasPermission(source, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)

    private fun canQueueControl(source: CommandSourceStack): Boolean =
        PermissionResolver.hasPermission(source, PermissionNodes.QUEUE_CONTROL)

    private fun canPlayNow(source: CommandSourceStack): Boolean =
        canSubmitTracks(source) && canQueueControl(source)

    private fun hasSelectionModerationAction(source: CommandSourceStack, entry: SelectionEntry): Boolean =
        canManageContentFilter(source) && !entry.sourceId.isNullOrBlank() && entry.directTrackId != null

    private fun hasTrackModerationAction(source: CommandSourceStack, track: TrackInfo): Boolean =
        canManageContentFilter(source) && !track.sourceId.isNullOrBlank() && track.id.isNotBlank()

    private fun moderationSuffixForSelectionEntry(source: CommandSourceStack, entry: SelectionEntry): MutableComponent {
        if (!hasSelectionModerationAction(source, entry)) return McText.literal("")
        val sourceId = entry.sourceId.orEmpty()

        val suffix = McText.literal("")
        entry.directTrackId?.let { trackId ->
            val note = buildTrackRuleNote(entry.title.ifBlank { trackId }, entry.artistDisplay)
            val isBlocked = ContentFilterRuntime.isExactTrackBlocked(sourceId, trackId)
            suffix.append(McText.literal(" "))
            suffix.append(
                filterActionComponent(
                    source = source,
                    label = if (isBlocked) "U" else "B",
                    labelColor = if (isBlocked) "§a" else "§c",
                    hover = if (isBlocked) {
                        LocalizedText.key("action.moemusic.filter.quick.unban_track_hover", entry.title.ifBlank { trackId })
                    } else {
                        LocalizedText.key("action.moemusic.filter.quick.ban_track_hover", entry.title.ifBlank { trackId })
                    },
                    command = filterTrackCommand(sourceId, trackId, note),
                )
            )
        }
        return suffix
    }

    private fun moderationSuffixForTrack(source: CommandSourceStack, track: TrackInfo): MutableComponent {
        if (!hasTrackModerationAction(source, track)) return McText.literal("")
        val sourceId = track.sourceId.orEmpty()

        val suffix = McText.literal(" ")
        val isBlocked = ContentFilterRuntime.isExactTrackBlocked(sourceId, track.id)
        suffix.append(
            filterActionComponent(
                source = source,
                label = if (isBlocked) "U" else "B",
                labelColor = if (isBlocked) "§a" else "§c",
                hover = if (isBlocked) {
                    LocalizedText.key("action.moemusic.filter.quick.unban_track_hover", track.title.ifBlank { track.id })
                } else {
                    LocalizedText.key("action.moemusic.filter.quick.ban_track_hover", track.title.ifBlank { track.id })
                },
                command = filterTrackCommand(sourceId, track.id, buildTrackRuleNote(track.title.ifBlank { track.id }, track.artistDisplay)),
            )
        )
        return suffix
    }

    private fun queueActionSuffixForTrack(source: CommandSourceStack, track: TrackInfo): MutableComponent {
        val sourceId = track.sourceId.orEmpty()
        if (sourceId.isBlank() || track.id.isBlank()) return McText.literal("")
        val suffix = McText.literal("")
        if (canPlayNow(source)) {
            suffix.append(McText.literal(" "))
            suffix.append(
                commandActionComponent(
                    source = source,
                    label = "▶",
                    labelColor = "§c",
                    hover = LocalizedText.key("action.moemusic.queue.play_now_hover", track.title.ifBlank { track.id }),
                    command = queuePlayNowCommand(sourceId, track.id),
                )
            )
        }
        if (suffix.string.isEmpty()) {
            suffix.append(McText.literal(" "))
        }
        suffix.append(
            commandActionComponent(
                source = source,
                label = "✕",
                labelColor = "§c",
                hover = LocalizedText.key("action.moemusic.queue.remove_hover", track.title.ifBlank { track.id }),
                command = queueRemoveCommand(sourceId, track.id),
            )
        )
        return suffix
    }

    private fun filterActionComponent(
        source: CommandSourceStack,
        label: String,
        labelColor: String,
        hover: LocalizedText,
        command: String,
    ): MutableComponent {
        return commandActionComponent(
            source = source,
            label = label,
            labelColor = labelColor,
            hover = hover,
            command = command,
        )
    }

    private fun applyCommandAction(
        component: MutableComponent,
        source: CommandSourceStack,
        action: CommandActionTarget,
    ): MutableComponent = component.withStyle { style ->
        style
            .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, action.command))
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, localizedComponent(source, action.hover)))
    }

    private fun applyHoverText(
        component: MutableComponent,
        source: CommandSourceStack,
        hover: LocalizedText,
    ): MutableComponent = component.withStyle { style ->
        style.withHoverEvent(
            HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                localizedComponent(source, hover, LocalizedChatRenderer.Tone.FAILURE),
            )
        )
    }

    private fun commandActionComponent(
        source: CommandSourceStack,
        label: String,
        labelColor: String,
        hover: LocalizedText,
        command: String,
    ): MutableComponent {
        return McText.literal("§8[")
            .append(McText.literal("$labelColor$label"))
            .append(McText.literal("§8]"))
            .withStyle { style ->
                style
                    .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, localizedComponent(source, hover)))
            }
    }

    private data class CommandActionTarget(
        val command: String,
        val hover: LocalizedText,
    )

    private fun filterTrackCommand(sourceId: String, trackId: String, note: String?): String {
        val action = if (ContentFilterRuntime.isExactTrackBlocked(sourceId, trackId)) "unban" else "ban"
        val base = "/music filter track $action ${quoteCommandToken(sourceId)} ${quoteCommandToken(trackId)}"
        return appendGreedyCommandTail(base, note)
    }

    internal fun queueRemoveCommand(sourceId: String, trackId: String): String =
        "/music remove ${quoteCommandToken(sourceId)} ${quoteCommandToken(trackId)}"

    internal fun trackSubmitCommand(sourceId: String, trackId: String, mode: TrackAddMode = TrackAddMode.NORMAL): String {
        val base = "/music addById ${quoteCommandToken(sourceId)} ${quoteCommandToken(trackId)}"
        return when (mode) {
            TrackAddMode.NORMAL -> base
            TrackAddMode.SKIP_AUTOPLAY -> "$base --skip-autoplay"
            TrackAddMode.PLAY_NOW -> "$base --now"
        }
    }

    internal fun selectionCommand(sourceId: String, selectionId: String, mode: TrackAddMode = TrackAddMode.NORMAL): String {
        val base = "/music select ${quoteCommandToken(sourceId)} ${quoteCommandToken(selectionId)}"
        return when (mode) {
            TrackAddMode.NORMAL -> base
            TrackAddMode.SKIP_AUTOPLAY -> "$base --skip-autoplay"
            TrackAddMode.PLAY_NOW -> "$base --now"
        }
    }

    private fun queuePlayNowCommand(sourceId: String, trackId: String): String =
        trackSubmitCommand(sourceId, trackId, TrackAddMode.PLAY_NOW)

    private fun filterArtistCommand(sourceId: String, artistId: String, note: String?): String {
        val action = if (ContentFilterRuntime.isExactArtistBlocked(sourceId, artistId)) "unban" else "ban"
        val base = "/music filter artist $action ${quoteCommandToken(sourceId)} ${quoteCommandToken(artistId)}"
        return appendGreedyCommandTail(base, note)
    }

    private fun buildTrackRuleNote(title: String, artistDisplay: String): String? {
        val normalizedTitle = title.trim().takeIf(String::isNotEmpty)
        val normalizedArtist = artistDisplay.trim().takeIf { it.isNotEmpty() && it != "-" }
        return when {
            normalizedTitle != null && normalizedArtist != null -> "$normalizedTitle - $normalizedArtist"
            normalizedTitle != null -> normalizedTitle
            normalizedArtist != null -> normalizedArtist
            else -> null
        }
    }

    private fun buildArtistRuleNote(artistLabel: String): String? =
        artistLabel.trim().takeIf { it.isNotEmpty() && it != "-" }

    private fun primaryArtistQuickTarget(artists: List<ArtistInfo>): Pair<String, String>? =
        artists
            .map { it.effectiveId.trim() to it.displayName.trim().ifBlank { it.effectiveId.trim() } }
            .firstOrNull { it.first.isNotBlank() }

    private fun trackFilterMessage(
        action: ContentFilterRuleAction,
        nowBlocked: Boolean,
        label: String,
        changed: Boolean,
    ): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.track_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.track_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key("action.moemusic.filter.track_unbanned", label)
        else -> LocalizedText.key("action.moemusic.filter.track_already_unbanned", label)
    }

    private fun artistFilterMessage(
        action: ContentFilterRuleAction,
        nowBlocked: Boolean,
        label: String,
        changed: Boolean,
    ): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.artist_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.artist_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key("action.moemusic.filter.artist_unbanned", label)
        else -> LocalizedText.key("action.moemusic.filter.artist_already_unbanned", label)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "?:??"
        val totalSec = ms / 1000
        return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
    }

    private fun commandSearchPageSize(): Int =
        COMMAND_SEARCH_PAGE_SIZE.coerceAtMost(ModConfigManager.config.media.maxSearchResultsPerPage)

    private fun totalPages(total: Int, pageSize: Int): Int =
        if (total <= 0 || pageSize <= 0) 1 else ((total - 1) / pageSize) + 1
}
