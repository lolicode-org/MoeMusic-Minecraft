package org.lolicode.moemusic.spigot

import kotlin.test.Test
import kotlin.test.assertEquals

class MusicCommandParsingTest {
    @Test
    fun `search accepts both mod option orders and unquotes source ids`() {
        MusicCommand.parseSearch(listOf("--source", "\"plugin:source\"", "--page", "3", "title", "mix"))!!.let {
            assertEquals("plugin:source", it.sourceId)
            assertEquals(3, it.page)
            assertEquals("title mix", it.query)
        }
        MusicCommand.parseSearch(listOf("--page", "3", "--source", "\"plugin:source\"", "title", "mix"))!!.let {
            assertEquals("plugin:source", it.sourceId)
            assertEquals(3, it.page)
            assertEquals("title mix", it.query)
        }
    }

    @Test
    fun `search keeps option-looking text inside a greedy query`() {
        MusicCommand.parseSearch(listOf("title", "--page", "2"))!!.let {
            assertEquals(null, it.sourceId)
            assertEquals(1, it.page)
            assertEquals("title --page 2", it.query)
        }
    }
}
