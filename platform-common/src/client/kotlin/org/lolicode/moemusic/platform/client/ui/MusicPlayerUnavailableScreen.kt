package org.lolicode.moemusic.platform.client.ui

import org.lolicode.moemusic.platform.text.McText

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lolicode.moemusic.clientcore.playback.AvailabilityIssue
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler

/**
 * Fallback screen shown when the music player cannot be opened for the current connection.
 */
class MusicPlayerUnavailableScreen(
    private val parent: Screen?,
    private val fallbackIssue: AvailabilityIssue = AvailabilityIssue.SERVER_MISSING,
) : Screen(McText.translatable("screen.moemusic.unavailable.title")) {

    private var buttonIssue: AvailabilityIssue = fallbackIssue

    override fun init() {
        super.init()
        buttonIssue = currentIssue()
        rebuildButtons()
    }

    override fun tick() {
        super.tick()
        val issue = ClientPlaybackHandler.currentAvailabilityIssue()
        if (minecraft.connection != null && issue == null) {
            minecraft.setScreen(MusicPlayerScreen())
            return
        }
        val effectiveIssue = issue ?: fallbackIssue
        if (effectiveIssue != buttonIssue) {
            buttonIssue = effectiveIssue
            rebuildButtons()
        }
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        context.centeredText(font, McText.translatable(titleKey(currentIssue())), width / 2, 40, 0xFFFFFFFF.toInt())
        context.textWithWordWrap(
            font,
            bodyComponent(currentIssue()),
            width / 2 - 140,
            74,
            280,
            0xFFC0C0C0.toInt(),
        )
        super.extractRenderState(context, mouseX, mouseY, delta)
    }

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    private fun rebuildButtons() {
        clearWidgets()
        addRenderableWidget(
            Button.builder(McText.translatable(if (parent != null) "gui.back" else "gui.done")) {
                onClose()
            }.pos(width / 2 - 50, height - 40).size(100, 20).build()
        )
    }

    private fun currentIssue(): AvailabilityIssue =
        ClientPlaybackHandler.currentAvailabilityIssue() ?: fallbackIssue

    private fun titleKey(issue: AvailabilityIssue): String = when (issue) {
        AvailabilityIssue.SERVER_MISSING -> "screen.moemusic.unavailable.title"
    }

    private fun bodyComponent(issue: AvailabilityIssue): Component = when (issue) {
        AvailabilityIssue.SERVER_MISSING ->
            McText.translatable("screen.moemusic.unavailable.body")
    }
}
