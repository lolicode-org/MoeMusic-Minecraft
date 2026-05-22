package org.lolicode.moemusic.platform.text

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

object McText {
    fun literal(text: String): MutableComponent =
        Component.literal(text)

    fun translatable(key: String, vararg args: Any?): MutableComponent =
        Component.translatable(key, *args.map { it ?: "" }.toTypedArray())
}
