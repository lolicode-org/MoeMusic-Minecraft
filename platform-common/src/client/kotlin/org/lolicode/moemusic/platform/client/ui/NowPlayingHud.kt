package org.lolicode.moemusic.platform.client.ui

import org.lolicode.moemusic.platform.text.McText

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Vector3f
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.clientcore.hud.NowPlayingHudModel
import org.lolicode.moemusic.clientcore.hud.NowPlayingHudModel.Layout
import org.lolicode.moemusic.clientcore.media.ClientMediaFirewall
import org.lolicode.moemusic.clientcore.media.computeCoverDecodeDownscaleFactor
import org.lolicode.moemusic.core.config.HudTextAlignment
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.config.NowPlayingHudConfig
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.slf4j.LoggerFactory
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

object NowPlayingHud {

    private data class CoverTextures(
        val square: ResourceLocation,
        val circular: ResourceLocation,
    )

    private data class CoverArtLimits(
        val maxDownloadBytes: Int,
        val maxSourceDimension: Int,
        val maxSourcePixels: Long,
        val maxDecodeDownscaleFactor: Int,
        val maxTextureSize: Int,
    )

    private sealed interface CoverTexturesState
    private data object Loading : CoverTexturesState
    private data object Failed : CoverTexturesState
    private data class Ready(val textures: CoverTextures) : CoverTexturesState

    private val logger = LoggerFactory.getLogger(NowPlayingHud::class.java)
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val coverCache = LinkedHashMap<String, CoverTexturesState>(16, 0.75f, true)
    private val coverCacheLock = Any()
    private val logoTextureLock = Any()
    private val logoCircularCache = ConcurrentHashMap<String, ResourceLocation>()
    private val logoCircularLoadFailures = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var logoSquareTexture: ResourceLocation? = null

    @Volatile
    private var logoTextureLoadFailed = false

    private const val DEFAULT_PLACEHOLDER_COLOR = 0xFF555555.toInt()
    private const val DEFAULT_PLACEHOLDER_TEXTURE_SIZE = 128
    private const val MAX_COVER_CACHE_ENTRIES = 64
    private const val COVER_CONNECT_TIMEOUT_MS = 5_000
    private const val COVER_READ_TIMEOUT_MS = 5_000
    private const val MOD_ID = "moemusic"
    private val MOD_LOGO_RESOURCE_ID = ResourceLocation(MOD_ID, "icon.png")

    fun render(poseStack: PoseStack, tickDelta: Float) {
        val mc = Minecraft.getInstance()
        if (mc.options.renderDebug) return

        val ctx = ClientPlaybackHandler.currentContext ?: return
        val config = ModConfigManager.config.client.nowPlayingHud
        if (!config.enabled) return

        val context = GuiGraphics(poseStack, mc.window.guiScaledWidth, mc.window.guiScaledHeight)
        val layout = computeLayout(context, mc.font, ctx, config) ?: return
        renderPanel(context, mc.font, ctx, config, layout)
    }

    private fun computeLayout(
        gfx: GuiGraphics,
        font: Font,
        ctx: TrackContext,
        config: NowPlayingHudConfig,
    ): Layout? {
        val positionMs = computePositionMs(ctx)
        val metadataLines = NowPlayingHudModel.metadataLines(ctx, config, positionMs)
        val primaryLyricText = ClientPlaybackHandler.currentLyricLine(positionMs)?.text
        val secondaryLyricText = ClientPlaybackHandler.currentSecondaryLyricLine(positionMs)?.text
        val primaryLyric = NowPlayingHudModel.lyricLine(primaryLyricText, config.textColorArgb, config.showLyrics)
        val secondaryLyric = NowPlayingHudModel.lyricLine(secondaryLyricText, config.secondaryTextColorArgb, config.showLyrics)
        return NowPlayingHudModel.computeLayout(
            guiWidth = gfx.guiWidth(),
            guiHeight = gfx.guiHeight(),
            fontLineHeight = font.lineHeight,
            config = config,
            metadataLines = metadataLines,
            primaryLyric = primaryLyric,
            secondaryLyric = secondaryLyric,
        )
    }

