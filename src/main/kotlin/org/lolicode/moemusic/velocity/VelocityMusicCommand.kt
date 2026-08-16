package org.lolicode.moemusic.velocity

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ConsoleCommandSource
import com.velocitypowered.api.proxy.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.lolicode.moemusic.api.FilterBlockException
import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.api.SearchableMusicSource
import org.lolicode.moemusic.api.model.ContentFilterRuleAction
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.api.model.directTrackId
import org.lolicode.moemusic.api.model.isAvailable
import org.lolicode.moemusic.api.model.isDirectTrack
import org.lolicode.moemusic.api.model.isSelectable
import org.lolicode.moemusic.api.model.unavailabilityMessage
import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.api.service.IdentifierSubmitOutcome
import org.lolicode.moemusic.api.service.PlaybackAction
import org.lolicode.moemusic.api.service.QueueRemoveResult
import org.lolicode.moemusic.api.service.SelectionSubmitOutcome
import org.lolicode.moemusic.core.MoeMusicCoreBuildInfo
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuleEditor
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.error.UserFacingErrors
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.source.SelectionSessionManager
import org.lolicode.moemusic.core.source.builtin.HttpMusicSource
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Velocity command tree backed by the proxy's native Brigadier dispatcher. */
class VelocityMusicCommand(
    private val plugin: MoeMusicVelocityPlugin,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun close() = scope.cancel()

    fun command(): BrigadierCommand = BrigadierCommand(
        BrigadierCommand.literalArgumentBuilder("music")
            .executes { context -> help(context.source); Command.SINGLE_SUCCESS }
            .then(
                BrigadierCommand.literalArgumentBuilder("help")
                    .executes { context -> help(context.source); Command.SINGLE_SUCCESS },
            )
            .then(
                BrigadierCommand.requiredArgumentBuilder("linkOrId", StringArgumentType.greedyString())
                    .requires { source -> hasPermission(source, PermissionNodes.SUBMIT) }
                    .executes { context -> executeAdd(context, TrackAddMode.NORMAL) },
            )
            .then(addCommandNode())
            .then(addByIdCommandNode())
            .then(selectCommandNode())
            .then(
                BrigadierCommand.literalArgumentBuilder("pause")
                    .requires { source -> hasPermission(source, PermissionNodes.PLAYBACK_CONTROL) }
                    .executes { context -> executeControl(context, PlaybackAction.PAUSE, "action.moemusic.playback.paused") },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("resume")
                    .requires { source -> hasPermission(source, PermissionNodes.PLAYBACK_CONTROL) }
                    .executes { context -> executeControl(context, PlaybackAction.RESUME, "action.moemusic.playback.resumed") },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("skip")
                    .requires(::requiresSkipPermission)
                    .executes { context -> executeControl(context, PlaybackAction.SKIP, "action.moemusic.playback.skipped") },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("next")
                    .requires(::requiresSkipPermission)
                    .executes { context -> executeControl(context, PlaybackAction.SKIP, "action.moemusic.playback.skipped") },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("stop")
                    .requires { source -> hasPermission(source, PermissionNodes.PLAYBACK_CONTROL) }
                    .executes { context -> executeControl(context, PlaybackAction.STOP, "action.moemusic.playback.stopped") },
            )
            .then(queueCommandNode("queue"))
            .then(queueCommandNode("list"))
            .then(choicesCommandNode())
            .then(removeCommandNode())
            .then(searchCommandNode())
            .then(reloadCommandNode())
            .then(filterCommandNode())
            .then(
                BrigadierCommand.literalArgumentBuilder("system")
                    .requires { source -> hasPermission(source, PermissionNodes.SYSTEM_INFO) }
                    .executes { context -> system(context.source); Command.SINGLE_SUCCESS },
            ),
    )

    private fun addCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("add")
            .requires { source -> hasPermission(source, PermissionNodes.SUBMIT) }
            .then(
                BrigadierCommand.requiredArgumentBuilder("linkOrId", StringArgumentType.greedyString())
                    .executes { context -> executeAdd(context, TrackAddMode.NORMAL) },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("--skip-autoplay")
                    .requires { source -> hasPermission(source, PermissionNodes.SUBMIT_SKIP_AUTOPLAY) }
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("linkOrId", StringArgumentType.greedyString())
                            .executes { context -> executeAdd(context, TrackAddMode.SKIP_AUTOPLAY) },
                    ),
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("--now")
                    .requires { source -> hasPermission(source, PermissionNodes.QUEUE_CONTROL) }
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("linkOrId", StringArgumentType.greedyString())
                            .executes { context -> executeAdd(context, TrackAddMode.PLAY_NOW) },
                    ),
            )

    private fun addByIdCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("addById")
            .requires { source -> hasPermission(source, PermissionNodes.SUBMIT) }
            .then(
                sourceArgument().then(
                    BrigadierCommand.requiredArgumentBuilder("trackId", StringArgumentType.string())
                        .executes { context -> executeAddById(context, TrackAddMode.NORMAL) }
                        .then(
                            BrigadierCommand.literalArgumentBuilder("--skip-autoplay")
                                .requires { source -> hasPermission(source, PermissionNodes.SUBMIT_SKIP_AUTOPLAY) }
                                .executes { context -> executeAddById(context, TrackAddMode.SKIP_AUTOPLAY) },
                        )
                        .then(
                            BrigadierCommand.literalArgumentBuilder("--now")
                                .requires { source -> hasPermission(source, PermissionNodes.QUEUE_CONTROL) }
                                .executes { context -> executeAddById(context, TrackAddMode.PLAY_NOW) },
                        ),
                ),
            )

    private fun selectCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("select")
            .requires { source -> hasPermission(source, PermissionNodes.SUBMIT) }
            .then(
                sourceArgument().then(
                    BrigadierCommand.requiredArgumentBuilder("selectionId", StringArgumentType.string())
                        .executes { context -> executeSelect(context, TrackAddMode.NORMAL) }
                        .then(
                            BrigadierCommand.literalArgumentBuilder("--skip-autoplay")
                                .requires { source -> hasPermission(source, PermissionNodes.SUBMIT_SKIP_AUTOPLAY) }
                                .executes { context -> executeSelect(context, TrackAddMode.SKIP_AUTOPLAY) },
                        )
                        .then(
                            BrigadierCommand.literalArgumentBuilder("--now")
                                .requires { source -> hasPermission(source, PermissionNodes.QUEUE_CONTROL) }
                                .executes { context -> executeSelect(context, TrackAddMode.PLAY_NOW) },
                        ),
                ),
            )

    private fun removeCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("remove")
            .requires { source -> hasPermission(source, PermissionNodes.QUEUE_VIEW) }
            .then(
                BrigadierCommand.requiredArgumentBuilder("index", IntegerArgumentType.integer(1))
                    .executes { context ->
                        remove(context.source, listOf(IntegerArgumentType.getInteger(context, "index").toString()))
                        Command.SINGLE_SUCCESS
                    },
            )
            .then(
                sourceArgument().then(
                    BrigadierCommand.requiredArgumentBuilder("trackId", StringArgumentType.string())
                        .executes { context ->
                            remove(
                                context.source,
                                listOf(
                                    StringArgumentType.getString(context, "source"),
                                    StringArgumentType.getString(context, "trackId"),
                                ),
                            )
                            Command.SINGLE_SUCCESS
                        },
                ),
            )

    private fun searchCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("search")
            .requires { source -> hasPermission(source, PermissionNodes.SEARCH) }
            .then(
                BrigadierCommand.requiredArgumentBuilder("query", StringArgumentType.greedyString())
                    .executes { context -> executeSearch(context) },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("--source")
                    .then(
                        sourceArgument(searchableOnly = true)
                            .then(searchQueryNode(withSource = true))
                            .then(
                                BrigadierCommand.literalArgumentBuilder("--page")
                                    .then(
                                        BrigadierCommand.requiredArgumentBuilder("page", IntegerArgumentType.integer(1))
                                            .then(searchQueryNode(withSource = true, withPage = true)),
                                    ),
                            ),
                    ),
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("--page")
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("page", IntegerArgumentType.integer(1))
                            .then(searchQueryNode(withPage = true))
                            .then(
                                BrigadierCommand.literalArgumentBuilder("--source")
                                    .then(
                                        sourceArgument(searchableOnly = true)
                                            .then(searchQueryNode(withSource = true, withPage = true)),
                                    ),
                            ),
                    ),
            )

    private fun queueCommandNode(name: String): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder(name)
            .requires { source -> hasPermission(source, PermissionNodes.QUEUE_VIEW) }
            .then(
                BrigadierCommand.literalArgumentBuilder("--page")
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("page", IntegerArgumentType.integer(1))
                            .executes { context ->
                                queue(context.source, IntegerArgumentType.getInteger(context, "page"))
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                BrigadierCommand.requiredArgumentBuilder("page", IntegerArgumentType.integer(1))
                    .executes { context ->
                        queue(context.source, IntegerArgumentType.getInteger(context, "page"))
                        Command.SINGLE_SUCCESS
                    }
            )
            .executes { context -> queue(context.source, 1); Command.SINGLE_SUCCESS }

    private fun choicesCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("choices")
            .requires { source -> hasPermission(source, PermissionNodes.SUBMIT) }
            .then(
                BrigadierCommand.requiredArgumentBuilder("sessionId", StringArgumentType.string())
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("page", IntegerArgumentType.integer(1))
                            .executes { context ->
                                choicesCommand(
                                    context.source,
                                    StringArgumentType.getString(context, "sessionId"),
                                    IntegerArgumentType.getInteger(context, "page"),
                                )
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .executes { context ->
                        choicesCommand(
                            context.source,
                            StringArgumentType.getString(context, "sessionId"),
                            1,
                        )
                        Command.SINGLE_SUCCESS
                    }
            )

    private fun searchQueryNode(
        withSource: Boolean = false,
        withPage: Boolean = false,
    ) = BrigadierCommand.requiredArgumentBuilder("query", StringArgumentType.greedyString())
        .executes { context -> executeSearch(context, withSource, withPage) }

    private fun reloadCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("reload")
            .requires { source ->
                hasPermission(source, PermissionNodes.CONFIG_RELOAD) ||
                    hasPermission(source, PermissionNodes.CONTENT_FILTER_MANAGE) ||
                    hasPermission(source, PermissionNodes.AUTOPLAY_REFRESH)
            }
            .then(
                BrigadierCommand.literalArgumentBuilder("all")
                    .requires { source -> hasPermission(source, PermissionNodes.CONFIG_RELOAD) }
                    .executes { context -> reload(context.source, listOf("all")); Command.SINGLE_SUCCESS },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("filter")
                    .requires { source -> hasPermission(source, PermissionNodes.CONTENT_FILTER_MANAGE) }
                    .executes { context -> reload(context.source, listOf("filter")); Command.SINGLE_SUCCESS },
            )
            .then(
                BrigadierCommand.literalArgumentBuilder("autoplay")
                    .requires { source -> hasPermission(source, PermissionNodes.AUTOPLAY_REFRESH) }
                    .executes { context -> reload(context.source, listOf("autoplay")); Command.SINGLE_SUCCESS },
            )

    private fun filterCommandNode(): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder("filter")
            .requires { source -> hasPermission(source, PermissionNodes.CONTENT_FILTER_MANAGE) }
            .then(filterKindNode("track"))
            .then(filterKindNode("artist"))

    private fun filterKindNode(kind: String): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder(kind)
            .then(filterActionNode(kind, "ban"))
            .then(filterActionNode(kind, "unban"))
            .then(filterActionNode(kind, "toggle"))

    private fun filterActionNode(kind: String, action: String): LiteralArgumentBuilder<CommandSource> =
        BrigadierCommand.literalArgumentBuilder(action)
            .then(
                sourceArgument().then(
                    BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.string())
                        .executes { context -> executeFilter(context, kind, action, withNote = false) }
                        .then(
                            BrigadierCommand.requiredArgumentBuilder("note", StringArgumentType.greedyString())
                                .executes { context -> executeFilter(context, kind, action, withNote = true) },
                        ),
                ),
            )

    private fun sourceArgument(searchableOnly: Boolean = false) =
        BrigadierCommand.requiredArgumentBuilder("source", StringArgumentType.string())
            .suggests { _, builder -> suggestSourceIds(builder, searchableOnly) }

    private fun suggestSourceIds(
        builder: SuggestionsBuilder,
        searchableOnly: Boolean,
    ): CompletableFuture<Suggestions> {
        val prefix = builder.remaining
        sourceIds(searchableOnly)
            .map(::quoteCommandToken)
            .distinct()
            .filter { it.startsWith(prefix, ignoreCase = true) || unquote(it).startsWith(prefix, ignoreCase = true) }
            .forEach(builder::suggest)
        return builder.buildFuture()
    }

    private fun requiresSkipPermission(source: CommandSource): Boolean = hasSkipPermission(source)

    private fun executeAdd(
        context: com.mojang.brigadier.context.CommandContext<CommandSource>,
        mode: TrackAddMode,
    ): Int {
        add(context.source, listOf(StringArgumentType.getString(context, "linkOrId")).let {
            if (mode == TrackAddMode.NORMAL) it else listOf(mode.flag()) + it
        })
        return Command.SINGLE_SUCCESS
    }

    private fun executeAddById(
        context: com.mojang.brigadier.context.CommandContext<CommandSource>,
        mode: TrackAddMode,
    ): Int {
        addById(
            context.source,
            listOf(
                StringArgumentType.getString(context, "source"),
                StringArgumentType.getString(context, "trackId"),
            ) + mode.flagOrEmpty(),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun executeSelect(
        context: com.mojang.brigadier.context.CommandContext<CommandSource>,
        mode: TrackAddMode,
    ): Int {
        select(
            context.source,
            listOf(
                StringArgumentType.getString(context, "source"),
                StringArgumentType.getString(context, "selectionId"),
            ) + mode.flagOrEmpty(),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun executeControl(
        context: com.mojang.brigadier.context.CommandContext<CommandSource>,
        action: PlaybackAction,
        successKey: String,
    ): Int {
        control(context.source, action, successKey)
        return Command.SINGLE_SUCCESS
    }

    private fun executeSearch(
        context: com.mojang.brigadier.context.CommandContext<CommandSource>,
        withSource: Boolean = false,
        withPage: Boolean = false,
    ): Int {
        val args = buildList {
            if (withSource && withPage) {
                add("--source")
                add(StringArgumentType.getString(context, "source"))
                add("--page")
                add(IntegerArgumentType.getInteger(context, "page").toString())
            } else if (withSource) {
                add("--source")
                add(StringArgumentType.getString(context, "source"))
            } else if (withPage) {
                add("--page")
                add(IntegerArgumentType.getInteger(context, "page").toString())
            }
            add(StringArgumentType.getString(context, "query"))
        }
        search(context.source, args)
        return Command.SINGLE_SUCCESS
    }

    private fun executeFilter(
        context: com.mojang.brigadier.context.CommandContext<CommandSource>,
        kind: String,
        action: String,
        withNote: Boolean,
    ): Int {
        filter(
            context.source,
            listOf(
                kind,
                action,
                StringArgumentType.getString(context, "source"),
                StringArgumentType.getString(context, "id"),
            ) + if (withNote) listOf(StringArgumentType.getString(context, "note")) else emptyList(),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun TrackAddMode.flagOrEmpty(): List<String> = when (this) {
        TrackAddMode.NORMAL -> emptyList()
        TrackAddMode.SKIP_AUTOPLAY -> listOf("--skip-autoplay")
        TrackAddMode.PLAY_NOW -> listOf("--now")
    }

    private fun TrackAddMode.flag(): String = when (this) {
        TrackAddMode.NORMAL -> ""
        TrackAddMode.SKIP_AUTOPLAY -> "--skip-autoplay"
        TrackAddMode.PLAY_NOW -> "--now"
    }

    private fun mode(value: String?): TrackAddMode? = when (value) {
        "--skip-autoplay" -> TrackAddMode.SKIP_AUTOPLAY
        "--now" -> TrackAddMode.PLAY_NOW
        else -> null
    }

    private fun modeAndValue(args: List<String>): Pair<TrackAddMode, String>? {
        val modes = args.mapNotNull(::mode).distinct()
        if (modes.size > 1) return null
        val value = args.filterNot { mode(it) != null }
            .joinToString(" ")
            .let(::unquote)
            .takeIf(String::isNotBlank)
            ?: return null
        return (modes.singleOrNull() ?: TrackAddMode.NORMAL) to value
    }

    private fun add(source: CommandSource, args: List<String>) {
        if (!require(source, PermissionNodes.SUBMIT)) return
        val (mode, value) = modeAndValue(args) ?: return usage(source, "add [--skip-autoplay|--now] <linkOrId>")
        if (!requireMode(source, mode)) return
        val submitter = user(source)
        VelocityChat.success(source, LocalizedText.key("action.moemusic.identifier.resolving"))
        scope.launch {
            try {
                when (val outcome = ServerRuntimeCoordinator.userActionService.submitIdentifier(value, submitter, mode)) {
                    is IdentifierSubmitOutcome.Submitted -> respond(source) { submitted(source, outcome.track) }
                    is IdentifierSubmitOutcome.Choices -> respond(source) { choices(source, outcome.entries) }
                }
            } catch (error: Exception) {
                failLater(source, error)
            }
        }
    }

    private fun addById(source: CommandSource, args: List<String>) {
        if (!require(source, PermissionNodes.SUBMIT)) return
        if (args.size < 2) return usage(source, "addById <source> <trackId> [--skip-autoplay|--now]")
        val trailingMode = mode(args.lastOrNull())
        val selectedArgs = args.drop(1).dropLast(if (trailingMode == null) 0 else 1)
        val sourceId = unquote(args[0])
        val trackId = unquote(selectedArgs.joinToString(" "))
        if (trackId.isBlank()) return VelocityChat.failure(source, LocalizedText.key("error.moemusic.track_id.blank"))
        val selectedMode = trailingMode ?: TrackAddMode.NORMAL
        if (!requireMode(source, selectedMode)) return
        val submitter = user(source)
        VelocityChat.success(source, LocalizedText.key("action.moemusic.track.lookup"))
        scope.launch {
            try {
                val result = ServerRuntimeCoordinator.userActionService.submitBySourceAndId(
                    sourceId,
                    trackId,
                    submitter,
                    selectedMode,
                )
                respond(source) { submitted(source, result.track) }
            } catch (error: Exception) {
                failLater(source, error)
            }
        }
    }

    private fun select(source: CommandSource, args: List<String>) {
        if (!require(source, PermissionNodes.SUBMIT)) return
        if (args.size < 2) return usage(source, "select <source> <selectionId> [--skip-autoplay|--now]")
        val trailingMode = mode(args.lastOrNull())
        val selectedArgs = args.drop(1).dropLast(if (trailingMode == null) 0 else 1)
        val sourceId = unquote(args[0])
        val selectionId = unquote(selectedArgs.joinToString(" "))
        if (selectionId.isBlank()) return VelocityChat.failure(source, LocalizedText.key("error.moemusic.selection.bad_request"))
        val selectedMode = trailingMode ?: TrackAddMode.NORMAL
        if (!requireMode(source, selectedMode)) return
        val submitter = user(source)
        scope.launch {
            try {
                when (val result = ServerRuntimeCoordinator.userActionService.submitBySelection(
                    sourceId,
                    selectionId,
                    submitter,
                    selectedMode,
                )) {
                    is SelectionSubmitOutcome.Submitted -> respond(source) { submitted(source, result.track) }
                    is SelectionSubmitOutcome.Choices -> respond(source) { choices(source, result.entries) }
                }
            } catch (error: Exception) {
                failLater(source, error)
            }
        }
    }

    private fun control(source: CommandSource, action: PlaybackAction, successKey: String) {
        if (action != PlaybackAction.SKIP && !require(source, PermissionNodes.PLAYBACK_CONTROL)) return
        try {
            val outcome = ServerRuntimeCoordinator.userActionService.controlPlayback(action, user(source))
            outcome.failure?.let { VelocityChat.failure(source, it) }
                ?: VelocityChat.success(source, outcome.success ?: LocalizedText.key(successKey))
        } catch (error: Exception) {
            fail(source, error)
        }
    }

    private fun queue(source: CommandSource, page: Int = 1) {
        if (!require(source, PermissionNodes.QUEUE_VIEW)) return
        val current = ServerRuntimeCoordinator.playbackController.currentContext?.track
        val queued = ServerRuntimeCoordinator.queue.userQueueSnapshot()
        if (current == null && queued.isEmpty()) {
            VelocityChat.success(source, LocalizedText.key("action.moemusic.queue.empty"))
            return
        }
        val pageSize = 8
        val total = queued.size
        val totalPages = if (total == 0) 1 else ((total - 1) / pageSize) + 1
        val clampedPage = page.coerceIn(1, totalPages)
        val offset = (clampedPage - 1) * pageSize
        val pageTracks = queued.drop(offset).take(pageSize)
        val lines = buildList {
            if (totalPages > 1) {
                add(prefixed(source, LocalizedText.key("action.moemusic.queue.header_paged", total, clampedPage, totalPages)))
            } else {
                add(prefixed(source, LocalizedText.key("action.moemusic.queue.header", queued.size + if (current == null) 0 else 1)))
            }
            if (clampedPage == 1) {
                current?.let { add(queueLine(source, null, it, true)) }
            }
            pageTracks.forEachIndexed { index, track -> add(queueLine(source, offset + index + 1, track, false)) }
            queuePaginationFooter(source, clampedPage, totalPages)?.let(::add)
        }
        VelocityChat.multiline(source, lines)
    }

    private fun queuePaginationFooter(
        source: CommandSource,
        currentPage: Int,
        totalPages: Int,
    ): Component? {
        if (totalPages <= 1) return null
        var footer = VelocityChat.legacy("  ")
        if (currentPage > 1) {
            footer = footer.append(
                VelocityChat.action(
                    source,
                    "<",
                    "§e",
                    "/music queue --page ${currentPage - 1}",
                    LocalizedText.key("action.moemusic.queue.prev_page"),
                )
            ).append(VelocityChat.legacy(" "))
        }
        footer = footer.append(VelocityChat.legacy("§8(§7$currentPage§8 / §7$totalPages§8)"))
        if (currentPage < totalPages) {
            footer = footer.append(VelocityChat.legacy(" ")).append(
                VelocityChat.action(
                    source,
                    ">",
                    "§e",
                    "/music queue --page ${currentPage + 1}",
                    LocalizedText.key("action.moemusic.queue.next_page"),
                )
            )
        }
        return footer
    }

    private fun remove(source: CommandSource, args: List<String>) {
        if (!require(source, PermissionNodes.QUEUE_VIEW)) return
        if (args.size == 1) {
            val index = args[0].toIntOrNull()
                ?: return VelocityChat.failure(source, LocalizedText.key("error.moemusic.queue.invalid_index"))
            val track = ServerRuntimeCoordinator.queue.userQueueSnapshot().getOrNull(index - 1)
                ?: return VelocityChat.failure(source, LocalizedText.key("error.moemusic.queue.invalid_index"))
            return remove(source, listOf(track.sourceId.orEmpty(), track.id))
        }
        if (args.size < 2) return usage(source, "remove <index> | <source> <trackId>")
        val sourceId = unquote(args[0])
        val trackId = unquote(args.drop(1).joinToString(" "))
        if (sourceId.isBlank() || trackId.isBlank()) {
            return VelocityChat.failure(source, LocalizedText.key("error.moemusic.track.bad_request"))
        }
        val outcome = ServerRuntimeCoordinator.userActionService.removeQueuedTrack(sourceId, trackId, user(source))
        when (outcome.result) {
            QueueRemoveResult.REMOVED -> VelocityChat.success(source, LocalizedText.key("action.moemusic.queue.removed", trackId))
            QueueRemoveResult.NOT_FOUND -> VelocityChat.failure(source, LocalizedText.key("error.moemusic.queue.track_not_found"))
            QueueRemoveResult.FORBIDDEN -> VelocityChat.failure(source, LocalizedText.key("error.moemusic.queue.remove_forbidden"))
            else -> VelocityChat.failure(source, outcome.failure ?: LocalizedText.key("error.moemusic.internal"))
        }
    }

    private fun search(source: CommandSource, args: List<String>) {
        if (!require(source, PermissionNodes.SEARCH)) return
        val parsed = parseSearch(args) ?: return usage(source, "search [--source <source>] [--page <page>] <query>")
        val sources = PluginManager.musicSourceSnapshot()
        if (sources.none { it is SearchableMusicSource }) {
            return VelocityChat.failure(source, LocalizedText.key("error.moemusic.search.no_sources"))
        }
        val selectedSource = parsed.sourceId?.let { requested -> sources.firstOrNull { it.id == requested } }
        if (parsed.sourceId != null && selectedSource == null) {
            return VelocityChat.failure(source, LocalizedText.key("error.moemusic.source.not_found", parsed.sourceId))
        }
        if (selectedSource != null && selectedSource !is SearchableMusicSource) {
            return VelocityChat.failure(source, LocalizedText.key("error.moemusic.source.bad_format"))
        }
        val pageSize = 8.coerceAtMost(ModConfigManager.config.media.maxSearchResultsPerPage)
        val submitter = user(source)
        VelocityChat.success(source, LocalizedText.key("action.moemusic.search.searching", parsed.query))
        scope.launch {
            try {
                val result = ServerRuntimeCoordinator.userActionService.search(
                    SearchQuery(parsed.query, parsed.sourceId, pageSize, (parsed.page - 1) * pageSize),
                    submitter,
                )
                respond(source) {
                    result.failure?.let {
                        VelocityChat.failure(source, it)
                        return@respond
                    }
                    val lines = buildList {
                        if (result.entries.isEmpty()) {
                            add(prefixed(source, LocalizedText.key("action.moemusic.search.no_results")))
                        } else {
                            add(prefixed(source, LocalizedText.key("action.moemusic.search.header", result.entries.size)))
                            val canBypass = hasPermission(source, PermissionNodes.CONTENT_FILTER_BYPASS)
                            val canSeeDetail = hasPermission(source, PermissionNodes.CONTENT_FILTER_MANAGE)
                            result.entries.forEachIndexed { index, entry ->
                                add(selectionLine(source, index, entry, canBypass, canSeeDetail))
                            }
                        }
                        searchFooter(source, parsed, result.sourceId, result.total, pageSize, result.hasMore)?.let(::add)
                    }
                    VelocityChat.multiline(source, lines)
                }
            } catch (error: Exception) {
                failLater(source, error)
            }
        }
    }

    private fun reload(source: CommandSource, args: List<String>) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "all" -> {
                if (!require(source, PermissionNodes.CONFIG_RELOAD)) return
                try {
                    val report = ServerRuntimeCoordinator.reloadServerConfigFromDisk()
                    VelocityChat.multiline(source, buildList {
                        add(prefixed(source, LocalizedText.key("action.moemusic.reload.reloaded")))
                        if (report.pluginConfigsNotified.isNotEmpty()) {
                            add(prefixed(source, LocalizedText.key(
                                "action.moemusic.reload.plugin_notified",
                                report.pluginConfigsNotified.joinToString(", "),
                            )))
                        }
                    })
                    if (report.pluginConfigFailures.isNotEmpty()) {
                        VelocityChat.failure(
                            source,
                            LocalizedText.key(
                                "error.moemusic.reload.plugin_failures",
                                report.pluginConfigFailures.keys.joinToString(", "),
                            ),
                        )
                    }
                } catch (error: IllegalStateException) {
                    VelocityChat.failure(source, LocalizedText.key("error.moemusic.reload.failed", error.message ?: "unknown error"))
                } catch (error: Exception) {
                    fail(source, error)
                }
            }
            "filter" -> {
                if (!require(source, PermissionNodes.CONTENT_FILTER_MANAGE)) return
                try {
                    ContentFilterRuleEditor.reloadFromDisk(ServerRuntimeCoordinator.configDir)
                    VelocityChat.success(source, LocalizedText.key("action.moemusic.filter.reloaded"))
                } catch (error: IllegalStateException) {
                    VelocityChat.failure(source, LocalizedText.key("error.moemusic.reload.failed", error.message ?: "unknown error"))
                } catch (error: Exception) {
                    fail(source, error)
                }
            }
            "autoplay" -> {
                if (!require(source, PermissionNodes.AUTOPLAY_REFRESH)) return
                try {
                    ServerRuntimeCoordinator.refreshAutoplayRuntime()
                    VelocityChat.success(source, LocalizedText.key("action.moemusic.reload.autoplay_reloaded"))
                } catch (error: Exception) {
                    fail(source, error)
                }
            }
            else -> usage(source, "reload <all|filter|autoplay>")
        }
    }

    private fun filter(source: CommandSource, args: List<String>) {
        if (!require(source, PermissionNodes.CONTENT_FILTER_MANAGE)) return
        if (args.size < 4) return usage(source, "filter <track|artist> <ban|unban|toggle> <source> <id> [note]")
        val action = when (args[1].lowercase(Locale.ROOT)) {
            "ban" -> ContentFilterRuleAction.BAN
            "unban" -> ContentFilterRuleAction.UNBAN
            "toggle" -> ContentFilterRuleAction.TOGGLE
            else -> return usage(source, "filter <track|artist> <ban|unban|toggle> <source> <id> [note]")
        }
        val sourceId = unquote(args[2])
        val (id, noteText) = splitCommandToken(args.drop(3).joinToString(" "))
        val note = noteText.takeIf(String::isNotBlank)
        if (sourceId.isBlank() || id.isBlank()) {
            return VelocityChat.failure(source, LocalizedText.key("error.moemusic.track.bad_request"))
        }
        val result = when (args[0].lowercase(Locale.ROOT)) {
            "track" -> ContentFilterRuleEditor.updateTrackRule(sourceId, id, action, note).also {
                if (it.nowBlocked) {
                    val removal = ServerRuntimeCoordinator.playbackController.removeQueuedTrack(sourceId, id, null, true)
                    if (removal == QueueRemoveResult.NOT_FOUND) {
                        ServerRuntimeCoordinator.playbackController.skipIfCurrentTrackMatches(sourceId, id)
                    }
                }
            }
            "artist" -> ContentFilterRuleEditor.updateArtistRules(sourceId, listOf(id), action, note)
            else -> return usage(source, "filter <track|artist> <ban|unban|toggle> <source> <id> [note]")
        }
        VelocityChat.success(
            source,
            if (args[0].equals("track", true)) {
                trackFilterMessage(action, result.nowBlocked, id, result.changed)
            } else {
                artistFilterMessage(action, result.nowBlocked, id, result.changed)
            },
        )
    }

    private fun system(source: CommandSource) {
        if (!require(source, PermissionNodes.SYSTEM_INFO)) return
        val plugins = PluginManager.plugins.sortedBy { it.id.lowercase(Locale.ROOT) }
        val sources = PluginManager.musicSourceSnapshot().sortedBy { it.id.lowercase(Locale.ROOT) }
        VelocityChat.multiline(source, buildList {
            add(prefixed(source, LocalizedText.key(
                "action.moemusic.system.summary",
                plugin.proxy.version.toString(),
                "Velocity",
                MoeMusicApi.API_VERSION,
                MoeMusicCoreBuildInfo.CORE_VERSION,
                "n/a",
                plugin.proxy.version.toString(),
                plugins.size.toString(),
                sources.size.toString(),
            )))
            plugins.forEach { item ->
                add(localized(source, LocalizedText.key(
                    "action.moemusic.system.plugin",
                    VelocityChat.render(source, item.displayName),
                    item.id,
                    item.version,
                    item.configId,
                    localizedBoolean(source, item.configSpec != null),
                ), "  "))
            }
            add(Component.empty())
            sources.forEach { musicSource ->
                val resolver = musicSource is IdentifierResolvableMusicSource
                add(localized(source, LocalizedText.key(
                    "action.moemusic.system.source",
                    VelocityChat.render(source, musicSource.displayName),
                    musicSource.id,
                    localizedBoolean(source, musicSource is SearchableMusicSource),
                    localizedBoolean(source, resolver),
                    localizedBoolean(source, resolver && musicSource.isFallbackResolver),
                ), "  "))
            }
        })
    }

    private fun submitted(source: CommandSource, track: TrackInfo) {
        VelocityChat.success(
            source,
            LocalizedText.key("action.moemusic.track.queued", track.title.ifBlank { track.id.ifBlank { "track" } }),
        )
    }

    private fun choices(source: CommandSource, entries: List<SelectionEntry>) {
        val session = SelectionSessionManager.createSession(
            ownerUserId = user(source)?.id,
            sourceId = entries.firstOrNull()?.sourceId.orEmpty(),
            entries = entries,
        )
        renderChoicesPage(source, entries.take(8), session.id, 1, entries.size, 0, intro = LocalizedText.key("action.moemusic.selection.choose_prompt"))
    }

    private fun choicesCommand(source: CommandSource, sessionId: String, page: Int) {
        if (!require(source, PermissionNodes.SUBMIT)) return
        val u = user(source)
        val bypass = u?.let { hasPermission(source, PermissionNodes.QUEUE_CONTROL) } ?: true
        val session = SelectionSessionManager.getSession(sessionId, u?.id, bypass)
        if (session == null) {
            VelocityChat.failure(source, LocalizedText.key("error.moemusic.selection.session_expired"))
            return
        }
        val total = session.entries.size
        if (total == 0) {
            VelocityChat.failure(source, LocalizedText.key("error.moemusic.selection.session_expired"))
            return
        }
        val pageSize = 8
        val totalPages = ((total - 1) / pageSize) + 1
        val clampedPage = page.coerceIn(1, totalPages)
        val offset = (clampedPage - 1) * pageSize
        val slice = session.entries.drop(offset).take(pageSize)
        renderChoicesPage(source, slice, session.id, clampedPage, total, offset)
    }

    private fun renderChoicesPage(
        source: CommandSource,
        entries: List<SelectionEntry>,
        sessionId: String,
        currentPage: Int,
        totalChoices: Int,
        offset: Int,
        intro: LocalizedText? = null,
    ) {
        val canBypass = hasPermission(source, PermissionNodes.CONTENT_FILTER_BYPASS)
        val canSeeDetail = hasPermission(source, PermissionNodes.CONTENT_FILTER_MANAGE)
        val pageSize = 8
        val totalPages = if (totalChoices == 0) 1 else ((totalChoices - 1) / pageSize) + 1
        VelocityChat.multiline(source, buildList {
            intro?.let { add(prefixed(source, it)) }
            if (totalPages > 1) {
                add(prefixed(source, LocalizedText.key("action.moemusic.selection.header_paged", totalChoices, currentPage, totalPages)))
            } else {
                add(prefixed(source, LocalizedText.key("action.moemusic.selection.header", totalChoices)))
            }
            entries.forEachIndexed { index, entry -> add(selectionLine(source, offset + index, entry, canBypass, canSeeDetail)) }
            if (totalPages > 1) {
                choicesPaginationFooter(source, sessionId, currentPage, totalPages)?.let(::add)
            }
        })
    }

    private fun choicesPaginationFooter(
        source: CommandSource,
        sessionId: String,
        currentPage: Int,
        totalPages: Int,
    ): Component? {
        if (totalPages <= 1) return null
        var footer = VelocityChat.legacy("  ")
        if (currentPage > 1) {
            footer = footer.append(
                VelocityChat.action(
                    source,
                    "<",
                    "§e",
                    "/music choices \"$sessionId\" ${currentPage - 1}",
                    LocalizedText.key("action.moemusic.selection.prev_page"),
                )
            ).append(VelocityChat.legacy(" "))
        }
        footer = footer.append(VelocityChat.legacy("§8(§7$currentPage§8 / §7$totalPages§8)"))
        if (currentPage < totalPages) {
            footer = footer.append(VelocityChat.legacy(" ")).append(
                VelocityChat.action(
                    source,
                    ">",
                    "§e",
                    "/music choices \"$sessionId\" ${currentPage + 1}",
                    LocalizedText.key("action.moemusic.selection.next_page"),
                )
            )
        }
        return footer
    }

    private fun queueLine(source: CommandSource, index: Int?, track: TrackInfo, current: Boolean): Component {
        val title = track.title.ifBlank { track.id.ifBlank { "-" } }.take(72)
        val prefix = if (current) {
            "§6${VelocityChat.render(source, LocalizedText.key("screen.moemusic.queue.now_playing"))}§8: "
        } else {
            "§e${requireNotNull(index)}. "
        }
        val titleColor = if (current) "§6" else if (track.isAvailable) "§b" else "§7"
        var line = if (track.isAvailable) {
            VelocityChat.legacy("  $prefix$titleColor$title")
        } else {
            VelocityChat.hoverable(source, "  $prefix$titleColor$title", track.unavailabilityMessage(), VelocityChatFormatting.Tone.FAILURE)
        }
        val trackSourceId = track.sourceId
        if (!current && trackSourceId != null && track.id.isNotBlank()) {
            line = line.append(Component.text(" ")).append(
                VelocityChat.action(
                    source,
                    "✕",
                    "§c",
                    queueRemoveCommand(trackSourceId, track.id),
                    LocalizedText.key("action.moemusic.queue.remove_hover", title),
                ),
            )
        }
        val meta = listOfNotNull(
            track.artistDisplay.takeIf(String::isNotBlank),
            formatDuration(track.durationMs).takeIf { track.durationMs > 0 },
            track.submittedByUserName?.takeIf(String::isNotBlank)?.let { "@$it" },
            sourceDisplayName(source, track.sourceId).takeIf(String::isNotBlank),
        ).joinToString(" §8· ")
        return line.append(VelocityChat.legacy("\n  §7$meta"))
    }

    private fun selectionLine(
        source: CommandSource,
        index: Int,
        entry: SelectionEntry,
        canBypassFilter: Boolean,
        canSeeFilterDetail: Boolean,
    ): Component {
        val filterReason = if (canBypassFilter) null else when (val verdict = ContentFilterRuntime.selectionFilterVerdict(entry)) {
            FilterVerdict.Allow -> null
            is FilterVerdict.Reject -> if (canSeeFilterDetail) verdict.reason
            else LocalizedText.key("error.moemusic.content_filter.managed")
        }
        val selectable = entry.isSelectable && filterReason == null
        val unavailable = filterReason ?: entry.unavailabilityMessage()
        val action = if (selectable && hasPermission(source, PermissionNodes.SUBMIT)) {
            selectionActionTarget(entry, TrackAddMode.NORMAL)
        } else {
            null
        }
        val title = entry.title.ifBlank { "-" }.take(64)
        var line = when {
            action != null -> VelocityChat.clickable(source, "  §e${index + 1}. §b$title", action.command, action.hover)
            !selectable -> VelocityChat.hoverable(source, "  §e${index + 1}. §7$title", unavailable, VelocityChatFormatting.Tone.FAILURE)
            else -> VelocityChat.legacy("  §e${index + 1}. §b$title")
        }
        if (action != null) {
            line = line.append(Component.text(" ")).append(
                VelocityChat.action(source, "+", "§a", action.command, action.hover),
            )
            if (hasPermission(source, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)) {
                val skip = selectionActionTarget(entry, TrackAddMode.SKIP_AUTOPLAY)
                line = line.append(VelocityChat.action(source, "»", "§e", skip.command, skip.hover))
            }
            if (canPlayNow(source)) {
                val now = selectionActionTarget(entry, TrackAddMode.PLAY_NOW)
                line = line.append(VelocityChat.action(source, "▶", "§c", now.command, now.hover))
            }
        }
        val meta = listOfNotNull(
            entry.artistDisplay.takeIf(String::isNotBlank),
            if (selectable) entry.album?.takeIf(String::isNotBlank) else VelocityChat.render(source, unavailable),
            formatDuration(entry.durationMs).takeIf { entry.durationMs > 0 },
        ).joinToString(" §8· ")
        return line.append(VelocityChat.legacy("\n  §7$meta"))
    }

    private fun searchFooter(
        source: CommandSource,
        parsed: ParsedSearch,
        sourceId: String,
        total: Int,
        pageSize: Int,
        hasMore: Boolean,
    ): Component? {
        if (total <= 0 || pageSize <= 0) return null
        val selectedSource = sourceId.ifBlank { parsed.sourceId.orEmpty() }
        var line = VelocityChat.legacy("  ")
        if (parsed.page > 1) {
            line = line.append(VelocityChat.action(
                source,
                "<",
                "§e",
                searchCommand(parsed.query, selectedSource, parsed.page - 1),
                LocalizedText.key("action.moemusic.search.prev_page"),
            )).append(Component.text(" "))
        }
        line = line.append(VelocityChat.legacy("§8(§7${parsed.page}§8 / §7${totalPages(total, pageSize)}§8)"))
        sourceDisplayName(source, selectedSource).takeIf(String::isNotBlank)?.let {
            line = line.append(VelocityChat.legacy(" §8· §3$it"))
        }
        if (hasMore) {
            line = line.append(Component.text(" ")).append(VelocityChat.action(
                source,
                ">",
                "§e",
                searchCommand(parsed.query, selectedSource, parsed.page + 1),
                LocalizedText.key("action.moemusic.search.next_page"),
            ))
        }
        return line
    }

    private fun selectionActionTarget(entry: SelectionEntry, mode: TrackAddMode): CommandActionTarget {
        val sourceId = entry.sourceId ?: HttpMusicSource.id
        val command = entry.directTrackId?.let { trackSubmitCommand(sourceId, it, mode) }
            ?: selectCommand(sourceId, entry.selectionId, mode)
        val key = when (mode) {
            TrackAddMode.NORMAL -> if (entry.isDirectTrack) "action.moemusic.search.click_to_queue" else "action.moemusic.search.click_to_select"
            TrackAddMode.SKIP_AUTOPLAY -> if (entry.isDirectTrack) "action.moemusic.search.click_to_queue_skip_autoplay" else "action.moemusic.search.click_to_select_skip_autoplay"
            TrackAddMode.PLAY_NOW -> if (entry.isDirectTrack) "action.moemusic.search.click_to_play_now" else "action.moemusic.search.click_to_select_play_now"
        }
        return CommandActionTarget(command, LocalizedText.key(key, entry.title))
    }

    private fun localized(source: CommandSource, text: LocalizedText, prefix: String = ""): Component =
        VelocityChat.legacy(prefix + VelocityChatFormatting.render(VelocityChat.locale(source), text))

    private fun prefixed(source: CommandSource, text: LocalizedText): Component =
        VelocityChat.legacy(VelocityChatFormatting.prefixed(VelocityChat.locale(source), text, VelocityChatFormatting.Tone.SUCCESS))

    private fun localizedBoolean(source: CommandSource, value: Boolean): String =
        VelocityChat.render(source, LocalizedText.key(if (value) "label.moemusic.yes" else "label.moemusic.no"))

    private fun sourceDisplayName(source: CommandSource, sourceId: String?): String {
        if (sourceId.isNullOrBlank()) return ""
        val musicSource = PluginManager.musicSourceSnapshot().firstOrNull { it.id == sourceId } ?: return sourceId
        return VelocityChat.render(source, musicSource.displayName)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "?:??"
        val totalSeconds = ms / 1000
        return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    }

    private fun totalPages(total: Int, pageSize: Int): Int =
        if (total <= 0 || pageSize <= 0) 1 else ((total - 1) / pageSize) + 1

    private data class CommandActionTarget(val command: String, val hover: LocalizedText)

    private fun help(source: CommandSource) {
        VelocityChat.plain(source, "/music add <link> | addById <source> <id> | search <query> | queue")
        VelocityChat.plain(source, "/music pause | resume | skip | stop | remove <index> | system")
        if (hasPermission(source, PermissionNodes.CONFIG_RELOAD)) {
            VelocityChat.plain(source, "/music reload <all|filter|autoplay> | filter <track|artist> <ban|unban|toggle> ...")
        }
    }

    private fun require(source: CommandSource, node: PermissionNodes.Node): Boolean {
        if (hasPermission(source, node)) return true
        VelocityChat.failure(source, node.deniedMessage)
        return false
    }

    private fun hasPermission(source: CommandSource, node: PermissionNodes.Node): Boolean = when (source) {
        is Player -> VelocityUser.snapshot(source).hasPermission(node.id, node.defaultLevel())
        is ConsoleCommandSource -> true
        else -> source.hasPermission(node.id)
    }

    private fun hasSkipPermission(source: CommandSource): Boolean =
        hasPermission(source, PermissionNodes.QUEUE_CONTROL) ||
            (source is Player && hasPermission(source, PermissionNodes.VOTE))

    private fun canPlayNow(source: CommandSource): Boolean =
        hasPermission(source, PermissionNodes.SUBMIT) && hasPermission(source, PermissionNodes.QUEUE_CONTROL)

    private fun requireMode(source: CommandSource, mode: TrackAddMode): Boolean = when (mode) {
        TrackAddMode.NORMAL -> true
        TrackAddMode.SKIP_AUTOPLAY -> require(source, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)
        TrackAddMode.PLAY_NOW -> require(source, PermissionNodes.QUEUE_CONTROL)
    }

    private fun user(source: CommandSource): VelocityUser? = (source as? Player)?.let(VelocityUser::snapshot)

    private fun respond(source: CommandSource, block: () -> Unit) {
        plugin.runOnProxyThread {
            if (source is Player && plugin.proxy.getPlayer(source.uniqueId).isEmpty) return@runOnProxyThread
            block()
        }
    }

    private fun failLater(source: CommandSource, error: Exception) = respond(source) { fail(source, error) }

    private fun fail(source: CommandSource, error: Exception) {
        if (UserFacingErrors.isExpected(error)) {
            plugin.logger.debug("MoeMusic command rejected: {}", error.message)
        } else {
            plugin.logger.warn("MoeMusic command failed: {}", error.message, error)
        }
        VelocityChat.failure(source, classify(source, error))
    }

    private fun classify(source: CommandSource, error: Exception): LocalizedText =
        if (error is FilterBlockException && !hasPermission(source, PermissionNodes.CONTENT_FILTER_MANAGE)) {
            error.maskedReason
        } else {
            UserFacingErrors.classify(error)
        }

    private fun usage(source: CommandSource, suffix: String) {
        VelocityChat.plain(source, "Usage: /music $suffix")
    }

    private fun selectCommand(sourceId: String, selectionId: String, mode: TrackAddMode): String {
        val base = "/music select ${quoteCommandToken(sourceId)} ${quoteCommandToken(selectionId)}"
        return when (mode) {
            TrackAddMode.NORMAL -> base
            TrackAddMode.SKIP_AUTOPLAY -> "$base --skip-autoplay"
            TrackAddMode.PLAY_NOW -> "$base --now"
        }
    }

    private fun trackSubmitCommand(sourceId: String, trackId: String, mode: TrackAddMode): String {
        val base = "/music addById ${quoteCommandToken(sourceId)} ${quoteCommandToken(trackId)}"
        return when (mode) {
            TrackAddMode.NORMAL -> base
            TrackAddMode.SKIP_AUTOPLAY -> "$base --skip-autoplay"
            TrackAddMode.PLAY_NOW -> "$base --now"
        }
    }

    private fun queueRemoveCommand(sourceId: String, trackId: String): String =
        "/music remove ${quoteCommandToken(sourceId)} ${quoteCommandToken(trackId)}"

    private fun searchCommand(query: String, sourceId: String? = null, page: Int = 1): String {
        val base = buildString {
            append("/music search")
            sourceId?.trim()?.takeIf(String::isNotEmpty)?.let {
                append(" --source ").append(quoteCommandToken(it))
            }
            if (page > 1) append(" --page ").append(page)
        }
        return query.trim().takeIf(String::isNotEmpty)?.let { "$base $it" } ?: base
    }

    private fun trackFilterMessage(action: ContentFilterRuleAction, nowBlocked: Boolean, label: String, changed: Boolean): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.track_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.track_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key("action.moemusic.filter.track_unbanned", label)
        else -> LocalizedText.key("action.moemusic.filter.track_already_unbanned", label)
    }

    private fun artistFilterMessage(action: ContentFilterRuleAction, nowBlocked: Boolean, label: String, changed: Boolean): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.artist_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.artist_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key("action.moemusic.filter.artist_unbanned", label)
        else -> LocalizedText.key("action.moemusic.filter.artist_already_unbanned", label)
    }

    private fun quoteCommandToken(value: String): String = if (
        value.isNotEmpty() && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' || it == '+' }
    ) value else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun splitCommandToken(value: String): Pair<String, String> {
        val trimmed = value.trim()
        if (!trimmed.startsWith('"')) {
            val separator = trimmed.indexOf(' ')
            return if (separator < 0) trimmed to "" else trimmed.substring(0, separator) to trimmed.substring(separator + 1).trim()
        }
        val token = StringBuilder()
        var escaped = false
        for (index in 1 until trimmed.length) {
            val char = trimmed[index]
            if (escaped) {
                token.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return token.toString() to trimmed.substring(index + 1).trim()
            } else {
                token.append(char)
            }
        }
        return unquote(trimmed) to ""
    }

    private fun sourceIds(searchableOnly: Boolean): List<String> = PluginManager.musicSourceSnapshot()
        .filter { !searchableOnly || it is SearchableMusicSource }
        .map { it.id.trim() }
        .filter(String::isNotEmpty)
        .distinct()

    companion object {
        internal data class ParsedSearch(val sourceId: String?, val page: Int, val query: String)

        internal fun parseSearch(args: List<String>): ParsedSearch? {
            var sourceId: String? = null
            var page = 1
            var index = 0
            when (args.firstOrNull()) {
                "--source" -> {
                    sourceId = args.getOrNull(1)?.let(::unquote) ?: return null
                    index = 2
                    if (args.getOrNull(index) == "--page") {
                        page = args.getOrNull(index + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                        index += 2
                    }
                }
                "--page" -> {
                    page = args.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                    index = 2
                    if (args.getOrNull(index) == "--source") {
                        sourceId = args.getOrNull(index + 1)?.let(::unquote) ?: return null
                        index += 2
                    }
                }
            }
            return args.drop(index).joinToString(" ").trim()
                .takeIf(String::isNotEmpty)
                ?.let { ParsedSearch(sourceId, page, it) }
        }

        private fun unquote(value: String): String {
            val trimmed = value.trim()
            if (trimmed.length < 2 || trimmed.first() != '"' || trimmed.last() != '"') return trimmed
            return trimmed.substring(1, trimmed.lastIndex)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }
}
