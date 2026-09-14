package org.lolicode.moemusic.platform.client.ui

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.lolicode.moemusic.core.plugin.PluginDiscoveryReport
import org.lolicode.moemusic.platform.text.McText
import java.nio.file.Files
import java.nio.file.Path

/**
 * Startup warning screen shown when plugin issues (incompatible API, duplicate IDs, corrupt jars)
 * are detected during plugin discovery.
 */
class PluginIssueScreen(
    private val parent: Screen?,
    private val report: PluginDiscoveryReport,
    private val gameDir: Path? = null,
    private val configDir: Path? = null,
) : Screen(McText.translatable("screen.moemusic.plugin_issues.title")) {

    private data class IssueItem(
        val tag: Component,
        val tagColor: Int,
        val title: Component,
        val lines: List<Component>,
    )

    private val issues: List<IssueItem> = buildList {
        for (record in report.incompatiblePlugins) {
            val lines = mutableListOf<Component>()
            lines.add(
                McText.translatable(
                    "screen.moemusic.plugin_issues.incompatible.desc",
                    record.pluginId,
                    record.version,
                    record.supportedApiVersions,
                    record.runtimeApiVersion,
                )
            )
            lines.add(McText.literal(record.reason))
            if (record.filePath != null) {
                lines.add(McText.translatable("screen.moemusic.plugin_issues.file_path", record.filePath.toString()))
            }
            add(
                IssueItem(
                    tag = McText.translatable("screen.moemusic.plugin_issues.tag.incompatible"),
                    tagColor = 0xFFFF5555.toInt(),
                    title = McText.literal(record.pluginId),
                    lines = lines,
                )
            )
        }

        for (record in report.duplicatePlugins) {
            val lines = mutableListOf<Component>()
            lines.add(McText.translatable("screen.moemusic.plugin_issues.duplicate.selected", record.selected.plugin.version))
            val selectedPath = record.selected.filePath?.toString() ?: record.selected.origin
            lines.add(McText.translatable("screen.moemusic.plugin_issues.file_path", selectedPath))
            for (skipped in record.skipped) {
                lines.add(McText.translatable("screen.moemusic.plugin_issues.duplicate.ignored", skipped.plugin.version))
                val skippedPath = skipped.filePath?.toString() ?: skipped.origin
                lines.add(McText.translatable("screen.moemusic.plugin_issues.file_path", skippedPath))
            }
            add(
                IssueItem(
                    tag = McText.translatable("screen.moemusic.plugin_issues.tag.duplicate"),
                    tagColor = 0xFFF0C674.toInt(),
                    title = McText.literal(record.pluginId),
                    lines = lines,
                )
            )
        }

        for (record in report.failedPlugins) {
            val fileName = record.jarPath.fileName?.toString() ?: record.jarPath.toString()
            val lines = mutableListOf<Component>()
            lines.add(McText.literal(record.message))
            lines.add(McText.translatable("screen.moemusic.plugin_issues.file_path", record.jarPath.toString()))
            add(
                IssueItem(
                    tag = McText.translatable("screen.moemusic.plugin_issues.tag.failed"),
                    tagColor = 0xFFFF4444.toInt(),
                    title = McText.literal(fileName),
                    lines = lines,
                )
            )
        }
    }

    private var scrollAmount = 0.0

    override fun init() {
        super.init()
        rebuildButtons()
    }

    private fun rebuildButtons() {
        clearWidgets()

        val effectiveGameDir = gameDir ?: minecraft?.gameDirectory?.toPath()
        val effectiveConfigDir = configDir ?: effectiveGameDir?.resolve("config")?.resolve("moemusic")

        val modsDir = effectiveGameDir?.resolve("mods")
        val pluginsDir = effectiveConfigDir?.resolve("plugins")

        val btnRow1Y = height - 54
        val btnRow2Y = height - 28

        // Row 1: Folder buttons
        if (modsDir != null) {
            addRenderableWidget(
                button(
                    width / 2 - 155,
                    btnRow1Y,
                    150,
                    20,
                    McText.translatable("screen.moemusic.plugin_issues.button.open_mods"),
                ) {
                    openDirectory(modsDir)
                }
            )
        }

        if (pluginsDir != null) {
            addRenderableWidget(
                button(
                    width / 2 + 5,
                    btnRow1Y,
                    150,
                    20,
                    McText.translatable("screen.moemusic.plugin_issues.button.open_plugins"),
                ) {
                    openDirectory(pluginsDir)
                }
            )
        }

        // Row 2: Action buttons
        addRenderableWidget(
            button(
                width / 2 - 155,
                btnRow2Y,
                150,
                20,
                McText.translatable("screen.moemusic.plugin_issues.button.continue"),
            ) {
                onClose()
            }
        )

        addRenderableWidget(
            button(
                width / 2 + 5,
                btnRow2Y,
                150,
                20,
                McText.translatable("screen.moemusic.plugin_issues.button.quit"),
            ) {
                minecraft?.stop()
            }
        )
    }

    private fun openDirectory(path: Path) {
        try {
            Files.createDirectories(path)
            net.minecraft.Util.getPlatform().openFile(path.toFile())
        } catch (_: Throwable) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(path.toFile())
                }
            } catch (_: Throwable) {
                // Ignore
            }
        }
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, delta: Float) {
        val context = GuiGraphics(poseStack, width, height)
        renderBackground(poseStack)

        // Background dim
        context.fill(0, 0, width, height, 0xC0101010.toInt())

        // Header
        context.drawCenteredString(font, title, width / 2, 14, 0xFFFFFFFF.toInt())
        context.drawCenteredString(
            font,
            McText.translatable("screen.moemusic.plugin_issues.subtitle"),
            width / 2,
            28,
            0xFFAAAAAA.toInt(),
        )

        val listTop = 44
        val listBottom = height - 60
        val cardWidth = (width - 60).coerceIn(280, 440)
        val cardLeft = (width - cardWidth) / 2
        val cardRight = cardLeft + cardWidth
        val maxTextWidth = cardWidth - 16

        // Compute total content height and prepared lines
        val itemHeights = mutableListOf<Int>()
        val itemSplitLines = mutableListOf<List<List<FormattedCharSequence>>>()

        for (item in issues) {
            val linesForThisItem = mutableListOf<List<FormattedCharSequence>>()
            var itemH = 6 + font.lineHeight + 4 // Top padding + header + gap
            for (line in item.lines) {
                val split = font.split(line, maxTextWidth)
                linesForThisItem.add(split)
                itemH += split.size * (font.lineHeight + 2)
            }
            itemH += 6 // Bottom padding
            itemHeights.add(itemH)
            itemSplitLines.add(linesForThisItem)
        }

        val totalContentHeight = itemHeights.sum() + (issues.size - 1).coerceAtLeast(0) * 6
        val visibleHeight = listBottom - listTop
        val maxScroll = (totalContentHeight - visibleHeight).coerceAtLeast(0)
        scrollAmount = scrollAmount.coerceIn(0.0, maxScroll.toDouble())

        var currentY = listTop - scrollAmount.toInt()
        for (i in issues.indices) {
            val item = issues[i]
            val itemH = itemHeights[i]
            val itemBottom = currentY + itemH

            if (itemBottom >= listTop && currentY <= listBottom) {
                // Background card
                context.fill(cardLeft, currentY, cardRight, itemBottom, 0xAA1E1E1E.toInt())
                // Left accent line
                context.fill(cardLeft, currentY, cardLeft + 3, itemBottom, item.tagColor)

                // Header row
                var textY = currentY + 6
                context.drawString(font, item.tag, cardLeft + 8, textY, item.tagColor, false)
                val tagW = font.width(item.tag)
                context.drawString(font, item.title, cardLeft + 8 + tagW + 6, textY, 0xFFFFFFFF.toInt(), false)
                textY += font.lineHeight + 4

                // Details lines
                val splitLines = itemSplitLines[i]
                for (lineSplits in splitLines) {
                    for (seq in lineSplits) {
                        context.drawString(font, seq, cardLeft + 8, textY, 0xFFAAAAAA.toInt(), false)
                        textY += font.lineHeight + 2
                    }
                }
            }

            currentY += itemH + 6
        }

        // Render scrollbar if needed
        if (maxScroll > 0) {
            val scrollbarX = cardRight + 4
            val scrollbarW = 4
            val trackH = listBottom - listTop
            val thumbH = ((trackH.toFloat() / totalContentHeight.toFloat()) * trackH).toInt().coerceIn(16, trackH)
            val thumbY = listTop + ((scrollAmount / maxScroll) * (trackH - thumbH)).toInt()

            context.fill(scrollbarX, listTop, scrollbarX + scrollbarW, listBottom, 0x33FFFFFF.toInt())
            context.fill(scrollbarX, thumbY, scrollbarX + scrollbarW, thumbY + thumbH, 0x88FFFFFF.toInt())
        }

        super.render(poseStack, mouseX, mouseY, delta)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        val listTop = 44
        val listBottom = height - 60
        val cardWidth = (width - 60).coerceIn(280, 440)
        val maxTextWidth = cardWidth - 16

        var totalH = 0
        for (item in issues) {
            var itemH = 6 + font.lineHeight + 4
            for (line in item.lines) {
                val split = font.split(line, maxTextWidth)
                itemH += split.size * (font.lineHeight + 2)
            }
            itemH += 6
            totalH += itemH
        }
        totalH += (issues.size - 1).coerceAtLeast(0) * 6
        val maxScroll = (totalH - (listBottom - listTop)).coerceAtLeast(0)

        if (maxScroll > 0) {
            scrollAmount = (scrollAmount - amount * 24.0).coerceIn(0.0, maxScroll.toDouble())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, amount)
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }
}