    private fun renderPanel(
        gfx: GuiGraphics,
        font: Font,
        ctx: TrackContext,
        config: NowPlayingHudConfig,
        layout: Layout,
    ) {
        if (config.showBackground) {
            gfx.fill(
                layout.panelX,
                layout.panelY,
                layout.panelX + layout.panelWidth,
                layout.panelY + layout.panelHeight,
                parseArgb(config.backgroundColorArgb),
            )
        }
        renderCover(gfx, ctx, config, layout)
        renderText(gfx, font, config, layout)
        renderProgressBar(gfx, ctx, config, layout)
    }

    private fun renderCover(
        gfx: GuiGraphics,
        ctx: TrackContext,
        config: NowPlayingHudConfig,
        layout: Layout,
    ) {
        if (layout.coverSize <= 0) return

        val state = ctx.track.coverUrl?.takeIf { it.isNotBlank() }?.let {
            getCoverTextures(it, config.recordRingColorArgb, config.showCenterDot)
        }
        val downloadedCover = (state as? Ready)?.textures
        val fallbackTexture = if (downloadedCover == null) {
            getLogoTexture(config.circularCover, config.recordRingColorArgb, config.showCenterDot)
        } else {
            null
        }
        val textureId = downloadedCover
            ?.let { if (config.circularCover) it.circular else it.square }
            ?: fallbackTexture

        if (textureId == null) {
            gfx.fill(
                layout.coverX,
                layout.coverY,
                layout.coverX + layout.coverSize,
                layout.coverY + layout.coverSize,
                DEFAULT_PLACEHOLDER_COLOR,
            )
            return
        }

        if (config.circularCover) {
            renderCircularCover(gfx, ctx, config, layout, textureId)
            return
        }

        gfx.blit(
            textureId,
            layout.coverX,
            layout.coverY,
            0f,
            0f,
            layout.coverSize,
            layout.coverSize,
            layout.coverSize,
            layout.coverSize,
        )
    }

    private fun renderCircularCover(
        gfx: GuiGraphics,
        ctx: TrackContext,
        config: NowPlayingHudConfig,
        layout: Layout,
        textureId: ResourceLocation,
    ) {
        val shouldSpin = config.spinCover && ctx.state is PlaybackState.Playing
        if (!shouldSpin) {
            gfx.blit(
                textureId,
                layout.coverX,
                layout.coverY,
                0f,
                0f,
                layout.coverSize,
                layout.coverSize,
                layout.coverSize,
                layout.coverSize,
            )
            return
        }

        val centerX = layout.coverX + layout.coverSize / 2f
        val centerY = layout.coverY + layout.coverSize / 2f
        val rotation = ((computePositionMs(ctx) / 1000.0) * (PI / 6.0)).toFloat()
        val pose = gfx.pose()
        pose.pushPose()
        pose.translate(centerX.toDouble(), centerY.toDouble(), 0.0)
        pose.mulPose(Vector3f.ZP.rotation(rotation))
        pose.translate((-centerX).toDouble(), (-centerY).toDouble(), 0.0)
        gfx.blit(
            textureId,
            layout.coverX,
            layout.coverY,
            0f,
            0f,
            layout.coverSize,
            layout.coverSize,
            layout.coverSize,
            layout.coverSize,
        )
        pose.popPose()
    }

