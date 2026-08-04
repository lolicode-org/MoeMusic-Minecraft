package org.lolicode.moemusic.spigot

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.core.i18n.Localization
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatFormattingTest {
    @AfterTest
    fun clearLocalization() {
        Localization.clear()
    }

    @Test
    fun `localized arguments keep semantic colors`() {
        Localization.register("en_us", "test.moemusic.chat", $$"Queued %1$s (%2$s)")

        assertEquals(
            "§7[MoeMusic] §fQueued §bSkyline§f (§e12§f)",
            SpigotChatFormatting.prefixed(
                "en_us",
                LocalizedText.key("test.moemusic.chat", "Skyline", 12),
                SpigotChatFormatting.Tone.SUCCESS,
            ),
        )
    }
}
