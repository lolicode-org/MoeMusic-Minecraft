package org.lolicode.moemusic.spigot

import org.junit.jupiter.api.Test
import java.util.logging.Logger
import kotlin.test.assertTrue

class PaperLogFormatterPatcherTest {

    @Test
    fun `patch does not throw when log4j or paper appenders are absent`() {
        val logger = Logger.getLogger("TestLogger")
        // Should execute smoothly and fail gracefully without exceptions
        PaperLogFormatterPatcher.patch("MoeMusic", logger)
        assertTrue(true)
    }
}