    private fun renderText(
        gfx: GuiGraphics,
        font: Font,
        config: NowPlayingHudConfig,
        layout: Layout,
    ) {
        if (layout.textWidth <= 0) return

        var y = layout.contentY + ((layout.contentHeight - layout.textBlockHeight) / 2).coerceAtLeast(0)
        val lines = buildList {
            addAll(layout.metadataLines)
            layout.primaryLyric?.let(::add)
            layout.secondaryLyric?.let(::add)
        }
        lines.forEachIndexed { index, line ->
            renderScaledText(
                gfx = gfx,
                font = font,
                text = line.text,
                left = layout.textX,
                top = y,
                maxWidth = layout.textWidth,
                scale = layout.textScale,
                color = line.color,
                alignment = config.textAlignment,
                shadow = !config.showBackground,
            )
            y += layout.scaledFontHeight
            if (index != lines.lastIndex) {
                y += layout.textLineGap
            }
        }
    }

    private fun renderScaledText(
        gfx: GuiGraphics,
        font: Font,
        text: String,
        left: Int,
        top: Int,
        maxWidth: Int,
        scale: Float,
        color: Int,
        alignment: HudTextAlignment,
        shadow: Boolean,
    ) {
        val truncated = truncateScaled(text, maxWidth, font, scale)
        val screenWidth = scaledTextWidth(truncated, font, scale)
        val drawX = when (alignment) {
            HudTextAlignment.LEFT -> left
            HudTextAlignment.RIGHT -> left + (maxWidth - screenWidth).coerceAtLeast(0)
        }
        val pose = gfx.pose()
        pose.pushPose()
        pose.translate(drawX.toDouble(), top.toDouble(), 0.0)
        pose.scale(scale, scale, 1.0f)
        gfx.drawString(font, McText.literal(truncated), 0, 0, color, shadow)
        pose.popPose()
    }

    private fun renderProgressBar(
        gfx: GuiGraphics,
        ctx: TrackContext,
        config: NowPlayingHudConfig,
        layout: Layout,
    ) {
        val progressY = layout.progressY ?: return
        val barColor = if (ctx.state is PlaybackState.Paused) {
            parseArgb(config.pausedProgressBarColorArgb)
        } else {
            parseArgb(config.progressBarColorArgb)
        }
        val backgroundColor = parseArgb(config.progressBarBackgroundColorArgb)
        gfx.fill(
            layout.progressX,
            progressY,
            layout.progressX + layout.progressWidth,
            progressY + layout.progressHeight,
            backgroundColor,
        )
        val filled = (layout.progressWidth * computeProgress(ctx)).toInt().coerceIn(0, layout.progressWidth)
        if (filled > 0) {
            gfx.fill(
                layout.progressX,
                progressY,
                layout.progressX + filled,
                progressY + layout.progressHeight,
                barColor,
            )
        }
    }

    private fun computeProgress(ctx: TrackContext): Float {
        val posMs = computePositionMs(ctx)
        return NowPlayingHudModel.computeProgress(ctx, posMs)
    }

    private fun computePositionMs(ctx: TrackContext): Long = ClientPlaybackHandler.currentPositionMs(ctx)

    private fun truncateScaled(text: String, maxWidth: Int, font: Font, scale: Float): String {
        val rawMaxWidth = (maxWidth / scale).toInt().coerceAtLeast(1)
        if (font.width(text) <= rawMaxWidth) return text
        var candidate = text
        while (candidate.isNotEmpty() && font.width("$candidate…") > rawMaxWidth) {
            candidate = candidate.dropLast(1)
        }
        return "$candidate…"
    }

    private fun scaledTextWidth(text: String, font: Font, scale: Float): Int =
        (font.width(text) * scale).roundToInt()

    private fun parseArgb(value: String): Int = NowPlayingHudModel.parseArgb(value)

    private fun getLogoTexture(
        circular: Boolean,
        recordRingColorArgb: String,
        showCenterDot: Boolean,
    ): ResourceLocation? = if (circular) {
        getLogoCircularTexture(recordRingColorArgb, showCenterDot) ?: getLogoSquareTexture()
    } else {
        getLogoSquareTexture()
    }

