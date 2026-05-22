package org.lolicode.moemusic.platform.client.ui

import org.lolicode.moemusic.platform.text.McText

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiComponent
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence

internal class GuiGraphics(
    private val poseStack: PoseStack,
    private val width: Int,
    private val height: Int,
) {
    fun pose(): PoseStack = poseStack

    fun guiWidth(): Int = width

    fun guiHeight(): Int = height

    fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        GuiComponent.fill(poseStack, left, top, right, bottom, color)
    }

    fun drawString(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean): Int =
        if (shadow) {
            font.drawShadow(poseStack, text, x.toFloat(), y.toFloat(), color)
        } else {
            font.draw(poseStack, text, x.toFloat(), y.toFloat(), color)
        }

    fun drawString(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean = false): Int =
        if (shadow) {
            font.drawShadow(poseStack, text, x.toFloat(), y.toFloat(), color)
        } else {
            font.draw(poseStack, text, x.toFloat(), y.toFloat(), color)
        }

    fun drawCenteredString(font: Font, text: Component, centerX: Int, y: Int, color: Int) {
        GuiComponent.drawCenteredString(poseStack, font, text, centerX, y, color)
    }

    fun drawWordWrap(font: Font, text: FormattedText, x: Int, y: Int, lineWidth: Int, color: Int) {
        font.drawWordWrap(text, x, y, lineWidth, color)
    }

    fun blit(
        texture: ResourceLocation,
        x: Int,
        y: Int,
        u: Float,
        v: Float,
        width: Int,
        height: Int,
        textureWidth: Int,
        textureHeight: Int,
    ) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader)
        RenderSystem.setShaderTexture(0, texture)
        GuiComponent.blit(poseStack, x, y, 0, u, v, width, height, textureWidth, textureHeight)
    }
}

internal fun button(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    onPress: (Button) -> Unit,
): Button = CompactButton(x, y, width, height, message, Button.OnPress(onPress))

internal fun editBox(
    font: Font,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
): EditBox = HintEditBox(font, x, y, width, height, message)

internal fun EditBox.setHintCompat(hint: Component) {
    if (this is HintEditBox) {
        this.hint = hint
    } else {
        setSuggestion(null)
    }
}

internal fun centeredTextY(y: Int, height: Int, font: Font): Int =
    y + (height - font.lineHeight) / 2 + 1

internal fun truncateToWidth(text: String, maxWidth: Int, font: Font): String {
    if (maxWidth <= 0) return ""
    if (font.width(text) <= maxWidth) return text

    val ellipsis = "..."
    val ellipsisWidth = font.width(ellipsis)
    if (ellipsisWidth >= maxWidth) return ""

    var truncated = text
    while (truncated.isNotEmpty() && font.width(truncated) + ellipsisWidth > maxWidth) {
        truncated = truncated.dropLast(1)
    }
    return "$truncated$ellipsis"
}

private class CompactButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    onPress: OnPress,
) : Button(x, y, width, height, message, onPress) {
    override fun renderButton(poseStack: PoseStack, mouseX: Int, mouseY: Int, delta: Float) {
        val font = Minecraft.getInstance().font
        val hovered = active && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
        val fillColor = when {
            !active -> 0xFF3A3A3A.toInt()
            hovered -> 0xFF4444AA.toInt()
            else -> 0xFF333355.toInt()
        }
        val borderColor = if (active) 0xFF666688.toInt() else 0xFF555555.toInt()
        val textColor = if (active) 0xFFFFFFFF.toInt() else 0xFF999999.toInt()

        fill(poseStack, x, y, x + width, y + height, fillColor)
        fill(poseStack, x, y, x + width, y + 1, borderColor)
        fill(poseStack, x, y + height - 1, x + width, y + height, borderColor)
        fill(poseStack, x, y, x + 1, y + height, borderColor)
        fill(poseStack, x + width - 1, y, x + width, y + height, borderColor)

        val label = truncateToWidth(message.string, width - 6, font)
        drawCenteredString(
            poseStack,
            font,
            McText.literal(label),
            x + width / 2,
            centeredTextY(y, height, font),
            textColor,
        )
    }
}

private class HintEditBox(
    font: Font,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
) : EditBox(font, x, y, width, height, message) {
    var hint: Component? = null

    override fun renderButton(poseStack: PoseStack, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderButton(poseStack, mouseX, mouseY, delta)
        if (value.isNotEmpty() || isFocused) return

        val hintText = hint ?: return
        val font = Minecraft.getInstance().font
        font.draw(
            poseStack,
            hintText,
            (x + 4).toFloat(),
            centeredTextY(y, height, font).toFloat(),
            0xFF777777.toInt(),
        )
    }
}
