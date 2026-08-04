package org.lolicode.moemusic.spigot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.lolicode.moemusic.api.FilterBlockException
import org.lolicode.moemusic.api.IdentifierResolvableMusicSource
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicApi
import org.lolicode.moemusic.api.SearchableMusicSource
import org.lolicode.moemusic.api.model.ContentFilterRuleAction
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.api.model.directTrackId
import org.lolicode.moemusic.api.model.isDirectTrack
import org.lolicode.moemusic.api.model.isAvailable
import org.lolicode.moemusic.api.model.isSelectable
import org.lolicode.moemusic.api.model.unavailabilityMessage
import org.lolicode.moemusic.api.service.IdentifierSubmitOutcome
import org.lolicode.moemusic.api.service.FilterVerdict
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
import org.lolicode.moemusic.core.source.builtin.HttpMusicSource
import java.util.Locale

class MusicCommand(
    private val plugin: MoeMusicPlugin,
) : CommandExecutor, TabCompleter {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun close() = scope.cancel()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args[0].equals("help", true)) {
            help(sender)
            return true
        }

        when (args[0].lowercase(Locale.ROOT)) {
            "add" -> add(sender, args.drop(1))
            "addbyid" -> addById(sender, args.drop(1))
            "select" -> select(sender, args.drop(1))
            "pause" -> control(sender, PlaybackAction.PAUSE, "action.moemusic.playback.paused")
            "resume" -> control(sender, PlaybackAction.RESUME, "action.moemusic.playback.resumed")
            "skip", "next" -> control(sender, PlaybackAction.SKIP, "action.moemusic.playback.skipped")
            "stop" -> control(sender, PlaybackAction.STOP, "action.moemusic.playback.stopped")
            "queue", "list" -> queue(sender)
            "remove" -> remove(sender, args.drop(1))
            "search" -> search(sender, args.drop(1))
            "reload" -> reload(sender, args.drop(1))
            "filter" -> filter(sender, args.drop(1))
            "system" -> system(sender)
            else -> add(sender, args.toList())
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val candidates = when {
            args.size == 1 -> rootSuggestions(sender)
            args[0].equals("add", true) && args.size == 2 -> buildList {
                if (hasPermission(sender, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)) add("--skip-autoplay")
                if (hasPermission(sender, PermissionNodes.QUEUE_CONTROL)) add("--now")
            }
            args[0].equals("addbyid", true) && args.size == 2 -> sourceIds(false)
            args[0].equals("addbyid", true) && args.size == 4 -> modeSuggestions(sender)
            args[0].equals("select", true) && args.size == 2 -> sourceIds(false)
            args[0].equals("select", true) && args.size == 4 -> modeSuggestions(sender)
            args[0].equals("search", true) -> searchSuggestions(args)
            args[0].equals("remove", true) && args.size == 2 -> sourceIds(false)
            args[0].equals("reload", true) && args.size == 2 -> buildList {
                if (hasPermission(sender, PermissionNodes.CONFIG_RELOAD)) add("all")
                if (hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE)) add("filter")
                if (hasPermission(sender, PermissionNodes.AUTOPLAY_REFRESH)) add("autoplay")
            }
            args[0].equals("filter", true) && args.size == 2 && hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) -> listOf("track", "artist")
            args[0].equals("filter", true) && args.size == 3 && hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) -> listOf("ban", "unban", "toggle")
            args[0].equals("filter", true) && args.size == 4 && hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) -> sourceIds(false)
            else -> emptyList()
        }
        val prefix = args.lastOrNull().orEmpty()
        return candidates
            .map(::quoteCommandToken)
            .distinct()
            .filter {
                it.startsWith(prefix, ignoreCase = true) ||
                    unquote(it).startsWith(prefix, ignoreCase = true)
            }
    }

    private fun rootSuggestions(sender: CommandSender): List<String> = buildList {
        if (hasPermission(sender, PermissionNodes.SUBMIT)) addAll(listOf("add", "addById", "select"))
        if (hasPermission(sender, PermissionNodes.SEARCH)) add("search")
        if (hasPermission(sender, PermissionNodes.QUEUE_VIEW)) addAll(listOf("queue", "list", "remove"))
        if (hasPermission(sender, PermissionNodes.PLAYBACK_CONTROL)) addAll(listOf("pause", "resume", "stop"))
        if (hasSkipPermission(sender)) addAll(listOf("skip", "next"))
        if (hasPermission(sender, PermissionNodes.SYSTEM_INFO)) add("system")
        if (hasPermission(sender, PermissionNodes.CONFIG_RELOAD) ||
            hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) ||
            hasPermission(sender, PermissionNodes.AUTOPLAY_REFRESH)
        ) add("reload")
        if (hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE)) add("filter")
    }

    private fun searchSuggestions(args: Array<out String>): List<String> = when {
        args.size == 2 -> listOf("--source", "--page")
        args.size == 3 && args[1].equals("--source", true) -> sourceIds(true)
        args.size == 4 && args[1].equals("--source", true) -> listOf("--page")
        args.size == 4 && args[1].equals("--page", true) -> listOf("--source")
        args.size == 5 && args[1].equals("--page", true) && args[3].equals("--source", true) -> sourceIds(true)
        args.size == 5 && args[1].equals("--source", true) && args[3].equals("--page", true) -> emptyList()
        else -> emptyList()
    }

    private fun modeSuggestions(sender: CommandSender): List<String> = buildList {
        if (hasPermission(sender, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)) add("--skip-autoplay")
        if (hasPermission(sender, PermissionNodes.QUEUE_CONTROL)) add("--now")
    }

    private fun add(sender: CommandSender, args: List<String>) {
        if (!require(sender, PermissionNodes.SUBMIT)) return
        val (mode, value) = modeAndValue(args) ?: return usage(sender, "add [--skip-autoplay|--now] <linkOrId>")
        if (!requireMode(sender, mode)) return
        val submitter = user(sender)
        Chat.success(sender, LocalizedText.key("action.moemusic.identifier.resolving"))
        scope.launch {
            try {
                when (val outcome = ServerRuntimeCoordinator.userActionService.submitIdentifier(value, submitter, mode)) {
                    is IdentifierSubmitOutcome.Submitted -> respond(sender) { submitted(sender, outcome.track, outcome.result) }
                    is IdentifierSubmitOutcome.Choices -> respond(sender) { choices(sender, outcome.entries, outcome.sourceId) }
                }
            } catch (error: Exception) {
                failLater(sender, error)
            }
        }
    }

    private fun addById(sender: CommandSender, args: List<String>) {
        if (!require(sender, PermissionNodes.SUBMIT)) return
        if (args.size < 2) return usage(sender, "addById <source> <trackId> [--skip-autoplay|--now]")
        val trailingMode = mode(args.lastOrNull())
        val selectedArgs = args.drop(1).dropLast(if (trailingMode == null) 0 else 1)
        val sourceId = unquote(args[0])
        val trackId = unquote(selectedArgs.joinToString(" "))
        if (trackId.isBlank()) return Chat.failure(sender, LocalizedText.key("error.moemusic.track_id.blank"))
        val mode = trailingMode ?: TrackAddMode.NORMAL
        if (!requireMode(sender, mode)) return
        val submitter = user(sender)
        Chat.success(sender, LocalizedText.key("action.moemusic.track.lookup"))
        scope.launch {
            try {
                val result = ServerRuntimeCoordinator.userActionService.submitBySourceAndId(sourceId, trackId, submitter, mode)
                respond(sender) { submitted(sender, result.track, result.result) }
            } catch (error: Exception) {
                failLater(sender, error)
            }
        }
    }

    private fun select(sender: CommandSender, args: List<String>) {
        if (!require(sender, PermissionNodes.SUBMIT)) return
        if (args.size < 2) return usage(sender, "select <source> <selectionId> [--skip-autoplay|--now]")
        val trailingMode = mode(args.lastOrNull())
        val selectedArgs = args.drop(1).dropLast(if (trailingMode == null) 0 else 1)
        val sourceId = unquote(args[0])
        val selectionId = unquote(selectedArgs.joinToString(" "))
        if (selectionId.isBlank()) return Chat.failure(sender, LocalizedText.key("error.moemusic.selection.bad_request"))
        val mode = trailingMode ?: TrackAddMode.NORMAL
        if (!requireMode(sender, mode)) return
        val submitter = user(sender)
        scope.launch {
            try {
                when (val result = ServerRuntimeCoordinator.userActionService.submitBySelection(sourceId, selectionId, submitter, mode)) {
                    is SelectionSubmitOutcome.Submitted -> respond(sender) { submitted(sender, result.track, result.result) }
                    is SelectionSubmitOutcome.Choices -> respond(sender) { choices(sender, result.entries, sourceId) }
                }
            } catch (error: Exception) {
                failLater(sender, error)
            }
        }
    }

    private fun control(sender: CommandSender, action: PlaybackAction, successKey: String) {
        val node = if (action == PlaybackAction.SKIP) null else PermissionNodes.PLAYBACK_CONTROL
        if (node != null && !require(sender, node)) return
        try {
            val outcome = ServerRuntimeCoordinator.userActionService.controlPlayback(action, user(sender))
            outcome.failure?.let { Chat.failure(sender, it) }
                ?: Chat.success(sender, outcome.success ?: LocalizedText.key(successKey))
        } catch (error: Exception) {
            fail(sender, error)
        }
    }

    private fun queue(sender: CommandSender) {
        if (!require(sender, PermissionNodes.QUEUE_VIEW)) return
        val current = ServerRuntimeCoordinator.playbackController.currentContext?.track
        val queued = ServerRuntimeCoordinator.queue.userQueueSnapshot()
        if (current == null && queued.isEmpty()) return Chat.success(sender, LocalizedText.key("action.moemusic.queue.empty"))
        Chat.multiline(
            sender,
            buildList {
                add(Chat.legacy(SpigotChatFormatting.prefixed(
                    Chat.locale(sender),
                    LocalizedText.key("action.moemusic.queue.header", queued.size + if (current == null) 0 else 1),
                    SpigotChatFormatting.Tone.SUCCESS,
                )))
                current?.let { add(queueTrackLine(sender, null, it, isCurrent = true)) }
                queued.forEachIndexed { index, track -> add(queueTrackLine(sender, index + 1, track, isCurrent = false)) }
            },
        )
    }

    private fun remove(sender: CommandSender, args: List<String>) {
        if (!require(sender, PermissionNodes.QUEUE_VIEW)) return
        if (args.size == 1) {
            val index = args[0].toIntOrNull() ?: return Chat.failure(sender, LocalizedText.key("error.moemusic.queue.invalid_index"))
            val track = ServerRuntimeCoordinator.queue.userQueueSnapshot().getOrNull(index - 1)
                ?: return Chat.failure(sender, LocalizedText.key("error.moemusic.queue.invalid_index"))
            return remove(sender, listOf(track.sourceId.orEmpty(), track.id))
        }
        if (args.size < 2) return usage(sender, "remove <index> | <source> <trackId>")
        val sourceId = unquote(args[0])
        val trackId = args.drop(1).joinToString(" ").let(::unquote)
        if (sourceId.isBlank() || trackId.isBlank()) return Chat.failure(sender, LocalizedText.key("error.moemusic.track.bad_request"))
        val outcome = ServerRuntimeCoordinator.userActionService.removeQueuedTrack(sourceId, trackId, user(sender))
        when (outcome.result) {
            QueueRemoveResult.REMOVED -> Chat.success(sender, LocalizedText.key("action.moemusic.queue.removed", trackId))
            QueueRemoveResult.NOT_FOUND -> Chat.failure(sender, LocalizedText.key("error.moemusic.queue.track_not_found"))
            QueueRemoveResult.FORBIDDEN -> Chat.failure(sender, LocalizedText.key("error.moemusic.queue.remove_forbidden"))
            else -> Chat.failure(sender, outcome.failure ?: LocalizedText.key("error.moemusic.internal"))
        }
    }

    private fun search(sender: CommandSender, args: List<String>) {
        if (!require(sender, PermissionNodes.SEARCH)) return
        val parsed = parseSearch(args) ?: return usage(sender, "search [--source <source>] [--page <page>] <query>")
        val sources = PluginManager.musicSourceSnapshot()
        if (sources.none { it is SearchableMusicSource }) {
            return Chat.failure(sender, LocalizedText.key("error.moemusic.search.no_sources"))
        }
        val selectedSource = parsed.sourceId?.let { requested -> sources.firstOrNull { it.id == requested } }
        if (parsed.sourceId != null && selectedSource == null) {
            return Chat.failure(sender, LocalizedText.key("error.moemusic.source.not_found", parsed.sourceId))
        }
        if (selectedSource != null && selectedSource !is SearchableMusicSource) {
            return Chat.failure(sender, LocalizedText.key("error.moemusic.source.bad_format"))
        }
        val pageSize = 8.coerceAtMost(ModConfigManager.config.media.maxSearchResultsPerPage)
        val submitter = user(sender)
        Chat.success(sender, LocalizedText.key("action.moemusic.search.searching", parsed.query))
        scope.launch {
            try {
                val result = ServerRuntimeCoordinator.userActionService.search(
                    SearchQuery(parsed.query, parsed.sourceId, pageSize, (parsed.page - 1) * pageSize),
                    submitter,
                )
                respond(sender) {
                    result.failure?.let { return@respond Chat.failure(sender, it) }
                    val lines = buildList {
                        if (result.entries.isEmpty()) {
                            add(Chat.legacy(SpigotChatFormatting.prefixed(
                                Chat.locale(sender),
                                LocalizedText.key("action.moemusic.search.no_results"),
                                SpigotChatFormatting.Tone.SUCCESS,
                            )))
                        } else {
                            add(Chat.legacy(SpigotChatFormatting.prefixed(
                                Chat.locale(sender),
                                LocalizedText.key("action.moemusic.search.header", result.entries.size),
                                SpigotChatFormatting.Tone.SUCCESS,
                            )))
                            val canBypassFilter = hasPermission(sender, PermissionNodes.CONTENT_FILTER_BYPASS)
                            val canSeeFilterDetail = hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE)
                            result.entries.forEachIndexed { index, entry ->
                                add(selectionChoiceLine(sender, index, entry, canBypassFilter, canSeeFilterDetail))
                            }
                        }
                        searchPaginationFooter(sender, parsed, result.sourceId, result.total, pageSize, result.hasMore)?.let(::add)
                    }
                    Chat.multiline(sender, lines)
                }
            } catch (error: Exception) {
                failLater(sender, error)
            }
        }
    }

    private fun reload(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "all" -> {
                if (!require(sender, PermissionNodes.CONFIG_RELOAD)) return
                try {
                    val report = ServerRuntimeCoordinator.reloadServerConfigFromDisk()
                    Chat.multiline(sender, buildList {
                        add(prefixed(sender, LocalizedText.key("action.moemusic.reload.reloaded")))
                        if (report.pluginConfigsNotified.isNotEmpty()) {
                            add(prefixed(sender, LocalizedText.key(
                                "action.moemusic.reload.plugin_notified",
                                report.pluginConfigsNotified.joinToString(", "),
                            )))
                        }
                    })
                    if (report.pluginConfigFailures.isNotEmpty()) {
                        Chat.failure(sender, LocalizedText.key(
                            "error.moemusic.reload.plugin_failures",
                            report.pluginConfigFailures.keys.joinToString(", "),
                        ))
                    }
                } catch (error: IllegalStateException) {
                    Chat.failure(sender, LocalizedText.key("error.moemusic.reload.failed", error.message ?: "unknown error"))
                } catch (error: Exception) {
                    fail(sender, error)
                }
            }
            "filter" -> {
                if (!require(sender, PermissionNodes.CONTENT_FILTER_MANAGE)) return
                try {
                    ContentFilterRuleEditor.reloadFromDisk(ServerRuntimeCoordinator.configDir)
                    Chat.success(sender, LocalizedText.key("action.moemusic.filter.reloaded"))
                } catch (error: IllegalStateException) {
                    Chat.failure(sender, LocalizedText.key("error.moemusic.reload.failed", error.message ?: "unknown error"))
                } catch (error: Exception) {
                    fail(sender, error)
                }
            }
            "autoplay" -> {
                if (!require(sender, PermissionNodes.AUTOPLAY_REFRESH)) return
                try {
                    ServerRuntimeCoordinator.refreshAutoplayRuntime()
                    Chat.success(sender, LocalizedText.key("action.moemusic.reload.autoplay_reloaded"))
                } catch (error: Exception) {
                    fail(sender, error)
                }
            }
            else -> usage(sender, "reload <all|filter|autoplay>")
        }
    }

    private fun filter(sender: CommandSender, args: List<String>) {
        if (!require(sender, PermissionNodes.CONTENT_FILTER_MANAGE)) return
        if (args.size < 4) return usage(sender, "filter <track|artist> <ban|unban|toggle> <source> <id> [note]")
        val action = when (args[1].lowercase(Locale.ROOT)) {
            "ban" -> ContentFilterRuleAction.BAN
            "unban" -> ContentFilterRuleAction.UNBAN
            "toggle" -> ContentFilterRuleAction.TOGGLE
            else -> return usage(sender, "filter <track|artist> <ban|unban|toggle> <source> <id> [note]")
        }
        val sourceId = unquote(args[2])
        val (id, noteText) = splitCommandToken(args.drop(3).joinToString(" "))
        val note = noteText.takeIf(String::isNotBlank)
        if (sourceId.isBlank() || id.isBlank()) return Chat.failure(sender, LocalizedText.key("error.moemusic.track.bad_request"))
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
            else -> return usage(sender, "filter <track|artist> <ban|unban|toggle> <source> <id> [note]")
        }
        Chat.success(sender, when (args[0].lowercase(Locale.ROOT)) {
            "track" -> trackFilterMessage(action, result.nowBlocked, id, result.changed)
            else -> artistFilterMessage(action, result.nowBlocked, id, result.changed)
        })
    }

    private fun system(sender: CommandSender) {
        if (!require(sender, PermissionNodes.SYSTEM_INFO)) return
        val plugins = PluginManager.plugins.sortedBy { it.id.lowercase(Locale.ROOT) }
        val sources = PluginManager.musicSourceSnapshot().sortedBy { it.id.lowercase(Locale.ROOT) }
        Chat.multiline(sender, buildList {
            add(prefixed(sender, LocalizedText.key(
                "action.moemusic.system.summary",
                plugin.description.version,
                "Spigot",
                MoeMusicApi.API_VERSION,
                MoeMusicCoreBuildInfo.CORE_VERSION,
                "n/a",
                "${plugin.description.version} / ${plugin.server.bukkitVersion}",
                plugins.size.toString(),
                sources.size.toString(),
            )))
            plugins.forEach { item ->
                add(localized(sender, LocalizedText.key(
                    "action.moemusic.system.plugin",
                    Chat.render(sender, item.displayName),
                    item.id,
                    item.version,
                    item.configId,
                    localizedBoolean(sender, item.configSpec != null),
                ), prefix = "  "))
            }
            add(Chat.legacy(""))
            sources.forEach { source ->
                val resolver = source is IdentifierResolvableMusicSource
                add(localized(sender, LocalizedText.key(
                    "action.moemusic.system.source",
                    Chat.render(sender, source.displayName),
                    source.id,
                    localizedBoolean(sender, source is SearchableMusicSource),
                    localizedBoolean(sender, resolver),
                    localizedBoolean(sender, resolver && source.isFallbackResolver),
                ), prefix = "  "))
            }
        })
    }

    private fun submitted(sender: CommandSender, track: TrackInfo, result: TrackAddResult) {
        Chat.success(sender, LocalizedText.key("action.moemusic.track.queued", track.title.ifBlank { track.id.ifBlank { "track" } }))
    }

    private fun choices(sender: CommandSender, entries: List<SelectionEntry>, sourceId: String) {
        val canBypassFilter = hasPermission(sender, PermissionNodes.CONTENT_FILTER_BYPASS)
        val canSeeFilterDetail = hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE)
        Chat.multiline(sender, buildList {
            add(prefixed(sender, LocalizedText.key("action.moemusic.selection.choose_prompt")))
            add(prefixed(sender, LocalizedText.key("action.moemusic.selection.header", entries.size)))
            entries.forEachIndexed { index, entry ->
                add(selectionChoiceLine(sender, index, entry, canBypassFilter, canSeeFilterDetail))
            }
        })
    }

    private fun queueTrackLine(
        sender: CommandSender,
        index: Int?,
        track: TrackInfo,
        isCurrent: Boolean,
    ): List<BaseComponent> {
        val first = buildList {
            addAll(queuePrimaryLine(sender, index, track, isCurrent))
            if (!isCurrent) addAll(queueActionSuffix(sender, track))
            addAll(moderationSuffix(sender, track))
        }
        return first + Chat.legacy("\n") + queueMetaLine(sender, track)
    }

    private fun queuePrimaryLine(
        sender: CommandSender,
        index: Int?,
        track: TrackInfo,
        isCurrent: Boolean,
    ): List<BaseComponent> {
        val titleColor = if (isCurrent) "§6" else if (track.isAvailable) "§b" else "§7"
        val prefix = if (isCurrent) {
            "§6${Chat.render(sender, LocalizedText.key("screen.moemusic.queue.now_playing"))}§8: "
        } else {
            "§e${requireNotNull(index)}. "
        }
        val title = truncateForChat(track.title.ifBlank { "-" }, queuePrimaryTitleLimit(sender, track, isCurrent))
        val text = "  $prefix$titleColor$title"
        return if (track.isAvailable) Chat.legacy(text)
        else Chat.hoverable(sender, text, track.unavailabilityMessage(), SpigotChatFormatting.Tone.FAILURE)
    }

    private fun queueMetaLine(sender: CommandSender, track: TrackInfo): List<BaseComponent> {
        val line = StringBuilder("  ")
        var hasMeta = false
        hasMeta = appendMetaField(line, hasMeta, truncateForChat(compactMetaText(track.artistDisplay.takeIf { it.isNotBlank() && it != "-" }), 20), "§7")
        hasMeta = appendMetaField(line, hasMeta, formatDuration(track.durationMs), "§8")
        if (track.isAvailable) {
            hasMeta = appendMetaField(
                line,
                hasMeta,
                truncateForChat(track.submittedByUserName?.trim()?.takeIf(String::isNotEmpty)?.let { "@$it" }, 12),
                "§8",
            )
            appendMetaField(
                line,
                hasMeta,
                truncateForChat(compactMetaText(sourceDisplayName(sender, track.sourceId).takeIf(String::isNotBlank)), 12),
                "§3",
            )
        } else {
            appendMetaField(line, hasMeta, truncateForChat(Chat.render(sender, track.unavailabilityMessage()), 36), "§c")
        }
        val text = line.toString()
        return if (track.isAvailable) Chat.legacy(text)
        else Chat.hoverable(sender, text, track.unavailabilityMessage(), SpigotChatFormatting.Tone.FAILURE)
    }

    private fun selectionChoiceLine(
        sender: CommandSender,
        index: Int,
        entry: SelectionEntry,
        canBypassFilter: Boolean,
        canSeeFilterDetail: Boolean,
    ): List<BaseComponent> {
        val filterReason = if (canBypassFilter) null else when (val verdict = ContentFilterRuntime.selectionFilterVerdict(entry)) {
            FilterVerdict.Allow -> null
            is FilterVerdict.Reject -> if (canSeeFilterDetail) verdict.reason
            else LocalizedText.key("error.moemusic.content_filter.managed")
        }
        val isSelectable = entry.isSelectable && filterReason == null
        val unavailable = filterReason ?: entry.unavailabilityMessage()
        val defaultAction = selectionDefaultAction(sender, entry, isSelectable)
        val first = buildList {
            addAll(selectionPrimaryLine(sender, index, entry, isSelectable, unavailable, defaultAction))
            addAll(selectionActionSuffix(sender, entry, defaultAction))
            addAll(moderationSuffix(sender, entry))
        }
        return first + Chat.legacy("\n") + selectionMetaLine(sender, entry, isSelectable, unavailable, defaultAction)
    }

    private fun selectionPrimaryLine(
        sender: CommandSender,
        index: Int,
        entry: SelectionEntry,
        isSelectable: Boolean,
        unavailable: LocalizedText,
        action: CommandActionTarget?,
    ): List<BaseComponent> {
        val titleColor = if (isSelectable) "§b" else "§7"
        val title = truncateForChat(entry.title.ifBlank { "-" }, selectionPrimaryTitleLimit(sender, entry, action))
        val text = "  §e${index + 1}. $titleColor$title"
        return when {
            action != null -> Chat.clickable(sender, text, action.command, action.hover)
            !isSelectable -> Chat.hoverable(sender, text, unavailable, SpigotChatFormatting.Tone.FAILURE)
            else -> Chat.legacy(text)
        }
    }

    private fun selectionMetaLine(
        sender: CommandSender,
        entry: SelectionEntry,
        isSelectable: Boolean,
        unavailable: LocalizedText,
        action: CommandActionTarget?,
    ): List<BaseComponent> {
        val line = StringBuilder("  ")
        var hasMeta = false
        hasMeta = appendMetaField(line, hasMeta, truncateForChat(compactMetaText(entry.artistDisplay.takeIf { it.isNotBlank() && it != "-" }), 20), "§7")
        hasMeta = if (isSelectable) {
            appendMetaField(line, hasMeta, truncateForChat(compactMetaText(entry.album?.takeIf(String::isNotBlank)), 18), "§9")
        } else {
            appendMetaField(line, hasMeta, truncateForChat(Chat.render(sender, unavailable), 36), "§c")
        }
        appendMetaField(line, hasMeta, formatDuration(entry.durationMs), "§8")
        return when {
            action != null -> Chat.clickable(sender, line.toString(), action.command, action.hover)
            !isSelectable -> Chat.hoverable(sender, line.toString(), unavailable, SpigotChatFormatting.Tone.FAILURE)
            else -> Chat.legacy(line.toString())
        }
    }

    private fun queueActionSuffix(sender: CommandSender, track: TrackInfo): List<BaseComponent> {
        val sourceId = track.sourceId.orEmpty()
        if (sourceId.isBlank() || track.id.isBlank()) return emptyList()
        val suffix = mutableListOf<BaseComponent>()
        if (canPlayNow(sender)) {
            suffix += Chat.legacy(" ")
            suffix += Chat.action(
                sender,
                "▶",
                "§c",
                trackSubmitCommand(sourceId, track.id, TrackAddMode.PLAY_NOW),
                LocalizedText.key("action.moemusic.queue.play_now_hover", track.title.ifBlank { track.id }),
            )
        } else {
            suffix += Chat.legacy(" ")
        }
        suffix += Chat.action(
            sender,
            "✕",
            "§c",
            queueRemoveCommand(sourceId, track.id),
            LocalizedText.key("action.moemusic.queue.remove_hover", track.title.ifBlank { track.id }),
        )
        return suffix
    }

    private fun selectionActionSuffix(
        sender: CommandSender,
        entry: SelectionEntry,
        defaultAction: CommandActionTarget?,
    ): List<BaseComponent> {
        if (defaultAction == null) return emptyList()
        val suffix = mutableListOf<BaseComponent>()
        suffix += Chat.legacy(" ")
        suffix += Chat.action(sender, "+", "§a", defaultAction.command, defaultAction.hover)
        if (hasPermission(sender, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)) {
            val action = selectionActionTarget(entry, TrackAddMode.SKIP_AUTOPLAY)
            suffix += Chat.action(sender, "»", "§e", action.command, action.hover)
        }
        if (canPlayNow(sender)) {
            val action = selectionActionTarget(entry, TrackAddMode.PLAY_NOW)
            suffix += Chat.action(sender, "▶", "§c", action.command, action.hover)
        }
        return suffix
    }

    private fun selectionDefaultAction(
        sender: CommandSender,
        entry: SelectionEntry,
        isSelectable: Boolean,
    ): CommandActionTarget? = if (isSelectable && hasPermission(sender, PermissionNodes.SUBMIT)) {
        selectionActionTarget(entry, TrackAddMode.NORMAL)
    } else {
        null
    }

    private fun selectionActionTarget(
        entry: SelectionEntry,
        mode: TrackAddMode,
    ): CommandActionTarget {
        val sourceId = entry.sourceId ?: HttpMusicSource.id
        val command = entry.directTrackId?.let { trackSubmitCommand(sourceId, it, mode) }
            ?: selectCommand(sourceId, entry.selectionId, mode)
        return CommandActionTarget(command, selectionActionHover(entry, mode))
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

    private fun moderationSuffix(sender: CommandSender, track: TrackInfo): List<BaseComponent> {
        val sourceId = track.sourceId.orEmpty()
        if (!hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) || sourceId.isBlank() || track.id.isBlank()) return emptyList()
        val blocked = ContentFilterRuntime.isExactTrackBlocked(sourceId, track.id)
        return Chat.legacy(" ") + Chat.action(
            sender,
            if (blocked) "U" else "B",
            if (blocked) "§a" else "§c",
            filterTrackCommand(sourceId, track.id, buildTrackRuleNote(track.title.ifBlank { track.id }, track.artistDisplay)),
            LocalizedText.key(
                if (blocked) "action.moemusic.filter.quick.unban_track_hover"
                else "action.moemusic.filter.quick.ban_track_hover",
                track.title.ifBlank { track.id },
            ),
        )
    }

    private fun moderationSuffix(sender: CommandSender, entry: SelectionEntry): List<BaseComponent> {
        val sourceId = entry.sourceId.orEmpty()
        val trackId = entry.directTrackId
        if (!hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) || sourceId.isBlank() || trackId.isNullOrBlank()) return emptyList()
        val blocked = ContentFilterRuntime.isExactTrackBlocked(sourceId, trackId)
        return Chat.legacy(" ") + Chat.action(
            sender,
            if (blocked) "U" else "B",
            if (blocked) "§a" else "§c",
            filterTrackCommand(sourceId, trackId, buildTrackRuleNote(entry.title.ifBlank { trackId }, entry.artistDisplay)),
            LocalizedText.key(
                if (blocked) "action.moemusic.filter.quick.unban_track_hover"
                else "action.moemusic.filter.quick.ban_track_hover",
                entry.title.ifBlank { trackId },
            ),
        )
    }

    private fun searchPaginationFooter(
        sender: CommandSender,
        parsed: ParsedSearch,
        sourceId: String,
        total: Int,
        pageSize: Int,
        hasMore: Boolean,
    ): List<BaseComponent>? {
        if (total <= 0 || pageSize <= 0) return null
        val selectedSource = sourceId.ifBlank { parsed.sourceId.orEmpty() }
        val footer = mutableListOf<BaseComponent>()
        footer += Chat.legacy("  ")
        if (parsed.page > 1) {
            footer += Chat.action(
                sender,
                "<",
                "§e",
                searchCommand(parsed.query, selectedSource, parsed.page - 1),
                LocalizedText.key("action.moemusic.search.prev_page"),
            )
            footer += Chat.legacy(" ")
        }
        footer += Chat.legacy("§8(§7${parsed.page}§8 / §7${totalPages(total, pageSize)}§8)")
        sourceDisplayName(sender, selectedSource).trim().takeIf(String::isNotEmpty)?.let {
            footer += Chat.legacy(" §8· §3${compactMetaText(it)}")
        }
        if (hasMore) {
            footer += Chat.legacy(" ")
            footer += Chat.action(
                sender,
                ">",
                "§e",
                searchCommand(parsed.query, selectedSource, parsed.page + 1),
                LocalizedText.key("action.moemusic.search.next_page"),
            )
        }
        return footer
    }

    private fun localized(sender: CommandSender, text: LocalizedText, prefix: String = ""): List<BaseComponent> =
        Chat.legacy(prefix + SpigotChatFormatting.render(Chat.locale(sender), text))

    private fun prefixed(
        sender: CommandSender,
        text: LocalizedText,
        tone: SpigotChatFormatting.Tone = SpigotChatFormatting.Tone.SUCCESS,
    ): List<BaseComponent> = Chat.legacy(SpigotChatFormatting.prefixed(Chat.locale(sender), text, tone))

    private fun localizedBoolean(sender: CommandSender, value: Boolean): String =
        Chat.render(sender, LocalizedText.key(if (value) "label.moemusic.yes" else "label.moemusic.no"))

    private fun sourceDisplayName(sender: CommandSender, sourceId: String?): String {
        if (sourceId.isNullOrBlank()) return ""
        val source = PluginManager.musicSourceSnapshot().firstOrNull { it.id == sourceId } ?: return sourceId
        return Chat.render(sender, source.displayName)
    }

    private fun appendMetaField(line: StringBuilder, hasPrevious: Boolean, text: String?, color: String): Boolean {
        val normalized = text?.trim()?.takeIf(String::isNotEmpty) ?: return hasPrevious
        if (hasPrevious) line.append("§8・")
        line.append(color).append(normalized)
        return true
    }

    private fun compactMetaText(text: String?): String? = text?.trim()?.takeIf(String::isNotEmpty)?.replace(", ", ",")

    private fun truncateForChat(text: String?, maxChars: Int): String? {
        val normalized = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.length <= maxChars) return normalized
        if (maxChars <= 1) return normalized.take(maxChars)
        return normalized.take(maxChars - 1) + "…"
    }

    private fun selectionPrimaryTitleLimit(sender: CommandSender, entry: SelectionEntry, action: CommandActionTarget?): Int {
        var chips = 0
        if (action != null) {
            chips++
            if (hasPermission(sender, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)) chips++
            if (canPlayNow(sender)) chips++
        }
        if (hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) && entry.directTrackId != null) chips++
        return (48 - chips * 3).coerceAtLeast(24)
    }

    private fun queuePrimaryTitleLimit(sender: CommandSender, track: TrackInfo, isCurrent: Boolean): Int {
        var chips = if (isCurrent) 0 else 1
        if (!isCurrent && canPlayNow(sender)) chips++
        if (hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE) && track.sourceId != null && track.id.isNotBlank()) chips++
        val base = if (isCurrent) 40 else 48
        return (base - chips * 3).coerceAtLeast(if (isCurrent) 20 else 24)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "?:??"
        val totalSec = ms / 1000
        return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
    }

    private fun totalPages(total: Int, pageSize: Int): Int = if (total <= 0 || pageSize <= 0) 1 else ((total - 1) / pageSize) + 1

    private data class CommandActionTarget(val command: String, val hover: LocalizedText)

    private fun help(sender: CommandSender) {
        Chat.plain(sender, "/music add <link> | addById <source> <id> | search <query> | queue")
        Chat.plain(sender, "/music pause | resume | skip | stop | remove <index> | system")
        if (hasPermission(sender, PermissionNodes.CONFIG_RELOAD)) {
            Chat.plain(sender, "/music reload <all|filter|autoplay> | filter <track|artist> <ban|unban|toggle> ...")
        }
    }

    private fun require(sender: CommandSender, node: PermissionNodes.Node): Boolean {
        if (hasPermission(sender, node)) return true
        Chat.failure(sender, node.deniedMessage)
        return false
    }

    private fun hasPermission(sender: CommandSender, node: PermissionNodes.Node): Boolean = when (sender) {
        is Player -> SpigotUser.snapshot(sender).hasPermission(node.id, node.defaultLevel())
        is ConsoleCommandSender -> true
        else -> sender.hasPermission(node.id)
    }

    private fun hasSkipPermission(sender: CommandSender): Boolean =
        hasPermission(sender, PermissionNodes.QUEUE_CONTROL) ||
            (sender is Player && hasPermission(sender, PermissionNodes.VOTE))

    private fun canPlayNow(sender: CommandSender): Boolean =
        hasPermission(sender, PermissionNodes.SUBMIT) && hasPermission(sender, PermissionNodes.QUEUE_CONTROL)

    private fun requireMode(sender: CommandSender, mode: TrackAddMode): Boolean = when (mode) {
        TrackAddMode.NORMAL -> true
        TrackAddMode.SKIP_AUTOPLAY -> require(sender, PermissionNodes.SUBMIT_SKIP_AUTOPLAY)
        TrackAddMode.PLAY_NOW -> require(sender, PermissionNodes.QUEUE_CONTROL)
    }

    private fun user(sender: CommandSender) = (sender as? Player)?.let(SpigotUser::snapshot)

    private fun respond(sender: CommandSender, block: () -> Unit) {
        plugin.runOnServerThread(block)
    }

    private fun failLater(sender: CommandSender, error: Exception) = respond(sender) { fail(sender, error) }

    private fun fail(sender: CommandSender, error: Exception) {
        if (UserFacingErrors.isExpected(error)) {
            plugin.logger.fine("MoeMusic command rejected: ${error.message}")
        } else {
            plugin.logger.warning("MoeMusic command failed: ${error.message}")
        }
        Chat.failure(sender, classify(sender, error))
    }

    private fun classify(sender: CommandSender, error: Exception): LocalizedText =
        if (error is FilterBlockException && !hasPermission(sender, PermissionNodes.CONTENT_FILTER_MANAGE)) {
            error.maskedReason
        } else {
            UserFacingErrors.classify(error)
        }

    private fun usage(sender: CommandSender, suffix: String) {
        Chat.plain(sender, "Usage: /music $suffix")
    }

    private fun selectCommand(sourceId: String, selectionId: String, mode: TrackAddMode = TrackAddMode.NORMAL): String {
        val base = "/music select ${quoteCommandToken(sourceId)} ${quoteCommandToken(selectionId)}"
        return when (mode) {
            TrackAddMode.NORMAL -> base
            TrackAddMode.SKIP_AUTOPLAY -> "$base --skip-autoplay"
            TrackAddMode.PLAY_NOW -> "$base --now"
        }
    }

    private fun trackSubmitCommand(sourceId: String, trackId: String, mode: TrackAddMode = TrackAddMode.NORMAL): String {
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
        return appendGreedyCommandTail(base, query)
    }

    private fun appendGreedyCommandTail(prefix: String, text: String?): String =
        text?.trim()?.takeIf(String::isNotEmpty)?.let { "$prefix $it" } ?: prefix

    private fun filterTrackCommand(sourceId: String, trackId: String, note: String?): String {
        val action = if (ContentFilterRuntime.isExactTrackBlocked(sourceId, trackId)) "unban" else "ban"
        return appendGreedyCommandTail(
            "/music filter track $action ${quoteCommandToken(sourceId)} ${quoteCommandToken(trackId)}",
            note,
        )
    }

    private fun filterArtistCommand(sourceId: String, artistId: String, note: String?): String {
        val action = if (ContentFilterRuntime.isExactArtistBlocked(sourceId, artistId)) "unban" else "ban"
        return appendGreedyCommandTail(
            "/music filter artist $action ${quoteCommandToken(sourceId)} ${quoteCommandToken(artistId)}",
            note,
        )
    }

    private fun buildTrackRuleNote(title: String, artist: String): String? {
        val normalizedTitle = title.trim().takeIf(String::isNotEmpty)
        val normalizedArtist = artist.trim().takeIf { it.isNotEmpty() && it != "-" }
        return when {
            normalizedTitle != null && normalizedArtist != null -> "$normalizedTitle - $normalizedArtist"
            normalizedTitle != null -> normalizedTitle
            normalizedArtist != null -> normalizedArtist
            else -> null
        }
    }

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

    private fun quoteCommandToken(value: String): String = if (
        value.isNotEmpty() && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' || it == '+' }
    ) {
        value
    } else {
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

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

        private fun mode(flag: String?): TrackAddMode? = when (flag) {
            "--skip-autoplay" -> TrackAddMode.SKIP_AUTOPLAY
            "--now" -> TrackAddMode.PLAY_NOW
            else -> null
        }

        private fun modeAndValue(args: List<String>): Pair<TrackAddMode, String>? {
            if (args.isEmpty()) return null
            val selected = mode(args.first())
            val value = args.drop(if (selected == null) 0 else 1).joinToString(" ").trim()
            return value.takeIf(String::isNotEmpty)?.let { (selected ?: TrackAddMode.NORMAL) to it }
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
