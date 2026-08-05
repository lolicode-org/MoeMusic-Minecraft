package org.lolicode.moemusic.velocity

import com.velocitypowered.api.command.CommandSource
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import net.kyori.adventure.text.event.ClickEvent

class VelocityChatAdventureTest {
    @Test
    fun `clickable output uses Velocity 4 Adventure payloads`() {
        val source = Proxy.newProxyInstance(
            CommandSource::class.java.classLoader,
            arrayOf(CommandSource::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        } as CommandSource

        val component = VelocityChat.clickable(source, "button", "/music help")
        val click = component.clickEvent()
        val payload = click?.payload() as ClickEvent.Payload.Text

        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action())
        assertEquals("/music help", payload.value())
    }
}