    private fun getLogoSquareTexture(): ResourceLocation? {
        logoSquareTexture?.let { return it }
        if (logoTextureLoadFailed) return null

        synchronized(logoTextureLock) {
            logoSquareTexture?.let { return it }
            if (logoTextureLoadFailed) return null

            return try {
                val source = loadModLogoImage()
                val normalized = source.use { source ->
                    createSquareCover(source, DEFAULT_PLACEHOLDER_TEXTURE_SIZE)
                }
                val id = ResourceLocation(MOD_ID, "cover/fallback_logo")
                var registered = false
                try {
                    Minecraft.getInstance().textureManager.register(
                        id,
                        DynamicTexture(normalized),
                    )
                    registered = true
                } finally {
                    if (!registered) normalized.close()
                }
                logoSquareTexture = id
                id
            } catch (e: Exception) {
                logoTextureLoadFailed = true
                logger.warn("Failed to load MoeMusic logo cover fallback: {}", e.message)
                null
            }
        }
    }

    private fun getLogoCircularTexture(recordRingColorArgb: String, showCenterDot: Boolean): ResourceLocation? {
        val cacheKey = "$recordRingColorArgb#$showCenterDot"
        logoCircularCache[cacheKey]?.let { return it }
        if (logoTextureLoadFailed || cacheKey in logoCircularLoadFailures) return null

        synchronized(logoTextureLock) {
            logoCircularCache[cacheKey]?.let { return it }
            if (logoTextureLoadFailed || cacheKey in logoCircularLoadFailures) return null

            return try {
                val source = loadModLogoImage()
                val normalized = source.use { source ->
                    createSquareCover(source, DEFAULT_PLACEHOLDER_TEXTURE_SIZE)
                }
                val circular = normalized.use { normalized ->
                    createCircularRecordCover(normalized, parseArgb(recordRingColorArgb), showCenterDot)
                }
                val safeKey = cacheKey.hashCode().toUInt().toString(16)
                val id = ResourceLocation(MOD_ID, "cover/fallback_logo_record_$safeKey")
                var registered = false
                try {
                    Minecraft.getInstance().textureManager.register(
                        id,
                        DynamicTexture(circular),
                    )
                    registered = true
                } finally {
                    if (!registered) circular.close()
                }
                logoCircularCache[cacheKey] = id
                id
            } catch (e: Exception) {
                logoCircularLoadFailures.add(cacheKey)
                logger.warn("Failed to load MoeMusic logo record fallback: {}", e.message)
                null
            }
        }
    }

    private fun loadModLogoImage(): NativeImage =
        Minecraft.getInstance().resourceManager.getResource(MOD_LOGO_RESOURCE_ID).inputStream.use { NativeImage.read(it) }

