package org.lolicode.moemusic.spigot

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.session.UserSessionRegistry

object Chat {
    fun locale(sender: CommandSender): String =
        if (sender is Player) {
            Localization.resolveLocale(UserSessionRegistry.localeFor(sender.uniqueId) ?: sender.locale)
        } else {
            Localization.defaultLocale()
        }

    fun render(sender: CommandSender, text: LocalizedText): String = Localization.render(locale(sender), text)

    fun success(sender: CommandSender, text: LocalizedText) {
        sender.sendMessage("${ChatColor.GRAY}[MoeMusic] ${ChatColor.WHITE}${render(sender, text)}")
    }

    fun failure(sender: CommandSender, text: LocalizedText) {
        sender.sendMessage("${ChatColor.RED}[MoeMusic] ${render(sender, text)}")
    }

    fun plain(sender: CommandSender, text: String) {
        sender.sendMessage("${ChatColor.GRAY}[MoeMusic] ${ChatColor.WHITE}$text")
    }
}
