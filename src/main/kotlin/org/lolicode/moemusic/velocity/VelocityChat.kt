package org.lolicode.moemusic.velocity

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.LocalizedTextArg
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.session.UserSessionRegistry

object VelocityChat {
    private val legacySerializer = LegacyComponentSerializer.legacySection()

    fun locale(sender: CommandSource): String =
        if (sender is Player) {
            Localization.resolveLocale(
                UserSessionRegistry.localeFor(sender.uniqueId)
                    ?: sender.effectiveLocale.toMinecraftLocale(),
            )
        } else {
            Localization.defaultLocale()
        }

    fun render(sender: CommandSource, text: LocalizedText): String =
        Localization.render(locale(sender), text)

    fun success(sender: CommandSource, text: LocalizedText) {
        message(sender, VelocityChatFormatting.prefixed(locale(sender), text, VelocityChatFormatting.Tone.SUCCESS))
    }

    fun failure(sender: CommandSource, text: LocalizedText) {
        message(sender, VelocityChatFormatting.prefixed(locale(sender), text, VelocityChatFormatting.Tone.FAILURE))
    }

    fun plain(sender: CommandSource, text: String) {
        message(sender, "§7[MoeMusic] §f$text")
    }

    fun message(sender: CommandSource, text: String) {
        message(sender, legacy(text))
    }

    fun message(sender: CommandSource, component: Component) {
        sender.sendMessage(component)
    }

    fun multiline(sender: CommandSource, lines: Iterable<Component>) {
        lines.forEach { message(sender, it) }
    }

    fun legacy(text: String): Component = legacySerializer.deserialize(text)

    internal fun hoverable(
        sender: CommandSource,
        text: String,
        hover: LocalizedText,
        tone: VelocityChatFormatting.Tone = VelocityChatFormatting.Tone.NEUTRAL,
    ): Component = interactive(sender, text, null, hover, tone)

    fun action(
        sender: CommandSource,
        label: String,
        labelColor: String,
        command: String,
        hover: LocalizedText,
    ): Component = clickable(sender, "§8[$labelColor$label§8]", command, hover)

    fun clickable(
        sender: CommandSource,
        text: String,
        command: String,
        hover: LocalizedText? = null,
    ): Component = interactive(sender, text, command, hover)

    private fun interactive(
        sender: CommandSource,
        text: String,
        command: String?,
        hover: LocalizedText?,
        tone: VelocityChatFormatting.Tone = VelocityChatFormatting.Tone.NEUTRAL,
    ): Component {
        var component = legacy(text)
        if (command != null) {
            component = component.clickEvent(ClickEvent.runCommand(command))
        }
        if (hover != null) {
            component = component.hoverEvent(
                HoverEvent.showText(
                    legacy(VelocityChatFormatting.render(locale(sender), hover, tone)),
                ),
            )
        }
        return component
    }

    private fun java.util.Locale?.toMinecraftLocale(): String =
        this?.toLanguageTag()?.replace('-', '_')?.lowercase() ?: Localization.defaultLocale()
}

internal object VelocityChatFormatting {
    internal enum class Tone {
        SUCCESS,
        FAILURE,
        NEUTRAL,
    }

    private data class Theme(
        val bodyColor: String,
        val textArgColor: String,
        val numberArgColor: String,
    ) {
        fun nested(argColor: String) = Theme(argColor, numberArgColor, numberArgColor)
    }

    private val placeholderPattern = Regex("%(?:(\\d+)\\$)?([A-Za-z%])")
    private val simpleNumberPattern = Regex("^[+-]?\\d+$")

    internal fun prefixed(locale: String, text: LocalizedText, tone: Tone): String =
        prefix(tone) + render(locale, text, tone)

    internal fun render(locale: String, text: LocalizedText, tone: Tone = Tone.NEUTRAL): String =
        render(locale, text, themeFor(tone))

    private fun render(locale: String, text: LocalizedText, theme: Theme): String = when (text) {
        is LocalizedText.Plain -> theme.bodyColor + text.text
        is LocalizedText.Key -> renderTemplate(Localization.get(locale, text.key), text.args, locale, theme)
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
                    val arg = args.getOrNull(explicitIndex ?: nextSequentialIndex++)
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
        return result.append(template, lastEnd, template.length).toString()
    }

    private fun renderArg(locale: String, arg: LocalizedTextArg, theme: Theme, argColor: String): String = when (arg) {
        is LocalizedTextArg.Text -> render(locale, arg.value, theme)
        is LocalizedTextArg.Value -> argColor + arg.value
    }

    private fun argColor(arg: LocalizedTextArg, theme: Theme): String = when (arg) {
        is LocalizedTextArg.Text -> theme.textArgColor
        is LocalizedTextArg.Value -> if (simpleNumberPattern.matches(arg.value)) theme.numberArgColor else theme.textArgColor
    }

    private fun prefix(tone: Tone): String =
        "${if (tone == Tone.FAILURE) "§c" else "§7"}[MoeMusic] "

    private fun themeFor(tone: Tone): Theme = if (tone == Tone.FAILURE) {
        Theme("§c", "§6", "§e")
    } else {
        Theme("§f", "§b", "§e")
    }
}
