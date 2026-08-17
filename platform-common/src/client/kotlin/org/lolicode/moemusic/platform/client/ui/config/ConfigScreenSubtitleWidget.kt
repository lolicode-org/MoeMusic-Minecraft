package org.lolicode.moemusic.platform.client.ui.config

import me.shedaniel.clothconfig2.api.Tooltip
import me.shedaniel.clothconfig2.gui.AbstractConfigScreen
import me.shedaniel.math.Point
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lolicode.moemusic.platform.text.McText

/**
 * Subtitle widget displayed under the main screen title in the MoeMusic config screen.
 */
internal class ConfigScreenSubtitleWidget(
    private val screen: Screen,
    private val text: Component = McText.translatable("config.moemusic.client_only_notice"),
) : Renderable {

    companion object {
        private const val SUBTITLE_Y = 29
        private const val SUBTITLE_COLOR = 0xFFAAAAAA.toInt() // Muted gray (§7)
        private const val HORIZONTAL_MARGIN = 40
        private const val MIN_AVAILABLE_WIDTH = 60

        fun attach(screen: Screen) {
            val widget = ConfigScreenSubtitleWidget(screen)
            attachRenderable(screen, widget)
        }

        private fun attachRenderable(screen: Screen, renderable: Renderable) {
            try {
                val method = Screen::class.java.declaredMethods.firstOrNull {
                    it.parameterCount == 1 &&
                        it.parameterTypes[0].isAssignableFrom(renderable.javaClass) &&
                        (it.name == "addRenderableOnly" || it.name == "method_37063")
                }
                if (method != null) {
                    method.isAccessible = true
                    method.invoke(screen, renderable)
                    return
                }
            } catch (_: Throwable) {}

            try {
                val field = Screen::class.java.declaredFields.firstOrNull {
                    it.name == "renderables" || it.name == "field_33816"
                }
                if (field != null) {
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (field.get(screen) as? MutableList<Renderable>)?.add(renderable)
                }
            } catch (_: Throwable) {}
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val minecraft = Minecraft.getInstance()
        val font = minecraft.font
        val screenWidth = screen.width
        val maxAvailableWidth = (screenWidth - HORIZONTAL_MARGIN).coerceAtLeast(MIN_AVAILABLE_WIDTH)
        val textString = text.string
        val fullWidth = font.width(textString)

        val (displayComponent, renderedWidth) = if (fullWidth <= maxAvailableWidth) {
            text to fullWidth
        } else {
            val truncated = truncateWithEllipsis(font, textString, maxAvailableWidth)
            McText.literal(truncated) to font.width(truncated)
        }

        context.centeredText(
            font,
            displayComponent,
            screenWidth / 2,
            SUBTITLE_Y,
            SUBTITLE_COLOR,
        )

        val left = (screenWidth - renderedWidth) / 2
        val right = left + renderedWidth
        val isHovered = mouseX in left..right && mouseY in (SUBTITLE_Y - 2)..(SUBTITLE_Y + 10)

        if (isHovered && screen is AbstractConfigScreen) {
            screen.addTooltip(Tooltip.of(Point(mouseX, mouseY), text))
        }
    }

    private fun truncateWithEllipsis(font: Font, str: String, maxWidth: Int): String {
        if (font.width(str) <= maxWidth) return str
        var t = str
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) {
            t = t.dropLast(1)
        }
        return if (t.isNotEmpty()) "$t…" else ""
    }
}
