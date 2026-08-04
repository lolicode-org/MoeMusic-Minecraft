package org.lolicode.moemusic.spigot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
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
import org.lolicode.moemusic.api.model.isDirectTrack
import org.lolicode.moemusic.api.service.IdentifierSubmitOutcome
import org.lolicode.moemusic.api.service.PlaybackAction
import org.lolicode.moemusic.api.service.QueueRemoveResult
import org.lolicode.moemusic.api.service.SelectionSubmitOutcome
import org.lolicode.moemusic.core.MoeMusicCoreBuildInfo
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuleEditor
import org.lolicode.moemusic.core.error.UserFacingErrors
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
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
            args.size == 1 -> SUBCOMMANDS
            args.size == 2 && args[0].equals("reload", true) -> listOf("all", "filter", "autoplay")
            args.size == 2 && args[0].equals("filter", true) -> listOf("track", "artist")
            args.size == 3 && args[0].equals("filter", true) -> listOf("ban", "unban", "toggle")
            args.size == 2 && args[0].equals("search", true) -> listOf("--source", "--page")
            args.size == 2 && args[0].equals("add", true) -> listOf("--skip-autoplay", "--now")
            args.size == 2 && args[0].equals("addbyid", true) -> sourceIds(false)
            args.size == 2 && args[0].equals("select", true) -> sourceIds(false)
            args.size == 4 && args[0].equals("filter", true) -> sourceIds(false)
            else -> emptyList()
        }
        val prefix = args.lastOrNull().orEmpty()
        return candidates.filter { it.startsWith(prefix, ignoreCase = true) }
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
        val trackId = unquote(selectedArgs.joinToString(" "))
        if (trackId.isBlank()) return usage(sender, "addById <source> <trackId> [--skip-autoplay|--now]")
        val mode = trailingMode ?: TrackAddMode.NORMAL
        if (!requireMode(sender, mode)) return
        val submitter = user(sender)
        scope.launch {
            try {
                val result = ServerRuntimeCoordinator.userActionService.submitBySourceAndId(args[0], trackId, submitter, mode)
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
        val selectionId = unquote(selectedArgs.joinToString(" "))
        if (selectionId.isBlank()) return usage(sender, "select <source> <selectionId> [--skip-autoplay|--now]")
        val mode = trailingMode ?: TrackAddMode.NORMAL
        if (!requireMode(sender, mode)) return
        val submitter = user(sender)
        scope.launch {
            try {
                when (val result = ServerRuntimeCoordinator.userActionService.submitBySelection(args[0], selectionId, submitter, mode)) {
                    is SelectionSubmitOutcome.Submitted -> respond(sender) { submitted(sender, result.track, result.result) }
                    is SelectionSubmitOutcome.Choices -> respond(sender) { choices(sender, result.entries, args[0]) }
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
        Chat.success(sender, LocalizedText.key("action.moemusic.queue.header", queued.size + if (current == null) 0 else 1))
        current?.let { Chat.message(sender, "  §6Now: §b${trackLine(it)}") }
        queued.forEachIndexed { index, track ->
            Chat.message(
                sender,
                Chat.clickable(
                    sender,
                    "  §e${index + 1}. §b${trackLine(track)} §c[✕]",
                    "/music remove ${index + 1}",
                    LocalizedText.key("action.moemusic.queue.remove_hover", track.title.ifBlank { track.id }),
                ),
            )
        }
    }

    private fun remove(sender: CommandSender, args: List<String>) {
        if (!require(sender, PermissionNodes.QUEUE_VIEW)) return
        val target = when {
            args.size == 1 -> ServerRuntimeCoordinator.queue.userQueueSnapshot().getOrNull(args[0].toIntOrNull()?.minus(1) ?: -1)
            args.size >= 2 -> null
            else -> return usage(sender, "remove <index> | <source> <trackId>")
        }
        val sourceId = target?.sourceId ?: args.getOrNull(0)
        val trackId = target?.id ?: args.drop(1).joinToString(" ").let(::unquote)
        if (sourceId.isNullOrBlank() || trackId.isNullOrBlank()) return Chat.failure(sender, LocalizedText.key("error.moemusic.queue.invalid_index"))
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
                    if (result.entries.isEmpty()) {
                        Chat.success(sender, LocalizedText.key("action.moemusic.search.no_results"))
                    } else {
                        Chat.success(sender, LocalizedText.key("action.moemusic.search.header", result.entries.size))
                        result.entries.forEachIndexed { index, entry ->
                            val source = entry.sourceId ?: result.sourceId
                            val command = selectCommand(source, entry.selectionId)
                            val hoverKey = if (entry.isDirectTrack) {
                                "action.moemusic.search.click_to_queue"
                            } else {
                                "action.moemusic.search.click_to_select"
                            }
                            Chat.message(
                                sender,
                                Chat.clickable(
                                    sender,
                                    "  §e${index + 1}. §b${selectionLine(entry)}",
                                    command,
                                    LocalizedText.key(hoverKey, entry.title.ifBlank { entry.selectionId }),
                                ),
                            )
                            Chat.message(sender, Chat.clickable(sender, "     §7$command", command))
                        }
                    }
                    if (parsed.page > 1 || result.hasMore) {
                        Chat.message(sender, "  §8Page §7${parsed.page}${if (result.total > 0) "/${(result.total + pageSize - 1) / pageSize}" else ""}")
                    }
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
                    Chat.success(sender, LocalizedText.key("action.moemusic.reload.reloaded"))
                    if (report.pluginConfigFailures.isNotEmpty()) {
                        Chat.failure(sender, LocalizedText.key("error.moemusic.reload.plugin_failures", report.pluginConfigFailures.keys.joinToString()))
                    }
                } catch (error: Exception) {
                    fail(sender, error)
                }
            }
            "filter" -> {
                if (!require(sender, PermissionNodes.CONTENT_FILTER_MANAGE)) return
                try {
                    ContentFilterRuleEditor.reloadFromDisk(ServerRuntimeCoordinator.configDir)
                    Chat.success(sender, LocalizedText.key("action.moemusic.filter.reloaded"))
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
        val note = args.drop(4).joinToString(" ").takeIf(String::isNotBlank)
        val result = when (args[0].lowercase(Locale.ROOT)) {
            "track" -> ContentFilterRuleEditor.updateTrackRule(args[2], args[3], action, note).also {
                if (it.nowBlocked) {
                    val removal = ServerRuntimeCoordinator.playbackController.removeQueuedTrack(args[2], args[3], null, true)
                    if (removal == QueueRemoveResult.NOT_FOUND) {
                        ServerRuntimeCoordinator.playbackController.skipIfCurrentTrackMatches(args[2], args[3])
                    }
                }
            }
            "artist" -> ContentFilterRuleEditor.updateArtistRules(args[2], listOf(args[3]), action, note)
            else -> return usage(sender, "filter <track|artist> <ban|unban|toggle> <source> <id> [note]")
        }
        Chat.plain(sender, "Filter updated: blocked=${result.nowBlocked}, changed=${result.changed}, affected=${result.affectedCount}")
    }

    private fun system(sender: CommandSender) {
        if (!require(sender, PermissionNodes.SYSTEM_INFO)) return
        Chat.plain(sender, "Spigot plugin ${plugin.description.version}; API ${MoeMusicApi.API_VERSION}; core ${MoeMusicCoreBuildInfo.CORE_VERSION}")
        PluginManager.plugins.sortedBy { it.id }.forEach { Chat.plain(sender, "Plugin ${it.id} ${it.version}") }
        PluginManager.musicSourceSnapshot().sortedBy { it.id }.forEach {
            Chat.plain(sender, "Source ${it.id}: search=${it is SearchableMusicSource}, identifiers=${it is IdentifierResolvableMusicSource}")
        }
    }

    private fun submitted(sender: CommandSender, track: TrackInfo, result: TrackAddResult) {
        Chat.success(sender, LocalizedText.key("action.moemusic.track.queued", track.title.ifBlank { track.id }))
    }

    private fun choices(sender: CommandSender, entries: List<SelectionEntry>, sourceId: String) {
        Chat.success(sender, LocalizedText.key("action.moemusic.selection.choose_prompt"))
        entries.forEachIndexed { index, entry ->
            val command = selectCommand(entry.sourceId ?: sourceId, entry.selectionId)
            val hoverKey = if (entry.isDirectTrack) {
                "action.moemusic.search.click_to_queue"
            } else {
                "action.moemusic.search.click_to_select"
            }
            Chat.message(
                sender,
                Chat.clickable(
                    sender,
                    "  §e${index + 1}. §b${selectionLine(entry)}",
                    command,
                    LocalizedText.key(hoverKey, entry.title.ifBlank { entry.selectionId }),
                ),
            )
            Chat.message(sender, Chat.clickable(sender, "     §7$command", command))
        }
    }

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
        plugin.logger.warning("MoeMusic command failed: ${error.message}")
        Chat.failure(sender, UserFacingErrors.classify(error))
    }

    private fun usage(sender: CommandSender, suffix: String) {
        Chat.plain(sender, "Usage: /music $suffix")
    }

    private fun trackLine(track: TrackInfo): String =
        "${track.title.ifBlank { track.id }}${track.artistDisplay.takeIf(String::isNotBlank)?.let { " - $it" }.orEmpty()} [${track.sourceId ?: "?"}:${track.id}]"

    private fun selectionLine(entry: SelectionEntry): String =
        "${entry.title.ifBlank { entry.selectionId }}${entry.artistDisplay.takeIf(String::isNotBlank)?.let { " - $it" }.orEmpty()}"

    private fun selectCommand(sourceId: String, selectionId: String): String =
        "/music select ${quote(sourceId)} ${quote(selectionId)}"

    private fun sourceIds(searchableOnly: Boolean): List<String> = PluginManager.musicSourceSnapshot()
        .filter { !searchableOnly || it is SearchableMusicSource }
        .map { it.id }

    companion object {
        private val SUBCOMMANDS = listOf(
            "add", "addById", "select", "search", "queue", "remove", "pause", "resume", "skip", "stop", "system", "reload", "filter",
        )

        internal data class ParsedSearch(val sourceId: String?, val page: Int, val query: String)

        internal fun parseSearch(args: List<String>): ParsedSearch? {
            var sourceId: String? = null
            var page = 1
            val query = mutableListOf<String>()
            var index = 0
            while (index < args.size) {
                when (args[index]) {
                    "--source" -> sourceId = args.getOrNull(++index) ?: return null
                    "--page" -> page = args.getOrNull(++index)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                    else -> query += args[index]
                }
                index++
            }
            return query.joinToString(" ").trim().takeIf(String::isNotEmpty)?.let { ParsedSearch(sourceId, page, it) }
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

        private fun quote(value: String): String = if (value.any(Char::isWhitespace)) {
            "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        } else {
            value
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
