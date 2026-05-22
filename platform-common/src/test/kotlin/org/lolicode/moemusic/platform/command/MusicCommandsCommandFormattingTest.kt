package org.lolicode.moemusic.platform.command

import com.mojang.brigadier.suggestion.SuggestionsBuilder
import org.lolicode.moemusic.api.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.session.UserSessionRegistry
import java.nio.file.Files
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicCommandsCommandFormattingTest {

    @AfterTest
    fun clearSessionRegistry() {
        UserSessionRegistry.clear()
        Localization.clear()
        ModConfigManager.load(Files.createTempDirectory("moemusic-command-formatting-reset"))
    }

    @Test
    fun `quoteCommandToken quotes opaque brigadier string tokens`() {
        assertEquals("plain-token", MusicCommands.quoteCommandToken("plain-token"))
        assertEquals("\"track id\"", MusicCommands.quoteCommandToken("track id"))
        assertEquals("\"track \\\"id\\\"\"", MusicCommands.quoteCommandToken("track \"id\""))
        assertEquals("\"plugin:source\"", MusicCommands.quoteCommandToken("plugin:source"))
        assertEquals("\"https://example.com/audio?id=1\"", MusicCommands.quoteCommandToken("https://example.com/audio?id=1"))
        assertEquals("\"\"", MusicCommands.quoteCommandToken(""))
    }

    @Test
    fun `appendGreedyCommandTail appends raw text without adding quotes`() {
        assertEquals(
            "/music filter track ban source \"track id\" title - artist",
            MusicCommands.appendGreedyCommandTail(
                "/music filter track ban source ${MusicCommands.quoteCommandToken("track id")}",
                "title - artist",
            )
        )
        assertEquals(
            "/music filter track ban source track-id title \"feat\" artist",
            MusicCommands.appendGreedyCommandTail(
                "/music filter track ban source ${MusicCommands.quoteCommandToken("track-id")}",
                "title \"feat\" artist",
            )
        )
    }

    @Test
    fun `appendGreedyCommandTail omits blank tails`() {
        assertEquals("/music filter artist ban source artist-id", MusicCommands.appendGreedyCommandTail("/music filter artist ban source artist-id", null))
        assertEquals("/music filter artist ban source artist-id", MusicCommands.appendGreedyCommandTail("/music filter artist ban source artist-id", "   "))
    }

    @Test
    fun `queueRemoveCommand quotes source ids and track ids for brigadier string arguments`() {
        assertEquals("/music remove source track-id", MusicCommands.queueRemoveCommand("source", "track-id"))
        assertEquals("/music remove \"plugin:source\" track-id", MusicCommands.queueRemoveCommand("plugin:source", "track-id"))
        assertEquals("/music remove source \"track id\"", MusicCommands.queueRemoveCommand("source", "track id"))
    }

    @Test
    fun `trackSubmitCommand quotes opaque ids and appends mode flags after the track id`() {
        assertEquals("/music addById source track-id", MusicCommands.trackSubmitCommand("source", "track-id"))
        assertEquals(
            "/music addById \"plugin:source\" \"track id\" --skip-autoplay",
            MusicCommands.trackSubmitCommand("plugin:source", "track id", TrackAddMode.SKIP_AUTOPLAY),
        )
        assertEquals(
            "/music addById source \"https://example.com/audio?id=1\" --now",
            MusicCommands.trackSubmitCommand("source", "https://example.com/audio?id=1", TrackAddMode.PLAY_NOW),
        )
    }

    @Test
    fun `searchCommand keeps flags before the greedy query tail`() {
        assertEquals("/music search synthwave mix", MusicCommands.searchCommand("synthwave mix"))
        assertEquals("/music search --source ncm synthwave mix", MusicCommands.searchCommand("synthwave mix", sourceId = "ncm"))
        assertEquals("/music search --source \"plugin:source\" synthwave mix", MusicCommands.searchCommand("synthwave mix", sourceId = "plugin:source"))
        assertEquals("/music search --page 2 title \"feat\" artist", MusicCommands.searchCommand("title \"feat\" artist", page = 2))
        assertEquals(
            "/music search --source ncm --page 3 title \"feat\" artist",
            MusicCommands.searchCommand("title \"feat\" artist", sourceId = "ncm", page = 3)
        )
    }

    @Test
    fun `selectionCommand quotes opaque handles and appends mode flags after the selection id`() {
        assertEquals(
            "/music select source track-id",
            MusicCommands.selectionCommand("source", "track-id"),
        )
        assertEquals(
            "/music select \"plugin:source\" \"selection id\" --skip-autoplay",
            MusicCommands.selectionCommand("plugin:source", "selection id", TrackAddMode.SKIP_AUTOPLAY),
        )
        assertEquals(
            "/music select source \"https://example.com/item?id=1\" --now",
            MusicCommands.selectionCommand("source", "https://example.com/item?id=1", TrackAddMode.PLAY_NOW),
        )
    }

    @Test
    fun `suggestSourceIds matches raw ids while inserting brigadier-safe tokens`() {
        withMusicSources(
            searchableSource("alpha"),
            plainSource("plugin:source"),
        ) {
            assertEquals(
                setOf("alpha", "\"plugin:source\""),
                suggestedSourceIds("").toSet(),
            )
            assertEquals(
                listOf("\"plugin:source\""),
                suggestedSourceIds("pl"),
            )
            assertEquals(
                listOf("\"plugin:source\""),
                suggestedSourceIds("\"pl"),
            )
        }
    }

    @Test
    fun `suggestSourceIds can be restricted to searchable sources`() {
        withMusicSources(
            searchableSource("alpha"),
            plainSource("local-only"),
        ) {
            assertEquals(
                listOf("alpha"),
                suggestedSourceIds("", searchableOnly = true),
            )
        }
    }

    @Test
    fun `sourceLocale uses standby session locale instead of active-only fallback`() {
        ModConfigManager.load(Files.createTempDirectory("moemusic-command-formatting-locale"))
        val userId = UUID.randomUUID()
        UserSessionRegistry.registerStandby(testUser(userId, locale = "en_us"), locale = "zh_cn")

        assertEquals("zh_cn", MusicCommands.sourceLocale(userId))
        assertEquals("en_us", MusicCommands.sourceLocale(null))
    }

    @Test
    fun `sourceLocale uses configured default language without a handshaked client locale`() {
        ModConfigManager.load(Files.createTempDirectory("moemusic-command-formatting-default-language"))
        Localization.register("zh_cn", "test.moemusic.message", "message")
        ModConfigManager.update { it.copy(defaultLanguage = "zh_cn") }

        assertEquals("zh_cn", MusicCommands.sourceLocale(UUID.randomUUID()))
        assertEquals("zh_cn", MusicCommands.sourceLocale(null))
    }

    private fun suggestedSourceIds(
        remaining: String,
        searchableOnly: Boolean = false,
    ): List<String> = MusicCommands.suggestSourceIds(
        builder = SuggestionsBuilder(remaining, 0),
        searchableOnly = searchableOnly,
    ).join().list.map { it.text }

    private fun withMusicSources(vararg sources: MusicSource, block: () -> Unit) {
        val snapshot = PluginManager.musicSourceSnapshot()
        PluginManager.musicSources.clear()
        PluginManager.musicSources.addAll(sources)
        try {
            block()
        } finally {
            PluginManager.musicSources.clear()
            PluginManager.musicSources.addAll(snapshot)
        }
    }

    private fun searchableSource(id: String): SearchableMusicSource = object : SearchableMusicSource {
        override val id: String = id
        override val displayName: LocalizedText = LocalizedText.plain(id)

        override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResource =
            PlaybackResource("https://example.com/$id")

        override suspend fun search(query: SearchQuery, submitter: MoeMusicUser?): UserResult<SearchResult> =
            UserResult.Success(SearchResult(entries = emptyList(), sourceId = id, total = 0))
    }

    private fun plainSource(id: String): MusicSource = object : MusicSource {
        override val id: String = id
        override val displayName: LocalizedText = LocalizedText.plain(id)

        override suspend fun resolve(track: TrackInfo, submitter: MoeMusicUser?): PlaybackResource =
            PlaybackResource("https://example.com/$id")
    }

    private fun testUser(
        id: UUID,
        locale: String,
    ): MoeMusicUser = object : MoeMusicUser() {
        override val displayName: String = "tester"
        override val id: UUID = id
        override val locale: String = locale

        override fun hasPermission(permission: String, defaultLevel: Int): Boolean = true
    }
}
