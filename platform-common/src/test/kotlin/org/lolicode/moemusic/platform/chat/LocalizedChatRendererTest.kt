package org.lolicode.moemusic.platform.chat

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.platform.text.McText
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalizedChatRendererTest {

    @AfterTest
    fun clearI18n() {
        Localization.clear()
        ModConfigManager.load(Files.createTempDirectory("moemusic-chat-renderer-reset"))
    }

    @Test
    fun `success tone highlights text and numeric fields`() {
        Localization.register("en_us", "test.moemusic.chat.success", $$"Queued %1$s (%2$s)")

        val rendered = LocalizedChatRenderer.renderString(
            locale = "en_us",
            text = LocalizedText.key("test.moemusic.chat.success", "Skyline", 12),
            tone = LocalizedChatRenderer.Tone.SUCCESS,
        )

        assertEquals("§fQueued §bSkyline§f (§e12§f)", rendered)
    }

    @Test
    fun `nested localized args shift to secondary highlight color`() {
        Localization.register("en_us", "test.moemusic.chat.outer", $$"Reason: %1$s")
        Localization.register("en_us", "test.moemusic.chat.inner", $$"Blocked: %1$s")

        val rendered = LocalizedChatRenderer.renderString(
            locale = "en_us",
            text = LocalizedText.key(
                "test.moemusic.chat.outer",
                LocalizedText.key("test.moemusic.chat.inner", "VIP"),
            ),
            tone = LocalizedChatRenderer.Tone.SUCCESS,
        )

        assertEquals("§fReason: §bBlocked: §eVIP§b§f", rendered)
    }

    @Test
    fun `failure prefix keeps body red and highlights field values`() {
        Localization.register("en_us", "test.moemusic.chat.failure", $$"Blocked host: %1$s")

        val rendered = LocalizedChatRenderer.prefixedString(
            locale = "en_us",
            text = LocalizedText.key("test.moemusic.chat.failure", "127.0.0.1"),
            tone = LocalizedChatRenderer.Tone.FAILURE,
        )

        assertEquals("§c[MoeMusic] §cBlocked host: §6127.0.0.1§c", rendered)
    }

    @Test
    fun `component prefix preserves multiline bodies`() {
        val rendered = LocalizedChatRenderer.prefixed(
            body = McText.literal("Header\nDetail"),
            tone = LocalizedChatRenderer.Tone.SUCCESS,
        )

        assertEquals("§7[MoeMusic] Header\nDetail", rendered.string)
    }

    @Test
    fun `missing locale uses configured server default language`() {
        ModConfigManager.load(Files.createTempDirectory("moemusic-chat-renderer-default-language"))
        Localization.register("en_us", "test.moemusic.chat.fallback", "English")
        Localization.register("zh_cn", "test.moemusic.chat.fallback", "Chinese")
        ModConfigManager.update { it.copy(defaultLanguage = "zh_cn") }

        val rendered = LocalizedChatRenderer.renderString(
            locale = "missing_locale",
            text = LocalizedText.key("test.moemusic.chat.fallback"),
            tone = LocalizedChatRenderer.Tone.SUCCESS,
        )

        assertEquals("§fChinese", rendered)
    }
}