    private fun getCoverTextures(url: String, recordRingColorArgb: String, showCenterDot: Boolean): CoverTexturesState {
        val cacheKey = "$url#$recordRingColorArgb#$showCenterDot"
        val cached = synchronized(coverCacheLock) { coverCache[cacheKey] }
        if (cached != null) return cached

        when (val verdict = ClientMediaFirewall.evaluate(url)) {
            MediaUrlPolicyResult.Allow -> Unit
            is MediaUrlPolicyResult.Reject -> {
                logger.info("Blocked cover art URL {}: {}", url, verdict.reason)
                synchronized(coverCacheLock) {
                    coverCache[cacheKey] = Failed
                    evictCoverCacheLocked()
                }
                return Failed
            }
        }

        synchronized(coverCacheLock) {
            coverCache[cacheKey] = Loading
            evictCoverCacheLocked()
        }
        downloadScope.launch {
            try {
                val limits = currentCoverArtLimits()
                val sourceImage = loadCoverImage(url, limits)
                val normalized = createSquareCover(sourceImage, limits.maxTextureSize)
                sourceImage.close()
                val circular = createCircularRecordCover(normalized, parseArgb(recordRingColorArgb), showCenterDot)
                Minecraft.getInstance().execute {
                    try {
                        val stillWanted = synchronized(coverCacheLock) { coverCache[cacheKey] == Loading }
                        if (!stillWanted) {
                            normalized.close()
                            circular.close()
                            return@execute
                        }
                        val safeKey = cacheKey.hashCode().toUInt().toString(16)
                        val squareId = ResourceLocation("moemusic", "cover/$safeKey")
                        val circularId = ResourceLocation("moemusic", "cover/${safeKey}_record")
                        Minecraft.getInstance().textureManager.register(
                            squareId,
                            DynamicTexture(normalized),
                        )
                        Minecraft.getInstance().textureManager.register(
                            circularId,
                            DynamicTexture(circular),
                        )
                        synchronized(coverCacheLock) {
                            coverCache[cacheKey] = Ready(CoverTextures(square = squareId, circular = circularId))
                            evictCoverCacheLocked()
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to register cover textures for {}: {}", url, e.message)
                        normalized.close()
                        circular.close()
                        synchronized(coverCacheLock) {
                            coverCache[cacheKey] = Failed
                            evictCoverCacheLocked()
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to download cover art from {}: {}", url, e.message)
                synchronized(coverCacheLock) {
                    coverCache[cacheKey] = Failed
                    evictCoverCacheLocked()
                }
            }
        }
        return Loading
    }

    private fun currentCoverArtLimits(): CoverArtLimits {
        val config = ModConfigManager.config.client.coverArt.normalized()
        return CoverArtLimits(
            maxDownloadBytes = config.maxDownloadMebibytes * 1024 * 1024,
            maxSourceDimension = config.maxSourceDimension,
            maxSourcePixels = config.maxSourcePixels,
            maxDecodeDownscaleFactor = config.maxDecodeDownscaleFactor,
            maxTextureSize = config.maxTextureSize,
        )
    }

    private fun loadCoverImage(url: String, limits: CoverArtLimits): NativeImage {
        val connection = openCoverConnection(url)
        val bytes = try {
            val contentLength = connection.contentLengthLong
            if (contentLength > limits.maxDownloadBytes) {
                throw IllegalArgumentException("Image download too large: $contentLength bytes")
            }
            connection.getInputStream().use { stream -> readAllBytesLimited(stream, limits.maxDownloadBytes) }
        } finally {
            if (connection is HttpURLConnection) {
                connection.disconnect()
            }
        }
        return decodeCoverBytes(bytes, limits)
    }

    private fun openCoverConnection(url: String): URLConnection {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = COVER_CONNECT_TIMEOUT_MS
        connection.readTimeout = COVER_READ_TIMEOUT_MS
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
        }
        return connection
    }

    private fun decodeCoverBytes(bytes: ByteArray, limits: CoverArtLimits): NativeImage =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { imageStream ->
            val reader = ImageIO.getImageReaders(imageStream).asSequence().firstOrNull()
                ?: throw IllegalArgumentException("Unsupported or corrupted image format")
            reader.useImage(imageStream) {
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0) {
                    throw IllegalArgumentException("Image has invalid dimensions: ${width}x$height")
                }
                if (width > limits.maxSourceDimension || height > limits.maxSourceDimension) {
                    throw IllegalArgumentException("Image dimensions exceed cap: ${width}x$height")
                }
                if (width.toLong() * height.toLong() > limits.maxSourcePixels) {
                    throw IllegalArgumentException("Image pixel count exceeds cap: ${width}x$height")
                }
                val squareSize = minOf(width, height).coerceAtLeast(1)
                val startX = ((width - squareSize) / 2).coerceAtLeast(0)
                val startY = ((height - squareSize) / 2).coerceAtLeast(0)
                val downscaleFactor = computeCoverDecodeDownscaleFactor(
                    sourceWidth = squareSize,
                    sourceHeight = squareSize,
                    maxTextureSize = limits.maxTextureSize,
                    maxDecodeDownscaleFactor = limits.maxDecodeDownscaleFactor,
                )
                val readParam = reader.defaultReadParam.apply {
                    sourceRegion = Rectangle(startX, startY, squareSize, squareSize)
                    if (downscaleFactor > 1) {
                        setSourceSubsampling(downscaleFactor, downscaleFactor, 0, 0)
                    }
                }
                val bufferedImage = reader.read(0, readParam)
                    ?: throw IllegalArgumentException("Unsupported or corrupted image format")
                bufferedImage.toNativeImage()
            }
        }

    private fun BufferedImage.toNativeImage(): NativeImage {
        val nativeImage = NativeImage(width, height, true)
        val argbPixels = getRGB(0, 0, width, height, null, 0, width)
        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                nativeImage.setPixelRGBA(x, y, argbToAbgr(argbPixels[index++]))
            }
        }
        return nativeImage
    }

