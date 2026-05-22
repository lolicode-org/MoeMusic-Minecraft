package org.lolicode.moemusic.platform.text

import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextComponent
import net.minecraft.network.chat.TranslatableComponent

object McText {
    fun literal(text: String): MutableComponent =
        TextComponent(text)

    fun translatable(key: String, vararg args: Any?): MutableComponent =
        TranslatableComponent(key, *args.map { it ?: "" }.toTypedArray())
}
