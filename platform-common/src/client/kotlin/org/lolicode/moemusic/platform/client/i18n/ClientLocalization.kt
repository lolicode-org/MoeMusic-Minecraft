package org.lolicode.moemusic.platform.client.i18n

import net.minecraft.client.Minecraft
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.LocalizedTextArg
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.platform.text.McText

internal object ClientLocalization {

    fun selectedLanguage(): String =
        Minecraft.getInstance().languageManager.selected

    fun render(text: LocalizedText, locale: String = selectedLanguage()): String =
        component(text, locale).string

    fun component(text: LocalizedText, locale: String = selectedLanguage()): Component = when (text) {
        is LocalizedText.Plain -> McText.literal(text.text)
        is LocalizedText.Key -> componentIfPresent(text.key, text.args, locale) ?: McText.translatable(
            text.key,
            *text.args.map { arg -> componentArg(arg, locale) }.toTypedArray(),
        )
    }

    fun componentIfPresent(
        translationKey: String,
        args: List<LocalizedTextArg> = emptyList(),
        locale: String = selectedLanguage(),
    ): Component? {
        val text = LocalizedText.Key(translationKey, args)
        if (Localization.getIfPresent(locale, translationKey) != null) {
            return McText.literal(Localization.render(locale, text))
        }
        if (Language.getInstance().has(translationKey)) {
            return McText.translatable(
                translationKey,
                *args.map { arg -> componentArg(arg, locale) }.toTypedArray(),
            )
        }
        if (Localization.get(locale, translationKey) != translationKey) {
            return McText.literal(Localization.render(locale, text))
        }
        return null
    }

    private fun componentArg(arg: LocalizedTextArg, locale: String): Any = when (arg) {
        is LocalizedTextArg.Text -> component(arg.value, locale)
        is LocalizedTextArg.Value -> arg.value
    }
}