    private fun argbToAbgr(argb: Int): Int =
        (argb and -0x1000000) or
                (argb and 0x0000FF00) or
                ((argb and 0x00FF0000) ushr 16) or
                ((argb and 0x000000FF) shl 16)

    private inline fun <T> ImageReader.useImage(
        imageStream: Any,
        block: () -> T,
    ): T {
        try {
            input = imageStream
            return block()
        } finally {
            dispose()
        }
    }

    private fun readAllBytesLimited(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(8_192)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) {
                throw IllegalArgumentException("Image download exceeded $limit bytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun evictCoverCacheLocked() {
        while (coverCache.size > MAX_COVER_CACHE_ENTRIES) {
            val iterator = coverCache.entries.iterator()
            if (!iterator.hasNext()) return
            val eldest = iterator.next()
            iterator.remove()
            val ready = eldest.value as? Ready ?: continue
            releaseTexture(ready.textures.square)
            releaseTexture(ready.textures.circular)
        }
    }

    private fun releaseTexture(id: ResourceLocation) {
        Minecraft.getInstance().execute {
            runCatching { Minecraft.getInstance().textureManager.release(id) }
                .onFailure { logger.debug("Failed to release cover texture {}: {}", id, it.message) }
        }
    }

    private fun createSquareCover(source: NativeImage, maxTextureSize: Int): NativeImage {
        val size = minOf(source.width, source.height).coerceAtLeast(1)
        val startX = ((source.width - size) / 2).coerceAtLeast(0)
        val startY = ((source.height - size) / 2).coerceAtLeast(0)
        val targetSize = minOf(size, maxTextureSize).coerceAtLeast(1)
        val result = NativeImage(targetSize, targetSize, true)
        source.resizeSubRectTo(startX, startY, size, size, result)
        return result
    }

    private fun createCircularRecordCover(source: NativeImage, ringColor: Int, showCenterDot: Boolean): NativeImage {
        val size = source.width
        val result = NativeImage(size, size, true)
        val radius = size / 2f
        val center = (size - 1) / 2f
        val innerImageRadius = radius * 0.84f
        val centerDotRadius = radius * 0.12f
        val innerImageSize = (size * 0.84f).roundToInt().coerceAtLeast(1)
        val imageStart = ((size - innerImageSize) / 2).coerceAtLeast(0)
        val scaledImage = NativeImage(innerImageSize, innerImageSize, true)
        source.resizeSubRectTo(0, 0, source.width, source.height, scaledImage)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - center
                val dy = y - center
                val distance = sqrt(dx * dx + dy * dy)
                val pixel = when {
                    distance > radius -> 0
                    showCenterDot && distance <= centerDotRadius -> ringColor
                    distance <= innerImageRadius &&
                            x in imageStart until (imageStart + innerImageSize) &&
                            y in imageStart until (imageStart + innerImageSize) ->
                        scaledImage.getPixelRGBA(x - imageStart, y - imageStart)

                    else -> ringColor
                }
                result.setPixelRGBA(x, y, pixel)
            }
        }

        scaledImage.close()
        return result
    }
}
