package org.lolicode.moemusic.spigot

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.LocalizedTextArg
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.session.UserSessionRegistry
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent

@Suppress("DEPRECATION")
object Chat {
    fun locale(sender: CommandSender): String =
        if (sender is Player) {
            Localization.resolveLocale(UserSessionRegistry.localeFor(sender.uniqueId) ?: sender.locale)
        } else {
            Localization.defaultLocale()
        }

    fun render(sender: CommandSender, text: LocalizedText): String = Localization.render(locale(sender), text)

    fun success(sender: CommandSender, text: LocalizedText) {
        message(sender, SpigotChatFormatting.prefixed(locale(sender), text, SpigotChatFormatting.Tone.SUCCESS))
    }

    fun failure(sender: CommandSender, text: LocalizedText) {
        message(sender, SpigotChatFormatting.prefixed(locale(sender), text, SpigotChatFormatting.Tone.FAILURE))
    }

    fun plain(sender: CommandSender, text: String) {
        message(sender, "§7[MoeMusic] §f$text")
    }

    fun message(sender: CommandSender, text: String) {
        message(sender, TextComponent.fromLegacyText(text).toList())
    }

    fun message(sender: CommandSender, components: Iterable<BaseComponent>) {
        val parts = components.toList()
        if (parts.isNotEmpty()) sender.spigot().sendMessage(*parts.toTypedArray())
    }

    fun clickable(
        sender: CommandSender,
        text: String,
        command: String,
        hover: LocalizedText? = null,
    ): List<BaseComponent> {
        val hoverEvent = hover?.let {
            HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText(
                    SpigotChatFormatting.render(locale(sender), it, SpigotChatFormatting.Tone.NEUTRAL),
                ),
            )
        }
        return TextComponent.fromLegacyText(text).onEach { component ->
            component.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
            component.hoverEvent = hoverEvent
        }.toList()
    }
}

internal object SpigotChatFormatting {
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
