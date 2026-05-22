package org.lolicode.moemusic.platform.chat

import org.lolicode.moemusic.platform.text.McText

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.LocalizedTextArg
import org.lolicode.moemusic.core.i18n.Localization

internal object LocalizedChatRenderer {

    enum class Tone {
        SUCCESS,
        FAILURE,
        NEUTRAL,
    }

    private data class Theme(
        val bodyColor: String,
        val textArgColor: String,
        val numberArgColor: String,
    ) {
        fun nested(argColor: String): Theme = Theme(
            bodyColor = argColor,
            textArgColor = numberArgColor,
            numberArgColor = numberArgColor,
        )
    }

    private val placeholderPattern = Regex("%(?:(\\d+)\\$)?([A-Za-z%])")
    private val simpleNumberPattern = Regex("^[+-]?\\d+$")

    fun prefixed(locale: String, text: LocalizedText, tone: Tone): MutableComponent =
        prefixed(component(locale, text, tone), tone)

    fun prefixed(body: Component, tone: Tone): MutableComponent =
        McText.literal(prefix(tone)).append(body)

    fun component(locale: String, text: LocalizedText, tone: Tone = Tone.NEUTRAL): MutableComponent =
        McText.literal(renderString(locale, text, tone))

    internal fun prefixedString(locale: String, text: LocalizedText, tone: Tone): String {
        return prefix(tone) + renderString(locale, text, tone)
    }

    internal fun renderString(locale: String, text: LocalizedText, tone: Tone = Tone.NEUTRAL): String =
        render(locale, text, themeFor(tone))

    private fun render(locale: String, text: LocalizedText, theme: Theme): String = when (text) {
        is LocalizedText.Plain -> theme.bodyColor + text.text
        is LocalizedText.Key -> renderTemplate(
            template = Localization.get(locale, text.key),
            args = text.args,
            locale = locale,
            theme = theme,
        )
    }

    private fun renderTemplate(
        template: String,
        args: List<LocalizedTextArg>,
        locale: String,
        theme: Theme,
    ): String {
        val result = StringBuilder(theme.bodyColor)
        var nextSequentialIndex = 0
        var lastEnd = 0

        for (match in placeholderPattern.findAll(template)) {
            result.append(template, lastEnd, match.range.first)
            when (match.groupValues[2]) {
                "%" -> result.append('%')
                "s" -> {
                    val explicitIndex = match.groupValues[1].takeIf(String::isNotEmpty)?.toInt()?.minus(1)
                    val index = explicitIndex ?: nextSequentialIndex++
                    val arg = args.getOrNull(index)
                    if (arg == null) {
                        result.append(match.value)
                    } else {
                        val argColor = argColor(arg, theme)
                        result.append(renderArg(locale, arg, theme.nested(argColor), argColor))
                        result.append(theme.bodyColor)
                    }
                }

                else -> result.append(match.value)
            }
            lastEnd = match.range.last + 1
        }

        result.append(template, lastEnd, template.length)
        return result.toString()
    }

    private fun renderArg(
        locale: String,
        arg: LocalizedTextArg,
        nestedTheme: Theme,
        argColor: String,
    ): String = when (arg) {
        is LocalizedTextArg.Text -> render(locale, arg.value, nestedTheme)
        is LocalizedTextArg.Value -> argColor + arg.value
    }

    private fun argColor(arg: LocalizedTextArg, theme: Theme): String = when (arg) {
        is LocalizedTextArg.Text -> theme.textArgColor
        is LocalizedTextArg.Value -> if (simpleNumberPattern.matches(arg.value)) theme.numberArgColor else theme.textArgColor
    }

    private fun prefix(tone: Tone): String {
        val prefixColor = when (tone) {
            Tone.FAILURE -> "§c"
            Tone.SUCCESS, Tone.NEUTRAL -> "§7"
        }
        return "$prefixColor[MoeMusic] "
    }

    private fun themeFor(tone: Tone): Theme = when (tone) {
        Tone.SUCCESS, Tone.NEUTRAL -> Theme(
            bodyColor = "§f",
            textArgColor = "§b",
            numberArgColor = "§e",
        )

        Tone.FAILURE -> Theme(
            bodyColor = "§c",
            textArgColor = "§6",
            numberArgColor = "§e",
        )
    }
}
