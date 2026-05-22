package org.lolicode.moemusic.platform.client.ui

import org.lolicode.moemusic.platform.text.McText

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.clientcore.playback.CachedSearchState
import org.lolicode.moemusic.clientcore.playback.SearchSourceInfo
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ContentFilterClientListMode
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuleEditor
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.protocol.proto.*
import org.lolicode.moemusic.core.playback.toApi
import org.lolicode.moemusic.platform.client.i18n.ClientLocalization
import org.lolicode.moemusic.platform.client.playback.ClientPlaybackHandler
import org.lolicode.moemusic.platform.client.playback.ClientVolumeRuntime
import org.lolicode.moemusic.platform.client.ui.config.ConfigScreenAccess
import java.util.*
import kotlin.math.roundToInt

/**
 * Main music player GUI screen, opened via the configured keybinding.
 *
 * Three tabs:
 *  - **Now Playing** — current track info, progress bar (click to seek), play/pause, skip, stop.
 *  - **Search** — source selector + text field + search button, scrollable results list
 *    (title, artist, album, `Queue`/`Select` button per row), with incremental page loading near
 *    the bottom of the list.
 *  - **Queue** — scrollable player-queue list (title, artist, album + source, `✕ Del` button per row),
 *    with a `↻ Refresh` button.
 *
 * Packet-driven: all actions (search, play, seek, pause/resume, skip, stop, queue remove) are
 * sent as C→S packets via [ClientPlaybackHandler]. No chat commands are used.
 *
 * Errors from the server (failure fields in response packets) are displayed inline in the
 * relevant tab rather than in chat.
 */
class MusicPlayerScreen : Screen(TITLE), ClientPlaybackHandler.GuiListener {

    // -------------------------------------------------------------------------
    // Tab state
    // -------------------------------------------------------------------------

    private enum class Tab { NOW_PLAYING, SEARCH, QUEUE }
    private enum class TrackListVariant { SEARCH, QUEUE }

    private data class InlineTextSegment(
        val text: String,
        val color: Int,
    )

    private data class RowActionMenuOption(
        val label: String,
        val action: () -> Unit,
    )

    private data class RowActionMenuState(
        val anchorX: Int,
        val anchorY: Int,
        val options: List<RowActionMenuOption>,
    )

    private data class RowActionMenuLayout(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private data class ExactFilterKey(
        val sourceId: String,
        val valueId: String,
    )

    private data class QuickAddLayout(
        val titleY: Int,
        val detailY: Int,
        val addBoxY: Int,
    )

    private data class ListControllerLayout(
        val x: Int,
        val width: Int,
        val upButtonY: Int,
        val downButtonY: Int,
        val trackY: Int,
        val trackHeight: Int,
        val thumbY: Int,
        val thumbHeight: Int,
        val thumbTravel: Int,
        val totalCount: Int,
        val visibleCount: Int,
        val currentOffset: Int,
        val maxOffset: Int,
    ) {
        fun isWithinTrack(mx: Int, my: Int): Boolean =
            mx in x..(x + width) && my in trackY..(trackY + trackHeight)

        fun isWithinThumb(mx: Int, my: Int): Boolean =
            mx in x..(x + width) && my in thumbY..(thumbY + thumbHeight)
    }

    private var currentTab = Tab.NOW_PLAYING

    // -------------------------------------------------------------------------
    // Now Playing state
    // -------------------------------------------------------------------------

    private var seekBarDragging = false
    private var seekBarDragProgress = 0f
    private var volumeBarDragging = false
    private var addIdentifierEditBox: EditBox? = null
    private var addIdentifierInputText = ""
    private var addIdentifierMode: TrackAddMode = TrackAddMode.NORMAL
    private var addIdentifierError: String? = null
    private var addIdentifierSuccess: String? = null

    // -------------------------------------------------------------------------
    // Search state
    // -------------------------------------------------------------------------

    private var searchEditBox: EditBox? = null
    private var rawSearchResults: List<SelectionEntry> = emptyList()
    private var searchResults: List<SelectionEntry> = emptyList()
    private var searchError: String? = null
    private var searchActionError: String? = null
    private var searchActionSuccess: String? = null
    private var searchScrollOffset = 0
    private var searchLoading = false
    private var searchQuery = ""
    private var searchInputText = ""
    private var searchTotal = -1
    private var searchHasMore = false
    private var searchResultSourceId = ""
    private var selectedSearchSourceId: String? = null
    private var searchSourceDropdownOpen = false
    private var searchHiddenCount = 0
    private var pendingSearchRequestId: Long? = null
    private var deferredSearchAbsoluteOffset: Int? = null

    // -------------------------------------------------------------------------
    // Queue state
    // -------------------------------------------------------------------------

    private var rawQueueTracks: List<TrackInfo> = emptyList()
    private var queueTracks: List<TrackInfo> = emptyList()
    private var queueError: String? = null
    private var queueSuccess: String? = null
    private var queueHiddenCount = 0
    private var queueScrollOffset = 0
    private var pendingQueueRequestId: Long? = null
    private var pendingTrackSubmitOrigin: TrackListVariant? = null
    private var pendingTrackSubmitRequestId: Long? = null
    private var pendingIdentifierSubmitRequestId: Long? = null
    private var pendingSelectionSubmitRequestId: Long? = null
    private var pendingQueueRemoveRequestId: Long? = null
    private var pendingPlaybackControlRequestId: Long? = null
    private var playbackError: String? = null
    private var playbackSuccess: String? = null
    private var rowActionMenu: RowActionMenuState? = null
    private var rowActionMenuLayout: RowActionMenuLayout? = null
    private var pendingServerFilterOrigin: TrackListVariant? = null
    private var pendingContentFilterActionRequestId: Long? = null
    private var listControllerDragging: TrackListVariant? = null
    private var listControllerDragThumbOffset = 0
    private val serverExactTrackOverlays = linkedSetOf<ExactFilterKey>()
    private val serverExactArtistOverlays = linkedSetOf<ExactFilterKey>()

    // -------------------------------------------------------------------------
    // Layout constants
    // -------------------------------------------------------------------------

    private val margin = 6
    private val tabBarH = 18
    private val searchRowH = 24
    private val queueRowH = 24
    private val currentTrackRowH = 20  // slim single-line pinned "now playing" header in the queue tab
    private val btnRowW = 18  // width of compact per-row action buttons
    private val btnRowH = 16  // height of per-row action button
    private val btnRowMenuW = 18
    private val btnRowGap = 2
    private val menuRowGap = 2
    private val listControllerW = 13
    private val listControllerBtnH = 13
    private val listControllerGap = 2
    private val listControllerMinThumbH = 18
    private val searchControlH = 16
    private val searchSourceW = 132
    private val searchDropdownRowH = 16
    private val nowPlayingInfoRows = 7

    // Colours
    private val panelBg = 0xCC000000.toInt()
    private val rowBg1 = 0xCC1A1A1A.toInt()
    private val rowBg2 = 0xCC232323.toInt()
    private val textCol = 0xFFFFFFFF.toInt()
    private val dimCol = 0xFFAAAAAA.toInt()
    private val metaAccentCol = 0xFF8EC5FF.toInt()
    private val metaWarmCol = 0xFFF0C674.toInt()
    private val metaSourceCol = 0xFF72D6B6.toInt()
    private val errCol = 0xFFFF5555.toInt()
    private val volumeCol = 0xFF55AAFF.toInt()
    private val accentCol = 0xFF1DB954.toInt()
    private val pausedAccentCol = 0xFFF4D35E.toInt()
    private val successCol = accentCol
    private val barBgCol = 0xFF444444.toInt()
    private val btnBg = 0xFF333355.toInt()
    private val btnHover = 0xFF4444AA.toInt()
    private val menuBgCol = 0xEE111111.toInt()
    private val listControllerThumbCol = 0xFF5A6AC8.toInt()
    private val listControllerThumbHoverCol = 0xFF6E82F0.toInt()
    private val listControllerThumbDragCol = 0xFF7CB6FF.toInt()

    // -------------------------------------------------------------------------
    // Derived layout helpers
    // -------------------------------------------------------------------------

    private val contentY get() = margin + tabBarH + 2
    private val listY get() = contentY + 20
    private val listW get() = width - 2 * margin - listControllerW - 2
    private val listBottom get() = height - margin - 20
    private val listControllerX get() = width - margin - listControllerW
    private val rowActionAreaW get() = btnRowW * 2 + btnRowGap * 2 + btnRowMenuW
    private val rowPrimaryBtnX get() = margin + listW - rowActionAreaW - 3
    private val rowSecondaryBtnX get() = rowPrimaryBtnX + btnRowW + btnRowGap
    private val rowMenuBtnX get() = rowSecondaryBtnX + btnRowW + btnRowGap
    private val searchSourceX get() = margin
    private val searchSourceY get() = contentY
    private val searchSourceDropdownX get() = searchSourceX
    private val searchSourceDropdownY get() = searchSourceY + searchControlH + 1
    private val seekBarX get() = margin + 4
    private val seekBarW get() = width - 2 * margin - 8
    private val addPanelX get() = margin + 4
    private val addPanelW get() = width - 2 * margin - 8
    private val topButtonW get() = ((width - 2 * margin) / (Tab.entries.size + 1)).coerceAtLeast(1)
    private val configButtonX get() = margin + Tab.entries.size * topButtonW

    /** Number of list rows that fit in the current list area. */
    private fun visibleRows(rowHeight: Int) = ((listBottom - listY) / rowHeight).coerceAtLeast(0)

    private fun seekBarY(): Int {
        val btnH = 16
        val seekBarH = 6
        return height - margin - btnH - seekBarH - 8
    }

    private fun volumeBarY(): Int = seekBarY() - 18

    private fun quickAddLayout(): QuickAddLayout {
        val titleY = contentY + 6 + (font.lineHeight + 3) * nowPlayingInfoRows + 12
        val detailY = titleY + font.lineHeight + 2
        val addBoxY = detailY + font.lineHeight + 4
        return QuickAddLayout(titleY, detailY, addBoxY)
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun init() {
        super.init()
        ClientPlaybackHandler.currentAvailabilityIssue()?.let { issue ->
            minecraft?.setScreen(MusicPlayerUnavailableScreen(null, issue))
            return
        }
        ClientPlaybackHandler.guiListener = this

        syncSelectedSearchSource(ClientPlaybackHandler.cachedSearchState?.sourceId)
        restoreCachedSearchState()
        ClientPlaybackHandler.lastQueueResponse?.let { resp ->
            rawQueueTracks = resp.tracks.map { it.toApi() }
            applyClientQueueFilter()
            queueError = resp.failure.ifEmpty { null }
        }
        ClientPlaybackHandler.lastTrackSubmitResponse?.let(::onTrackSubmitResponse)
        ClientPlaybackHandler.lastQueueRemoveResponse?.let(::onQueueRemoveResponse)
        ClientPlaybackHandler.lastPlaybackControlResponse?.let(::onPlaybackControlResponse)
        onInstancePlaybackStandby(ClientPlaybackHandler.lastInstanceLockMessage)

        pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
        rebuildScreenWidgets()
    }

    override fun tick() {
        super.tick()
        ClientPlaybackHandler.currentAvailabilityIssue()?.let { issue ->
            minecraft?.setScreen(MusicPlayerUnavailableScreen(null, issue))
            return
        }
    }

    private fun rebuildScreenWidgets() {
        syncDraftInputsFromWidgets()
        if (currentTab != Tab.SEARCH) {
            searchSourceDropdownOpen = false
        }
        val activeListVariant = currentTab.toListVariantOrNull()
        if (listControllerDragging != null && listControllerDragging != activeListVariant) {
            listControllerDragging = null
            listControllerDragThumbOffset = 0
        }
        if (currentTab == Tab.NOW_PLAYING) {
            rowActionMenu = null
            rowActionMenuLayout = null
        }
        searchEditBox = null
        addIdentifierEditBox = null
        clearWidgets()
        addTabButtons()
        when (currentTab) {
            Tab.NOW_PLAYING -> addNowPlayingWidgets()
            Tab.SEARCH -> addSearchWidgets()
            Tab.QUEUE -> addQueueWidgets()
        }
        addRowActionMenuWidgets()
    }

    private fun restoreCachedSearchState() {
        val cached = ClientPlaybackHandler.cachedSearchState ?: return
        searchInputText = cached.query

        val selectableIds = searchableSources().mapTo(linkedSetOf()) { it.id }
        val canRestoreResults = selectableIds.isEmpty() || cached.sourceId.isBlank() || cached.sourceId in selectableIds
        if (!canRestoreResults) return

        searchQuery = cached.query
        rawSearchResults = cached.entries
        applyClientSearchFilter()
        searchError = cached.failure
        searchTotal = cached.total
        searchHasMore = cached.hasMore
        searchResultSourceId = cached.sourceId.ifBlank { currentSelectedSearchSourceId().orEmpty() }
    }

    private fun syncDraftInputsFromWidgets() {
        searchInputText = searchEditBox?.value ?: searchInputText
        addIdentifierInputText = addIdentifierEditBox?.value ?: addIdentifierInputText
    }

    private fun visibleSearchRows(): Int = visibleRows(searchRowH)

    private fun effectiveSearchTotalCount(): Int = when {
        searchTotal >= 0 -> searchTotal
        rawSearchResults.isNotEmpty() -> rawSearchResults.size
        else -> 0
    }

    private fun maxLoadedSearchScrollOffset(): Int =
        (searchResults.size - visibleSearchRows()).coerceAtLeast(0)

    private fun maxSearchAbsoluteOffset(totalCount: Int = effectiveSearchTotalCount()): Int =
        (totalCount - visibleSearchRows()).coerceAtLeast(0)

    private fun currentSearchAbsoluteOffset(): Int = searchScrollOffset.coerceIn(0, maxSearchAbsoluteOffset())

    private fun requestSearchPage(
        query: String,
        sourceId: String,
        offset: Int,
    ) {
        val normalizedOffset = offset.coerceAtLeast(0)
        searchLoading = true
        searchActionError = null
        searchActionSuccess = null
        pendingSearchRequestId = ClientPlaybackHandler.sendSearchRequest(
            query,
            sourceId = sourceId,
            limit = SEARCH_PAGE_SIZE,
            offset = normalizedOffset,
        )
    }

    private fun submitSearch(query: String) {
        val sourceId = currentSelectedSearchSourceId()
        if (sourceId.isNullOrBlank()) {
            searchLoading = false
            searchError = tr("screen.moemusic.search.no_sources")
            searchActionError = null
            searchActionSuccess = null
            return
        }

        searchInputText = query
        searchScrollOffset = 0
        searchSourceDropdownOpen = false
        searchError = null
        deferredSearchAbsoluteOffset = null
        requestSearchPage(query = query, sourceId = sourceId, offset = 0)
    }

    private fun requestMoreSearchResults() {
        if (!canLoadMoreSearchResults()) return
        val sourceId = currentSelectedSearchSourceId() ?: return
        requestSearchPage(
            query = searchQuery,
            sourceId = sourceId,
            offset = rawSearchResults.size,
        )
    }

    private fun canLoadMoreSearchResults(): Boolean =
        !searchLoading &&
                searchQuery.isNotBlank() &&
                searchHasMore &&
                searchResultSourceId.isNotBlank() &&
                searchResultSourceId == currentSelectedSearchSourceId().orEmpty()

    private fun maybeLoadMoreSearchResults() {
        if (!canLoadMoreSearchResults() || searchResults.isEmpty()) return
        if (searchScrollOffset + visibleSearchRows() >= searchResults.size - SEARCH_PREFETCH_THRESHOLD) {
            requestMoreSearchResults()
        }
    }

    private fun setSearchAbsoluteOffset(targetOffset: Int) {
        val previousOffset = currentSearchAbsoluteOffset()
        val clampedTarget = targetOffset.coerceIn(0, maxSearchAbsoluteOffset())
        if (rawSearchResults.isEmpty()) {
            searchScrollOffset = 0
            deferredSearchAbsoluteOffset = null
            return
        }

        val loadedMaxOffset = maxLoadedSearchScrollOffset()
        if (clampedTarget <= loadedMaxOffset) {
            deferredSearchAbsoluteOffset = null
            searchScrollOffset = clampedTarget
            if (!searchLoading && clampedTarget > previousOffset) {
                maybeLoadMoreSearchResults()
            }
            return
        }

        if (searchLoading) {
            deferredSearchAbsoluteOffset = clampedTarget
            return
        }

        searchScrollOffset = loadedMaxOffset
        if (canLoadMoreSearchResults()) {
            deferredSearchAbsoluteOffset = clampedTarget
            requestMoreSearchResults()
        } else {
            deferredSearchAbsoluteOffset = null
        }
    }

    private fun applyDeferredSearchAbsoluteOffsetIfNeeded(): Boolean {
        val deferredOffset = deferredSearchAbsoluteOffset ?: return false
        val loadedMaxOffset = maxLoadedSearchScrollOffset()
        return if (deferredOffset <= loadedMaxOffset || !searchHasMore) {
            searchScrollOffset = deferredOffset.coerceIn(0, loadedMaxOffset)
            deferredSearchAbsoluteOffset = null
            false
        } else {
            searchScrollOffset = loadedMaxOffset
            requestMoreSearchResults()
            true
        }
    }

    private fun clearDisplayedSearchState() {
        rawSearchResults = emptyList()
        searchResults = emptyList()
        searchError = null
        searchActionError = null
        searchActionSuccess = null
        searchScrollOffset = 0
        searchLoading = false
        searchQuery = ""
        searchTotal = -1
        searchHasMore = false
        searchResultSourceId = ""
        pendingSearchRequestId = null
        deferredSearchAbsoluteOffset = null
        cacheSearchState()
    }

    private fun searchableSources(): List<SearchSourceInfo> =
        ClientPlaybackHandler.sourceCatalog?.sources?.filter { it.searchable }.orEmpty()

    private fun syncSelectedSearchSource(preferredSourceId: String? = null) {
        val sources = searchableSources()
        if (sources.isEmpty()) {
            selectedSearchSourceId = null
            searchSourceDropdownOpen = false
            return
        }

        val validIds = sources.mapTo(linkedSetOf()) { it.id }
        selectedSearchSourceId = sequenceOf(
            preferredSourceId,
            selectedSearchSourceId,
            ClientPlaybackHandler.sourceCatalog?.defaultSourceId?.takeIf { it.isNotBlank() },
            sources.firstOrNull()?.id,
        ).filterNotNull().firstOrNull { it in validIds }
    }

    private fun currentSelectedSearchSourceId(): String? {
        syncSelectedSearchSource()
        return selectedSearchSourceId
    }

    private fun currentSelectedSearchSource(): SearchSourceInfo? {
        val sourceId = currentSelectedSearchSourceId() ?: return null
        return searchableSources().firstOrNull { it.id == sourceId }
    }

    private fun selectSearchSource(sourceId: String) {
        val validSource = searchableSources().firstOrNull { it.id == sourceId } ?: return
        val changed = selectedSearchSourceId != validSource.id
        selectedSearchSourceId = validSource.id
        searchSourceDropdownOpen = false
        if (changed && searchResultSourceId.isNotBlank() && searchResultSourceId != validSource.id) {
            clearDisplayedSearchState()
        }
        rebuildScreenWidgets()
    }

    private fun isTrackAlreadyQueued(track: TrackInfo): Boolean =
        rawQueueTracks.any { it.matchesQueueIdentity(track) }

    private fun isSearchEntryAlreadyQueued(entry: SelectionEntry): Boolean =
        entry.isDirectTrack &&
                !entry.sourceId.isNullOrBlank() &&
                !entry.directTrackId.isNullOrBlank() &&
                rawQueueTracks.any { queued ->
                    queued.sourceId == entry.sourceId && queued.id == entry.directTrackId
                }

    private fun isSearchEntryCurrent(entry: SelectionEntry): Boolean {
        if (!entry.isDirectTrack) return false
        val sourceId = entry.sourceId?.takeIf { it.isNotBlank() } ?: return false
        val trackId = entry.directTrackId?.takeIf { it.isNotBlank() } ?: return false
        val currentTrack = ClientPlaybackHandler.currentContext?.track ?: return false
        return currentTrack.sourceId == sourceId && currentTrack.id == trackId
    }

    private fun isSearchEntryDuplicate(entry: SelectionEntry): Boolean =
        isSearchEntryAlreadyQueued(entry) || isSearchEntryCurrent(entry)

    private fun isSearchEntrySelectable(entry: SelectionEntry): Boolean =
        entry.isSelectable && (!entry.isDirectTrack || !isSearchEntryDuplicate(entry))

    private fun isSearchEntryPlayNowAllowed(entry: SelectionEntry): Boolean = entry.isSelectable

    private fun sourceDisplayName(sourceId: String?): String =
        sourceId?.takeIf { it.isNotBlank() }?.let(ClientPlaybackHandler::sourceDisplayName).orEmpty()

    private fun cacheSearchState() {
        val state = if (searchQuery.isBlank() && searchResults.isEmpty() && searchError.isNullOrBlank()) {
            null
        } else {
            CachedSearchState(
                query = searchQuery,
                sourceId = searchResultSourceId.ifBlank { currentSelectedSearchSourceId().orEmpty() },
                entries = rawSearchResults,
                total = searchTotal,
                hasMore = searchHasMore,
                failure = searchError,
            )
        }
        ClientPlaybackHandler.cacheSearchState(state)
    }

    private fun applySelectionChoices(
        entries: List<SelectionEntry>,
        successMessage: String?,
        switchToSearchTab: Boolean,
    ) {
        searchLoading = false
        searchError = null
        searchActionError = null
        searchActionSuccess = successMessage
        rawSearchResults = entries
        applyClientSearchFilter()
        searchScrollOffset = 0
        searchQuery = ""
        searchTotal = entries.size
        searchHasMore = false
        searchResultSourceId = entries.firstOrNull()?.sourceId.orEmpty()
        pendingSearchRequestId = null
        deferredSearchAbsoluteOffset = null
        cacheSearchState()
        if (switchToSearchTab) {
            currentTab = Tab.SEARCH
        }
        if (currentTab == Tab.SEARCH) {
            rebuildScreenWidgets()
        }
    }

    private fun applyClientSearchFilter() {
        searchResults = applySearchFilter(rawSearchResults)
        searchScrollOffset = searchScrollOffset.coerceIn(0, maxLoadedSearchScrollOffset())
    }

    private fun applyClientQueueFilter() {
        val filteredTracks = mutableListOf<TrackInfo>()
        var hiddenCount = 0
        if (!ContentFilterRuntime.clientFilterEnabled()) {
            rawQueueTracks.forEach { track ->
                filteredTracks += applyServerFilterOverlay(track)
            }
        } else {
            when (ContentFilterRuntime.queueListMode()) {
                ContentFilterClientListMode.MARK -> rawQueueTracks.forEach { track ->
                    filteredTracks += markDisplayFilteredTrack(track)
                }

                ContentFilterClientListMode.HIDE -> rawQueueTracks.forEach { track ->
                    if (ContentFilterRuntime.trackBlockReason(track) == null) {
                        filteredTracks += applyServerFilterOverlay(track)
                    } else {
                        hiddenCount += 1
                    }
                }
            }
        }
        queueTracks = filteredTracks
        queueHiddenCount = hiddenCount
        queueScrollOffset = queueScrollOffset.coerceIn(0, (queueTracks.size - visibleQueueRows()).coerceAtLeast(0))
    }

    private fun applySearchFilter(entries: List<SelectionEntry>): List<SelectionEntry> {
        if (!ContentFilterRuntime.clientFilterEnabled()) {
            searchHiddenCount = 0
            return entries.map(::applyServerFilterOverlay)
        }
        return when (ContentFilterRuntime.searchListMode()) {
            ContentFilterClientListMode.MARK -> {
                searchHiddenCount = 0
                entries.map(::markDisplayFilteredEntry)
            }
            ContentFilterClientListMode.HIDE -> {
                val filtered = entries.filter { ContentFilterRuntime.selectionBlockReason(it) == null }
                searchHiddenCount = entries.size - filtered.size
                filtered.map(::applyServerFilterOverlay)
            }
        }
    }

    private fun markDisplayFilteredEntry(entry: SelectionEntry): SelectionEntry =
        markLocallyFilteredEntry(applyServerFilterOverlay(entry))

    private fun markDisplayFilteredTrack(track: TrackInfo): TrackInfo =
        markLocallyFilteredTrack(applyServerFilterOverlay(track))

    private fun markLocallyFilteredEntry(entry: SelectionEntry): SelectionEntry =
        ContentFilterRuntime.selectionBlockReason(entry)
            ?.takeIf { entry.unavailableReason == null }
            ?.let { reason -> entry.copy(unavailableReason = reason) }
            ?: entry

    private fun markLocallyFilteredTrack(track: TrackInfo): TrackInfo =
        ContentFilterRuntime.trackBlockReason(track)
            ?.takeIf { track.unavailableReason == null }
            ?.let { reason -> track.copy(unavailableReason = reason) }
            ?: track

    private fun applyServerFilterOverlay(entry: SelectionEntry): SelectionEntry =
        serverFilterOverlayReason(entry)
            ?.takeIf { entry.unavailableReason == null }
            ?.let { reason -> entry.copy(unavailableReason = reason) }
            ?: entry

    private fun applyServerFilterOverlay(track: TrackInfo): TrackInfo =
        serverFilterOverlayReason(track)
            ?.takeIf { track.unavailableReason == null }
            ?.let { reason -> track.copy(unavailableReason = reason) }
            ?: track

    private fun addTabButtons() {
        Tab.entries.forEachIndexed { i, tab ->
            val labelKey = when (tab) {
                Tab.NOW_PLAYING -> "screen.moemusic.tab.now_playing"
                Tab.SEARCH -> "screen.moemusic.tab.search"
                Tab.QUEUE -> "screen.moemusic.tab.queue"
            }
            addRenderableWidget(Button.builder(McText.translatable(labelKey)) {
                searchSourceDropdownOpen = false
                rowActionMenu = null
                rowActionMenuLayout = null
                currentTab = tab
                rebuildScreenWidgets()
            }.pos(margin + i * topButtonW, margin).size(topButtonW - 1, tabBarH - 2).build())
        }

        addRenderableWidget(
            Button.builder(McText.translatable("screen.moemusic.config.button")) {
                minecraft?.setScreen(ConfigScreenAccess.buildOrFallback(this))
            }.pos(configButtonX, margin).size(topButtonW - 1, tabBarH - 2).build()
        )
    }

    private fun addNowPlayingWidgets() {
        val quickAddLayout = quickAddLayout()
        val btnW = 60
        val btnH = 16
        val cx = width / 2
        val btnY = height - margin - btnH - 2
        val controlGap = 4
        val skipButtonX = cx - btnW / 2
        val playPauseButtonX = skipButtonX - btnW - controlGap
        val stopButtonX = skipButtonX + btnW + controlGap

        val ctx = ClientPlaybackHandler.currentContext
        val isPaused = ctx?.state is PlaybackState.Paused

        val modeButtonW = 82
        val addButtonW = 42
        val addButtonGap = 4
        val addBoxX = addPanelX
        val addBoxW = (addPanelW - modeButtonW - addButtonW - addButtonGap * 2).coerceAtLeast(60)
        val modeButtonX = addBoxX + addBoxW + addButtonGap
        val addButtonX = modeButtonX + modeButtonW + addButtonGap

        val box =
            EditBox(
                font,
                addBoxX,
                quickAddLayout.addBoxY,
                addBoxW,
                16,
                McText.translatable("screen.moemusic.quick_add.identifier")
            )
        box.setMaxLength(512)
        box.setHint(McText.translatable("screen.moemusic.quick_add.hint"))
        box.value = addIdentifierInputText
        addIdentifierEditBox = box
        addRenderableWidget(box)

        addRenderableWidget(Button.builder(McText.literal(addModeLabel(addIdentifierMode))) {
            addIdentifierMode = nextAddMode(addIdentifierMode)
            rebuildScreenWidgets()
        }.pos(modeButtonX, quickAddLayout.addBoxY).size(modeButtonW, 16).build())

        addRenderableWidget(Button.builder(McText.translatable("screen.moemusic.quick_add.add")) {
            val identifier = addIdentifierEditBox?.value?.trim().orEmpty()
            if (identifier.isNotEmpty()) {
                addIdentifierInputText = identifier
                addIdentifierError = null
                addIdentifierSuccess = null
                pendingIdentifierSubmitRequestId = ClientPlaybackHandler.sendIdentifierSubmit(identifier, addIdentifierMode)
            }
        }.pos(addButtonX, quickAddLayout.addBoxY).size(addButtonW, 16).build())

        val playLabelKey =
            if (isPaused || ctx == null) "screen.moemusic.control.resume" else "screen.moemusic.control.pause"
        addRenderableWidget(Button.builder(McText.translatable(playLabelKey)) {
            playbackError = null
            playbackSuccess = null
            pendingPlaybackControlRequestId =
                if (ClientPlaybackHandler.currentContext?.state is PlaybackState.Paused || ClientPlaybackHandler.currentContext == null) {
                    ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.RESUME)
                } else {
                    ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.PAUSE)
                }
        }.pos(playPauseButtonX, btnY).size(btnW, btnH).build())

        addRenderableWidget(Button.builder(McText.translatable("screen.moemusic.control.skip")) {
            playbackError = null
            playbackSuccess = null
            pendingPlaybackControlRequestId = ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.SKIP)
        }.pos(skipButtonX, btnY).size(btnW, btnH).build())

        addRenderableWidget(Button.builder(McText.translatable("screen.moemusic.control.stop")) {
            playbackError = null
            playbackSuccess = null
            pendingPlaybackControlRequestId = ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.STOP)
        }.pos(stopButtonX, btnY).size(btnW, btnH).build())
    }

    private fun addSearchWidgets() {
        syncSelectedSearchSource()
        val controller = listControllerLayout(TrackListVariant.SEARCH)

        val searchBtnW = 54
        val controlGap = 2
        val editX = searchSourceX + searchSourceW + controlGap
        val editW = (width - margin - searchBtnW - controlGap - editX).coerceAtLeast(40)
        val hasSearchableSources = searchableSources().isNotEmpty()
        if (!hasSearchableSources) {
            searchSourceDropdownOpen = false
        }

        val sourceLabel = currentSelectedSearchSource()?.displayName ?: tr("screen.moemusic.search.source.none")
        val sourceButton = Button.builder(
            McText.literal(
                truncate(
                    tr("screen.moemusic.search.source.button", sourceLabel),
                    searchSourceW - 8,
                    font
                )
            )
        ) {
            if (hasSearchableSources) {
                searchSourceDropdownOpen = !searchSourceDropdownOpen
                rebuildScreenWidgets()
            }
        }.pos(searchSourceX, contentY).size(searchSourceW, searchControlH).build()
        sourceButton.active = hasSearchableSources
        addRenderableWidget(sourceButton)

        val box = EditBox(
            font,
            editX,
            contentY,
            editW,
            searchControlH,
            McText.translatable("screen.moemusic.search.field")
        )
        box.setMaxLength(128)
        box.setHint(McText.translatable("screen.moemusic.search.field_hint"))
        box.value = searchInputText
        searchEditBox = box
        addRenderableWidget(box)

        val searchButton = Button.builder(McText.translatable("screen.moemusic.search.button")) {
            searchSourceDropdownOpen = false
            val q = box.value.trim()
            if (q.isNotEmpty()) {
                submitSearch(q)
            }
        }.pos(editX + editW + controlGap, contentY).size(searchBtnW, searchControlH).build()
        searchButton.active = hasSearchableSources
        addRenderableWidget(searchButton)

        val searchUpButton = Button.builder(McText.literal("▲")) {
            setSearchAbsoluteOffset(currentSearchAbsoluteOffset() - 1)
        }.pos(controller.x, controller.upButtonY).size(controller.width, listControllerBtnH).build()
        searchUpButton.active = controller.maxOffset > 0
        addRenderableWidget(searchUpButton)

        val searchDownButton = Button.builder(McText.literal("▼")) {
            setSearchAbsoluteOffset(currentSearchAbsoluteOffset() + 1)
        }.pos(controller.x, controller.downButtonY).size(controller.width, listControllerBtnH).build()
        searchDownButton.active = controller.maxOffset > 0 || canLoadMoreSearchResults()
        addRenderableWidget(searchDownButton)
    }

    private fun addQueueWidgets() {
        val controller = listControllerLayout(TrackListVariant.QUEUE)
        addRenderableWidget(Button.builder(McText.translatable("screen.moemusic.queue.refresh")) {
            queueError = null
            queueSuccess = null
            pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
        }.pos(margin, contentY).size(60, 16).build())

        val queueUpButton = Button.builder(McText.literal("▲")) {
            queueScrollOffset = (queueScrollOffset - 1).coerceAtLeast(0)
        }.pos(controller.x, controller.upButtonY).size(controller.width, listControllerBtnH).build()
        queueUpButton.active = controller.maxOffset > 0
        addRenderableWidget(queueUpButton)

        val queueDownButton = Button.builder(McText.literal("▼")) {
            val maxQueueScroll = (queueTracks.size - visibleQueueRows()).coerceAtLeast(0)
            queueScrollOffset = (queueScrollOffset + 1).coerceAtMost(maxQueueScroll)
        }.pos(controller.x, controller.downButtonY).size(controller.width, listControllerBtnH).build()
        queueDownButton.active = controller.maxOffset > 0
        addRenderableWidget(queueDownButton)
    }

    private fun addRowActionMenuWidgets() {
        val menu = rowActionMenu ?: return
        if (menu.options.isEmpty()) {
            rowActionMenu = null
            rowActionMenuLayout = null
            return
        }

        val layout = computeRowActionMenuLayout(menu)
        rowActionMenuLayout = layout
        menu.options.forEachIndexed { index, option ->
            addRenderableWidget(
                Button.builder(McText.literal(option.label)) {
                    rowActionMenu = null
                    rowActionMenuLayout = null
                    option.action()
                }.pos(
                    layout.x + 3,
                    layout.y + 3 + index * (btnRowH + menuRowGap),
                ).size(layout.width - 6, btnRowH).build()
            )
        }
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.renderBackground(context)
        context.fill(margin, contentY - 1, width - margin, height - margin, panelBg)

        Tab.entries.forEachIndexed { i, tab ->
            if (tab == currentTab) {
                context.fill(
                    margin + i * topButtonW,
                    margin,
                    margin + (i + 1) * topButtonW - 1,
                    margin + tabBarH - 2,
                    0xCC004477.toInt(),
                )
            }
        }

        when (currentTab) {
            Tab.NOW_PLAYING -> renderNowPlaying(context, mouseX, mouseY)
            Tab.SEARCH -> renderSearch(context, mouseX, mouseY)
            Tab.QUEUE -> renderQueue(context, mouseX, mouseY)
        }

        renderRowActionMenuBackground(context)

        super.render(context, mouseX, mouseY, delta)
    }

    override fun renderBackground(context: GuiGraphics) = Unit

    // -------------------------------------------------------------------------
    // Now Playing rendering
    // -------------------------------------------------------------------------

    private fun renderNowPlaying(context: GuiGraphics, mouseX: Int, mouseY: Int) {
        val ctx = ClientPlaybackHandler.currentContext
        val quickAddLayout = quickAddLayout()
        val textX = margin + 6
        val lineH = font.lineHeight + 3
        val volumeBarH = 6
        val volumeY = volumeBarY()
        val volumePercent = ClientVolumeRuntime.configuredVolumePercent
        val volume = ClientVolume.percentToGain(volumePercent)
        val volumeLabel = tr("screen.moemusic.now_playing.volume", volumePercent)

        context.drawString(font, McText.literal(volumeLabel), textX, volumeY - font.lineHeight - 2, dimCol, true)
        context.fill(seekBarX, volumeY, seekBarX + seekBarW, volumeY + volumeBarH, barBgCol)
        val volumeFilled = (seekBarW * volume).toInt().coerceAtLeast(0)
        if (volumeFilled > 0) {
            context.fill(seekBarX, volumeY, seekBarX + volumeFilled, volumeY + volumeBarH, volumeCol)
        }
        val volumeKnobX = (seekBarX + volumeFilled).coerceIn(seekBarX, seekBarX + seekBarW)
        context.fill(volumeKnobX - 1, volumeY - 1, volumeKnobX + 2, volumeY + volumeBarH + 1, 0xFFFFFFFF.toInt())
        if (mouseX in seekBarX..(seekBarX + seekBarW) && mouseY in (volumeY - 2)..(volumeY + volumeBarH + 2)) {
            context.fill(seekBarX, volumeY, seekBarX + seekBarW, volumeY + volumeBarH, 0x22FFFFFF)
        }

        renderQuickAddHeader(context, quickAddLayout)

        if (ctx == null) {
            context.drawString(
                font,
                McText.translatable("screen.moemusic.now_playing.empty"),
                textX,
                contentY + 10,
                dimCol,
                true
            )
            return
        }

        val track = ctx.track
        val textWidth = width - 2 * margin - 14
        val metadataLines = buildList {
            add(tr("screen.moemusic.track.meta.artist", track.artistDisplay.ifBlank { "-" }))
            add(tr("screen.moemusic.track.meta.album", track.album ?: "-"))
            track.submittedByUserName?.takeIf { it.isNotBlank() }?.let {
                add(tr("screen.moemusic.track.meta.submitted_by", it))
            }
            add(tr("screen.moemusic.track.meta.source", sourceDisplayName(track.sourceId).ifBlank { "-" }))
        }
        context.drawString(
            font,
            McText.literal(truncate(track.title, textWidth, font)),
            textX,
            contentY + 6,
            textCol,
            true
        )
        metadataLines.forEachIndexed { index, line ->
            context.drawString(
                font,
                McText.literal(truncate(line, textWidth, font)),
                textX,
                contentY + 6 + lineH * (index + 1),
                dimCol,
                true,
            )
        }

        val stateLabel = when (ctx.state) {
            is PlaybackState.Playing -> tr("screen.moemusic.now_playing.state.playing")
            is PlaybackState.Paused -> tr("screen.moemusic.now_playing.state.paused")
            PlaybackState.Stopped -> tr("screen.moemusic.now_playing.state.stopped")
        }
        val stateColor = if (ctx.state is PlaybackState.Paused) pausedAccentCol else accentCol
        context.drawString(
            font,
            McText.literal(stateLabel),
            width - margin - font.width(stateLabel) - 6,
            contentY + 6,
            stateColor,
            true
        )

        val durationMs = track.durationMs
        val posMs = ClientPlaybackHandler.currentPositionMs(ctx)

        val timeY = contentY + 6 + lineH * (metadataLines.size + 1) + 4
        val timeStr = "${formatDuration(posMs)} / ${if (durationMs > 0) formatDuration(durationMs) else "∞"}"
        context.drawString(font, McText.literal(timeStr), textX, timeY, dimCol, true)
        when {
            playbackError != null ->
                context.drawString(font, McText.literal("✗ $playbackError"), textX, timeY + lineH + 6, errCol, true)

            playbackSuccess != null ->
                context.drawString(font, McText.literal("✓ $playbackSuccess"), textX, timeY + lineH + 6, successCol, true)
        }

        val seekBarH = 6
        val seekY = seekBarY()

        context.fill(seekBarX, seekY, seekBarX + seekBarW, seekY + seekBarH, barBgCol)

        val progress = if (seekBarDragging) seekBarDragProgress
        else if (durationMs > 0) (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else 0f

        val filled = (seekBarW * progress).toInt().coerceAtLeast(0)
        val progressColor = if (ctx.state is PlaybackState.Paused) pausedAccentCol else accentCol
        if (filled > 0) {
            context.fill(seekBarX, seekY, seekBarX + filled, seekY + seekBarH, progressColor)
        }

        val knobX = (seekBarX + filled).coerceIn(seekBarX, seekBarX + seekBarW)
        context.fill(knobX - 1, seekY - 1, knobX + 2, seekY + seekBarH + 1, 0xFFFFFFFF.toInt())

        if (mouseX in seekBarX..(seekBarX + seekBarW) && mouseY in (seekY - 2)..(seekY + seekBarH + 2)) {
            context.fill(seekBarX, seekY, seekBarX + seekBarW, seekY + seekBarH, 0x22FFFFFF)
        }
    }

    private fun renderQuickAddHeader(context: GuiGraphics, quickAddLayout: QuickAddLayout) {
        context.drawString(
            font,
            McText.translatable("screen.moemusic.quick_add.title"),
            addPanelX,
            quickAddLayout.titleY,
            textCol,
            true
        )
        when {
            addIdentifierError != null ->
                context.drawString(
                    font,
                    McText.literal(truncate("✗ $addIdentifierError", addPanelW, font)),
                    addPanelX,
                    quickAddLayout.detailY,
                    errCol,
                    false
                )

            addIdentifierSuccess != null ->
                context.drawString(
                    font,
                    McText.literal(truncate("✓ $addIdentifierSuccess", addPanelW, font)),
                    addPanelX,
                    quickAddLayout.detailY,
                    successCol,
                    false
                )

            else ->
                context.drawString(
                    font,
                    McText.translatable("screen.moemusic.quick_add.subtitle"),
                    addPanelX,
                    quickAddLayout.detailY,
                    dimCol,
                    false
                )
        }
    }

    // -------------------------------------------------------------------------
    // Search rendering
    // -------------------------------------------------------------------------

    private fun renderSearch(context: GuiGraphics, mouseX: Int, mouseY: Int) {
        val sources = searchableSources()
        if (sources.isEmpty()) {
            context.drawString(
                font,
                McText.literal(tr("screen.moemusic.search.no_sources")),
                margin + 6,
                listY + 4,
                dimCol,
                true
            )
        } else if (searchResults.isEmpty()) {
            val emptyMessage = when {
                searchLoading -> tr("screen.moemusic.search.loading")
                searchHiddenCount > 0 -> tr("screen.moemusic.search.filtered_empty", searchHiddenCount)
                else -> tr("screen.moemusic.search.empty_hint")
            }
            context.drawString(font, McText.literal(emptyMessage), margin + 6, listY + 4, dimCol, true)
        } else {
            renderSelectionList(
                context = context,
                mouseX = mouseX,
                mouseY = mouseY,
                tracks = searchResults,
                scrollOffset = searchScrollOffset,
                totalCount = effectiveSearchTotalCount(),
                variant = TrackListVariant.SEARCH,
                primaryActionEnabled = { isSearchEntrySelectable(it) },
                secondaryActionEnabled = { isSearchEntrySelectable(it) },
                primaryActionLabel = { "+" },
                secondaryActionLabel = { "\u00BB" },
            )
        }

        renderListController(context, mouseX, mouseY, TrackListVariant.SEARCH)

        if (searchSourceDropdownOpen) {
            renderSearchSourceDropdown(context, mouseX, mouseY)
        }

        when {
            searchActionError != null ->
                renderFooterNotice(context, searchActionError, errCol, prefix = "✗ ")

            searchActionSuccess != null ->
                renderFooterNotice(context, searchActionSuccess, successCol, prefix = "✓ ")

            searchError != null ->
                renderFooterNotice(context, searchError, errCol, prefix = "✗ ")

            searchLoading && searchResults.isNotEmpty() -> {
                val message = tr("screen.moemusic.search.loading_more")
                renderFooterNotice(context, message, dimCol)
            }

            searchHiddenCount > 0 ->
                renderFooterNotice(context, tr("screen.moemusic.search.filtered_partial", searchHiddenCount), dimCol)

            !searchHasMore && searchQuery.isNotBlank() && searchResults.isNotEmpty() ->
                renderFooterNotice(context, tr("screen.moemusic.search.all_loaded"), dimCol)
        }
    }

    private fun renderSearchSourceDropdown(context: GuiGraphics, mouseX: Int, mouseY: Int) {
        val sources = searchableSources()
        if (sources.isEmpty()) return

        val x = searchSourceDropdownX
        val y = searchSourceDropdownY
        val w = searchSourceW
        val h = sources.size * searchDropdownRowH

        context.fill(x, y, x + w, y + h, rowBg1)
        context.fill(x, y, x + w, y + 1, 0xFF666688.toInt())
        context.fill(x, y + h - 1, x + w, y + h, 0xFF666688.toInt())
        context.fill(x, y, x + 1, y + h, 0xFF666688.toInt())
        context.fill(x + w - 1, y, x + w, y + h, 0xFF666688.toInt())

        val selectedSourceId = currentSelectedSearchSourceId()
        sources.forEachIndexed { index, source ->
            val rowTop = y + index * searchDropdownRowH
            val hovered = mouseX in x..(x + w) && mouseY in rowTop..<rowTop + searchDropdownRowH
            val selected = source.id == selectedSourceId
            when {
                hovered -> context.fill(x + 1, rowTop, x + w - 1, rowTop + searchDropdownRowH, 0x22FFFFFF)
                selected -> context.fill(x + 1, rowTop, x + w - 1, rowTop + searchDropdownRowH, 0x22004777)
            }

            val label = if (selected) "[${source.displayName}]" else source.displayName
            val color = if (selected) textCol else dimCol
            context.drawString(
                font,
                McText.literal(truncate(label, w - 8, font)),
                x + 4,
                rowTop + (searchDropdownRowH - font.lineHeight) / 2,
                color,
                false,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Queue rendering
    // -------------------------------------------------------------------------

    /** Y-coordinate where the scrollable queue list starts (shifted down if current track is pinned). */
    private fun queueListStartY(): Int {
        val hasCurrent = ClientPlaybackHandler.currentContext != null
        return if (hasCurrent) listY + currentTrackRowH else listY
    }

    /** Number of visible queue rows, accounting for the pinned current-track row. */
    private fun visibleQueueRows(): Int =
        ((listBottom - queueListStartY()) / queueRowH).coerceAtLeast(0)

    private fun renderQueue(context: GuiGraphics, mouseX: Int, mouseY: Int) {
        val currentTrack = ClientPlaybackHandler.currentContext?.track

        // Pinned "NOW PLAYING" row (always at listY, no delete button).
        if (currentTrack != null) {
            renderQueueCurrentRow(context, mouseX, mouseY, currentTrack)
        }

        val startY = queueListStartY()
        val queueEmpty = queueTracks.isEmpty()

        if (queueEmpty && currentTrack == null) {
            context.drawString(
                font,
                McText.literal(
                    if (queueHiddenCount > 0) {
                        tr("screen.moemusic.queue.filtered_empty", queueHiddenCount)
                    } else {
                        tr("screen.moemusic.queue.empty")
                    }
                ),
                margin + 6,
                startY + 4,
                dimCol,
                true
            )
        } else if (!queueEmpty) {
            renderTrackList(
                context = context,
                mouseX = mouseX,
                mouseY = mouseY,
                tracks = queueTracks,
                scrollOffset = queueScrollOffset,
                variant = TrackListVariant.QUEUE,
                listStartY = startY,
                primaryActionEnabled = { it.isAvailable },
                secondaryActionEnabled = { true },
                primaryActionLabel = { "\u25B6" },
                secondaryActionLabel = { "\u2715" },
            )
        }

        renderListController(context, mouseX, mouseY, TrackListVariant.QUEUE)

        if (queueError != null) {
            renderFooterNotice(context, queueError, errCol, prefix = "✗ ")
        } else if (queueSuccess != null) {
            renderFooterNotice(context, queueSuccess, successCol, prefix = "✓ ")
        } else if (queueHiddenCount > 0) {
            renderFooterNotice(context, tr("screen.moemusic.queue.filtered_partial", queueHiddenCount), dimCol)
        }
    }

    /**
     * Renders the slim pinned "NOW PLAYING" header row at [listY].
     * Single line: "▶ Title — Artist" on the left, label chip on the right, `...` menu button far right.
     * No delete button.
     */
    private fun renderQueueCurrentRow(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        track: TrackInfo,
    ) {
        val rowY = listY
        val rowH = currentTrackRowH
        // Subtle gold-tinted background.
        context.fill(margin, rowY, margin + listW, rowY + rowH - 1, 0xCC1E1A08.toInt())
        if (mouseX in margin..(margin + listW) && mouseY in rowY..<rowY + rowH) {
            context.fill(margin, rowY, margin + listW, rowY + rowH - 1, 0x18FFFFFF)
        }
        // Left accent stripe.
        context.fill(margin, rowY, margin + 2, rowY + rowH - 1, pausedAccentCol)

        val nowPlayingLabel = tr("screen.moemusic.queue.now_playing")
        val artist = track.artistDisplay.ifBlank { "-" }
        val titleArtist = "\u25B6 ${track.title} \u2014 $artist"
        val durationText = formatListDuration(track.durationMs)

        // Right side: label chip + menu button.
        val menuX = rowMenuBtnX
        val by = rowY + (rowH - btnRowH) / 2
        val menuEnabled = rowMenuOptionsFor(track).isNotEmpty()
        val menuHovered = mouseX in menuX..(menuX + btnRowMenuW) && mouseY in by..(by + btnRowH)
        val menuBg = if (!menuEnabled) 0xFF3A3A3A.toInt() else if (menuHovered) btnHover else btnBg
        val menuText = if (menuEnabled) textCol else 0xFF999999.toInt()
        context.fill(menuX, by, menuX + btnRowMenuW, by + btnRowH, menuBg)
        context.fill(menuX, by, menuX + btnRowMenuW, by + 1, 0xFF666688.toInt())
        context.fill(menuX, by + btnRowH - 1, menuX + btnRowMenuW, by + btnRowH, 0xFF666688.toInt())
        val menuLabel = "..."
        context.drawString(
            font, McText.literal(menuLabel),
            menuX + (btnRowMenuW - font.width(menuLabel)) / 2,
            by + (btnRowH - font.lineHeight) / 2,
            menuText, false,
        )

        val labelW = font.width(nowPlayingLabel)
        val durationW = font.width(durationText)
        val durationX = menuX - durationW - 6
        context.drawString(
            font, McText.literal(durationText),
            durationX, rowY + (rowH - font.lineHeight) / 2,
            dimCol, false,
        )
        val labelX = durationX - labelW - 8
        context.drawString(
            font, McText.literal(nowPlayingLabel),
            labelX, rowY + (rowH - font.lineHeight) / 2,
            pausedAccentCol, false,
        )

        // Title — artist text, truncated to fit before the label.
        val textMaxW = (labelX - margin - 8).coerceAtLeast(0)
        context.drawString(
            font, McText.literal(truncate(titleArtist, textMaxW, font)),
            margin + 6, rowY + (rowH - font.lineHeight) / 2,
            pausedAccentCol, false,
        )
    }

    private fun renderListController(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        variant: TrackListVariant,
    ) {
        val layout = listControllerLayout(variant)
        context.fill(
            layout.x,
            layout.trackY,
            layout.x + layout.width,
            layout.trackY + layout.trackHeight,
            rowBg1,
        )
        context.fill(
            layout.x,
            layout.trackY,
            layout.x + layout.width,
            layout.trackY + 1,
            0xFF666688.toInt(),
        )
        context.fill(
            layout.x,
            layout.trackY + layout.trackHeight - 1,
            layout.x + layout.width,
            layout.trackY + layout.trackHeight,
            0xFF666688.toInt(),
        )
        val thumbColor = when {
            layout.maxOffset <= 0 -> 0xFF5A5A5A.toInt()
            listControllerDragging == variant -> listControllerThumbDragCol
            layout.isWithinThumb(mouseX, mouseY) -> listControllerThumbHoverCol
            else -> listControllerThumbCol
        }
        context.fill(
            layout.x + 1,
            layout.thumbY,
            layout.x + layout.width - 1,
            layout.thumbY + layout.thumbHeight,
            thumbColor,
        )
        context.fill(
            layout.x + 1,
            layout.thumbY,
            layout.x + layout.width - 1,
            layout.thumbY + 1,
            0xAAFFFFFF.toInt(),
        )
        context.fill(
            layout.x + 1,
            layout.thumbY + layout.thumbHeight - 1,
            layout.x + layout.width - 1,
            layout.thumbY + layout.thumbHeight,
            0x88000000.toInt(),
        )
    }

    // -------------------------------------------------------------------------
    // List renderers
    // -------------------------------------------------------------------------

    private fun renderSelectionList(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        tracks: List<SelectionEntry>,
        scrollOffset: Int,
        totalCount: Int = tracks.size,
        absoluteBaseIndex: Int = 0,
        variant: TrackListVariant,
        primaryActionEnabled: (SelectionEntry) -> Boolean = { true },
        secondaryActionEnabled: (SelectionEntry) -> Boolean = { true },
        primaryActionLabel: (SelectionEntry) -> String,
        secondaryActionLabel: (SelectionEntry) -> String,
    ) {
        val rowHeight = when (variant) {
            TrackListVariant.SEARCH -> searchRowH
            TrackListVariant.QUEUE -> queueRowH
        }
        val maxVisible = visibleRows(rowHeight)
        val textW = listW - rowActionAreaW - 10

        for (relIdx in 0 until maxVisible) {
            val absIdx = relIdx + scrollOffset
            if (absIdx >= tracks.size) break
            val track = tracks[absIdx]

            val rowY = listY + relIdx * rowHeight
            val bg = if (relIdx % 2 == 0) rowBg1 else rowBg2
            context.fill(margin, rowY, margin + listW, rowY + rowHeight - 1, bg)

            if (mouseX in margin..(margin + listW) && mouseY in rowY..<rowY + rowHeight) {
                context.fill(margin, rowY, margin + listW, rowY + rowHeight - 1, 0x22FFFFFF)
            }
            if (!track.isSelectable) {
                context.fill(margin, rowY, margin + listW, rowY + rowHeight - 1, 0x55000000)
            }

        val titleColor = if (track.isSelectable) textCol else dimCol
        val metaColor = if (track.isSelectable) dimCol else 0xFF888888.toInt()
            renderSelectionRowText(
                context = context,
                track = track,
                index = absoluteBaseIndex + absIdx,
                rowY = rowY,
                textWidth = textW,
                titleColor = titleColor,
                metaColor = metaColor,
            )

            val bx = rowPrimaryBtnX
            val bx2 = rowSecondaryBtnX
            val by = rowY + (rowHeight - btnRowH) / 2
            val label = primaryActionLabel(track)
            val label2 = secondaryActionLabel(track)
            val enabled = primaryActionEnabled(track)
            val enabled2 = secondaryActionEnabled(track)
            val menuEnabled = rowMenuOptionsFor(track).isNotEmpty()
            renderRowButton(context, bx, by, btnRowW, label, enabled, mouseX, mouseY)
            renderRowButton(context, bx2, by, btnRowW, label2, enabled2, mouseX, mouseY)
            renderMenuButton(context, rowMenuBtnX, by, menuEnabled, mouseX, mouseY)
        }

        val rangeStart = if (tracks.isEmpty()) 0 else absoluteBaseIndex + scrollOffset + 1
        val rangeEnd = if (tracks.isEmpty()) 0
        else (absoluteBaseIndex + scrollOffset + maxVisible).coerceAtMost(absoluteBaseIndex + tracks.size).coerceAtMost(totalCount)
        val totalStr = "$rangeStart-$rangeEnd / $totalCount"
        context.drawString(
            font,
            McText.literal(totalStr),
            listControllerX - font.width(totalStr) - 4,
            listBottom + 3,
            dimCol,
            false
        )
    }

    private fun renderTrackList(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        tracks: List<TrackInfo>,
        scrollOffset: Int,
        totalCount: Int = tracks.size,
        absoluteBaseIndex: Int = 0,
        variant: TrackListVariant,
        listStartY: Int = listY,
        primaryActionEnabled: (TrackInfo) -> Boolean = { true },
        secondaryActionEnabled: (TrackInfo) -> Boolean = { true },
        primaryActionLabel: (TrackInfo) -> String,
        secondaryActionLabel: (TrackInfo) -> String,
    ) {
        val rowHeight = when (variant) {
            TrackListVariant.SEARCH -> searchRowH
            TrackListVariant.QUEUE -> queueRowH
        }
        val maxVisible = ((listBottom - listStartY) / rowHeight).coerceAtLeast(0)
        val textW = listW - rowActionAreaW - 10

        for (relIdx in 0 until maxVisible) {
            val absIdx = relIdx + scrollOffset
            if (absIdx >= tracks.size) break
            val track = tracks[absIdx]

            val rowY = listStartY + relIdx * rowHeight
            val bg = if (relIdx % 2 == 0) rowBg1 else rowBg2
            context.fill(margin, rowY, margin + listW, rowY + rowHeight - 1, bg)

            if (mouseX in margin..(margin + listW) && mouseY in rowY..<rowY + rowHeight) {
                context.fill(margin, rowY, margin + listW, rowY + rowHeight - 1, 0x22FFFFFF)
            }
            if (!track.isAvailable) {
                context.fill(margin, rowY, margin + listW, rowY + rowHeight - 1, 0x55000000)
            }

            val titleColor = if (track.isAvailable) textCol else dimCol
            val metaColor = if (track.isAvailable) dimCol else 0xFF888888.toInt()
            renderTrackRowText(
                context = context,
                track = track,
                index = absoluteBaseIndex + absIdx,
                variant = variant,
                rowY = rowY,
                textWidth = textW,
                titleColor = titleColor,
                metaColor = metaColor,
            )

            val bx = rowPrimaryBtnX
            val bx2 = rowSecondaryBtnX
            val by = rowY + (rowHeight - btnRowH) / 2
            val label = primaryActionLabel(track)
            val label2 = secondaryActionLabel(track)
            val enabled = primaryActionEnabled(track)
            val enabled2 = secondaryActionEnabled(track)
            val menuEnabled = rowMenuOptionsFor(track).isNotEmpty()
            renderRowButton(context, bx, by, btnRowW, label, enabled, mouseX, mouseY)
            renderRowButton(context, bx2, by, btnRowW, label2, enabled2, mouseX, mouseY)
            renderMenuButton(context, rowMenuBtnX, by, menuEnabled, mouseX, mouseY)
        }

        val rangeStart = if (tracks.isEmpty()) 0 else absoluteBaseIndex + scrollOffset + 1
        val rangeEnd = if (tracks.isEmpty()) 0
        else (absoluteBaseIndex + scrollOffset + maxVisible).coerceAtMost(absoluteBaseIndex + tracks.size).coerceAtMost(totalCount)
        val totalStr = "$rangeStart-$rangeEnd / $totalCount"
        context.drawString(
            font,
            McText.literal(totalStr),
            listControllerX - font.width(totalStr) - 4,
            listBottom + 3,
            dimCol,
            false
        )
    }

    private fun renderSelectionRowText(
        context: GuiGraphics,
        track: SelectionEntry,
        index: Int,
        rowY: Int,
        textWidth: Int,
        titleColor: Int,
        metaColor: Int,
    ) {
        val lineTop = rowY + (searchRowH - (font.lineHeight * 2 + 1)) / 2
        renderPrimaryRowLine(
            context = context,
            rowNumber = index + 1,
            title = track.title,
            durationMs = track.durationMs,
            x = margin + 3,
            y = lineTop,
            width = textWidth,
            titleColor = titleColor,
        )
        renderInlineSegments(
            context = context,
            segments = buildSelectionMetaSegments(track, metaColor),
            x = margin + 3,
            y = lineTop + font.lineHeight + 1,
            maxWidth = textWidth,
        )
    }

    private fun renderTrackRowText(
        context: GuiGraphics,
        track: TrackInfo,
        index: Int,
        variant: TrackListVariant,
        rowY: Int,
        textWidth: Int,
        titleColor: Int,
        metaColor: Int,
    ) {
        val rowHeight = when (variant) {
            TrackListVariant.SEARCH -> searchRowH
            TrackListVariant.QUEUE -> queueRowH
        }
        val lineTop = rowY + (rowHeight - (font.lineHeight * 2 + 1)) / 2
        renderPrimaryRowLine(
            context = context,
            rowNumber = index + 1,
            title = track.title,
            durationMs = track.durationMs,
            x = margin + 3,
            y = lineTop,
            width = textWidth,
            titleColor = titleColor,
        )
        val segments = when (variant) {
            TrackListVariant.SEARCH -> buildTrackSearchMetaSegments(track, metaColor)
            TrackListVariant.QUEUE -> buildQueueMetaSegments(track, metaColor)
        }
        renderInlineSegments(
            context = context,
            segments = segments,
            x = margin + 3,
            y = lineTop + font.lineHeight + 1,
            maxWidth = textWidth,
        )
    }

    private fun renderPrimaryRowLine(
        context: GuiGraphics,
        rowNumber: Int,
        title: String,
        durationMs: Long,
        x: Int,
        y: Int,
        width: Int,
        titleColor: Int,
    ) {
        val durationText = formatListDuration(durationMs)
        val durationWidth = font.width(durationText)
        val durationX = x + width - durationWidth
        val titleMaxWidth = (durationX - x - 6).coerceAtLeast(0)
        context.drawString(
            font,
            McText.literal(truncate("${rowNumber}. $title", titleMaxWidth, font)),
            x,
            y,
            titleColor,
            false,
        )
        context.drawString(
            font,
            McText.literal(durationText),
            durationX,
            y,
            dimCol,
            false,
        )
    }

    private fun renderInlineSegments(
        context: GuiGraphics,
        segments: List<InlineTextSegment>,
        x: Int,
        y: Int,
        maxWidth: Int,
    ) {
        var drawX = x
        val endX = x + maxWidth
        for (segment in segments) {
            if (segment.text.isEmpty()) continue
            if (drawX >= endX) return
            val segmentWidth = font.width(segment.text)
            if (drawX + segmentWidth <= endX) {
                context.drawString(font, McText.literal(segment.text), drawX, y, segment.color, false)
                drawX += segmentWidth
                continue
            }

            val remainingWidth = (endX - drawX).coerceAtLeast(0)
            if (remainingWidth <= 0) return
            val truncated = truncate(segment.text, remainingWidth, font)
            if (truncated.isNotEmpty()) {
                context.drawString(font, McText.literal(truncated), drawX, y, segment.color, false)
            }
            return
        }
    }

    private fun renderRowButton(
        context: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        label: String,
        enabled: Boolean,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = mouseX in x..(x + width) && mouseY in y..(y + btnRowH)
        val buttonBg = when {
            !enabled -> 0xFF3A3A3A.toInt()
            hovered -> btnHover
            else -> btnBg
        }
        val buttonText = if (enabled) textCol else 0xFF999999.toInt()
        context.fill(x, y, x + width, y + btnRowH, buttonBg)
        context.fill(x, y, x + width, y + 1, 0xFF666688.toInt())
        context.fill(x, y + btnRowH - 1, x + width, y + btnRowH, 0xFF666688.toInt())
        context.drawString(
            font,
            McText.literal(label),
            x + (width - font.width(label)) / 2,
            y + (btnRowH - font.lineHeight) / 2 + 1,
            buttonText,
            false,
        )
    }

    private fun renderMenuButton(
        context: GuiGraphics,
        x: Int,
        y: Int,
        enabled: Boolean,
        mouseX: Int,
        mouseY: Int,
    ) = renderRowButton(context, x, y, btnRowMenuW, "...", enabled, mouseX, mouseY)

    private fun buildSelectionMetaSegments(track: SelectionEntry, metaColor: Int): List<InlineTextSegment> =
        buildList {
            appendMetaValue(track.artistDisplay.takeIf { it.isNotBlank() && it != "-" }, metaAccentCol)
            if (!track.isSelectable) {
                appendMetaValue(renderUnavailable(track), errCol)
            } else {
                appendMetaValue(track.album?.takeIf { it.isNotBlank() }, metaWarmCol)
            }
        }.ifEmpty { listOf(InlineTextSegment(" ", metaColor)) }

    private fun buildTrackSearchMetaSegments(track: TrackInfo, metaColor: Int): List<InlineTextSegment> =
        buildList {
            appendMetaValue(track.artistDisplay.takeIf { it.isNotBlank() && it != "-" }, metaAccentCol)
            if (!track.isAvailable) {
                appendMetaValue(renderUnavailable(track), errCol)
            } else {
                appendMetaValue(track.album?.takeIf { it.isNotBlank() }, metaWarmCol)
            }
        }.ifEmpty { listOf(InlineTextSegment(" ", metaColor)) }

    private fun buildQueueMetaSegments(track: TrackInfo, metaColor: Int): List<InlineTextSegment> =
        buildList {
            appendMetaValue(track.artistDisplay.takeIf { it.isNotBlank() && it != "-" }, metaAccentCol)
            track.submittedByUserName?.trim()?.takeIf { it.isNotEmpty() }?.let { appendMetaValue("@$it", metaWarmCol) }
            if (!track.isAvailable) {
                appendMetaValue(renderUnavailable(track), errCol)
            } else {
                appendMetaValue(sourceDisplayName(track.sourceId).takeIf { it.isNotBlank() }, metaSourceCol)
            }
        }.ifEmpty { listOf(InlineTextSegment(" ", metaColor)) }

    private fun MutableList<InlineTextSegment>.appendMetaValue(text: String?, color: Int) {
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (isNotEmpty()) {
            add(InlineTextSegment(" • ", dimCol))
        }
        add(InlineTextSegment(normalized, color))
    }

    // -------------------------------------------------------------------------
    // Mouse handling
    // -------------------------------------------------------------------------

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        if (rowActionMenu != null) {
            if (isWithinRowActionMenu(mx, my)) {
                return super.mouseClicked(mouseX, mouseY, button)
            }
            rowActionMenu = null
            rowActionMenuLayout = null
            rebuildScreenWidgets()
            return true
        }

        if (currentTab == Tab.SEARCH) {
            if (handleSearchSourceDropdownClick(mx, my)) return true
            if (searchSourceDropdownOpen && !isWithinSearchSourceButton(mx, my) && !isWithinSearchSourceDropdown(
                    mx,
                    my
                )
            ) {
                searchSourceDropdownOpen = false
                rebuildScreenWidgets()
            }
        }

        when (currentTab) {
            Tab.NOW_PLAYING -> {
                if (handleVolumeClick(mx, my, pressed = true)) return true
                if (handleSeekClick(mx, my, pressed = true)) return true
            }

            Tab.SEARCH -> {
                if (handleListControllerClick(TrackListVariant.SEARCH, mx, my)) return true
                if (handleListClick(
                        mx = mx,
                        my = my,
                        tracks = searchResults,
                        scrollOffset = searchScrollOffset,
                        rowHeight = searchRowH,
                        onPrimaryAction = { idx ->
                            val entry = searchResults[idx]
                            if (!entry.isSelectable) {
                                searchActionSuccess = null
                                searchActionError = renderUnavailable(entry)
                            } else if (entry.isDirectTrack && isSearchEntryDuplicate(entry)) {
                                searchActionSuccess = null
                                searchActionError = tr("error.moemusic.already_queued")
                            } else {
                                searchActionError = null
                                searchActionSuccess = null
                                if (entry.isDirectTrack) {
                                    pendingTrackSubmitOrigin = TrackListVariant.SEARCH
                                    pendingTrackSubmitRequestId = ClientPlaybackHandler.sendTrackSubmit(entry, TrackAddMode.NORMAL)
                                } else {
                                    pendingSelectionSubmitRequestId = ClientPlaybackHandler.sendSelectionSubmit(entry, TrackAddMode.NORMAL)
                                }
                            }
                        },
                        onSecondaryAction = { idx ->
                            val entry = searchResults[idx]
                            if (!entry.isSelectable) {
                                searchActionSuccess = null
                                searchActionError = renderUnavailable(entry)
                            } else if (entry.isDirectTrack && isSearchEntryDuplicate(entry)) {
                                searchActionSuccess = null
                                searchActionError = tr("error.moemusic.already_queued")
                            } else {
                                searchActionError = null
                                searchActionSuccess = null
                                if (entry.isDirectTrack) {
                                    pendingTrackSubmitOrigin = TrackListVariant.SEARCH
                                    pendingTrackSubmitRequestId = ClientPlaybackHandler.sendTrackSubmit(entry, TrackAddMode.SKIP_AUTOPLAY)
                                } else {
                                    pendingSelectionSubmitRequestId = ClientPlaybackHandler.sendSelectionSubmit(entry, TrackAddMode.SKIP_AUTOPLAY)
                                }
                            }
                        },
                        onMenu = { idx, anchorX, anchorY ->
                            rowActionMenu = rowActionMenuFor(searchResults[idx], anchorX, anchorY, TrackListVariant.SEARCH)
                            rebuildScreenWidgets()
                        },
                    )
                ) return true
            }

            Tab.QUEUE -> {
                if (handleListControllerClick(TrackListVariant.QUEUE, mx, my)) return true
                // Check click on the pinned "now playing" row first (menu only, no delete).
                val currentTrack = ClientPlaybackHandler.currentContext?.track
                if (currentTrack != null) {
                    val rowY = listY
                    val by = rowY + (currentTrackRowH - btnRowH) / 2
                    val menuX = rowMenuBtnX
                    if (mx in menuX..(menuX + btnRowMenuW) && my in by..(by + btnRowH)) {
                        rowActionMenu = rowActionMenuFor(currentTrack, menuX, by, TrackListVariant.QUEUE)
                        rebuildScreenWidgets()
                        return true
                    }
                    // Clicks anywhere else on the pinned row are consumed but no-op.
                    if (my in rowY..<rowY + currentTrackRowH && mx in margin..(margin + listW)) return true
                }
                // Delegate the rest to the standard list handler with the shifted start Y.
                if (handleListClick(
                    mx = mx,
                    my = my,
                    tracks = queueTracks,
                    scrollOffset = queueScrollOffset,
                    rowHeight = queueRowH,
                    listStartY = queueListStartY(),
                    onPrimaryAction = { idx ->
                        pendingTrackSubmitOrigin = TrackListVariant.QUEUE
                        pendingTrackSubmitRequestId = null
                        queueError = null
                        queueSuccess = null
                        pendingTrackSubmitRequestId = ClientPlaybackHandler.sendTrackSubmit(queueTracks[idx], TrackAddMode.PLAY_NOW)
                    },
                    onSecondaryAction = { idx ->
                        queueError = null
                        queueSuccess = null
                        pendingQueueRemoveRequestId = ClientPlaybackHandler.sendQueueRemoveRequest(queueTracks[idx])
                    },
                    onMenu = { idx, anchorX, anchorY ->
                        rowActionMenu = rowActionMenuFor(queueTracks[idx], anchorX, anchorY, TrackListVariant.QUEUE)
                        rebuildScreenWidgets()
                    },
                )) return true
            }
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        listControllerDragging?.let { variant ->
            updateListControllerDrag(variant, mouseY.toInt())
            return true
        }
        if (currentTab == Tab.NOW_PLAYING && volumeBarDragging) {
            handleVolumeClick(mouseX.toInt(), mouseY.toInt(), pressed = false)
            return true
        }
        if (currentTab == Tab.NOW_PLAYING && seekBarDragging) {
            handleSeekClick(mouseX.toInt(), mouseY.toInt(), pressed = false)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (listControllerDragging != null) {
            listControllerDragging = null
            listControllerDragThumbOffset = 0
            return true
        }
        if (volumeBarDragging) {
            volumeBarDragging = false
            ClientVolumeRuntime.persistConfiguredVolume()
            return true
        }
        if (seekBarDragging) {
            seekBarDragging = false
            val ctx = ClientPlaybackHandler.currentContext
            val durationMs = ctx?.track?.durationMs ?: 0L
            if (durationMs > 0) {
                val seekMs = (seekBarDragProgress * durationMs).toLong().coerceIn(0L, durationMs)
                playbackError = null
                playbackSuccess = null
                pendingPlaybackControlRequestId = ClientPlaybackHandler.sendPlaybackControl(PlaybackControlAction.SEEK, seekMs)
            }
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        val delta = if (amount > 0) -1 else 1
        when (currentTab) {
            Tab.SEARCH -> {
                setSearchAbsoluteOffset(currentSearchAbsoluteOffset() + delta)
                return true
            }

            Tab.QUEUE -> {
                val max = (queueTracks.size - visibleQueueRows()).coerceAtLeast(0)
                val newOff = (queueScrollOffset + delta).coerceIn(0, max)
                if (newOff != queueScrollOffset) {
                    queueScrollOffset = newOff
                }
                return true
            }

            else -> {}
        }
        return super.mouseScrolled(mouseX, mouseY, amount)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == 256 && currentTab == Tab.SEARCH && searchSourceDropdownOpen) {
            searchSourceDropdownOpen = false
            rebuildScreenWidgets()
            return true
        }
        if (keyCode == 256) {
            onClose()
            return true
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true
        }
        if (isConfirmKey(keyCode) && currentTab == Tab.SEARCH && searchEditBox?.isFocused == true) {
            val q = searchEditBox?.value?.trim().orEmpty()
            if (q.isNotEmpty()) {
                submitSearch(q)
            }
            return true
        }
        return false
    }

    // -------------------------------------------------------------------------
    // Click helpers
    // -------------------------------------------------------------------------

    private fun handleVolumeClick(mx: Int, my: Int, pressed: Boolean): Boolean {
        val volumeBarH = 6
        val volumeY = volumeBarY()
        val inBar = mx in seekBarX..(seekBarX + seekBarW) && my in (volumeY - 2)..(volumeY + volumeBarH + 2)
        if (!inBar && !volumeBarDragging) return false

        val percent = ClientVolume.gainToPercent(((mx - seekBarX).toFloat() / seekBarW).coerceIn(0f, 1f))
        ClientVolumeRuntime.setConfiguredVolumePercent(percent)
        if (pressed) volumeBarDragging = true
        return true
    }

    private fun handleSeekClick(mx: Int, my: Int, pressed: Boolean): Boolean {
        val seekBarH = 6
        val seekY = seekBarY()

        val inBar = mx in seekBarX..(seekBarX + seekBarW) && my in (seekY - 2)..(seekY + seekBarH + 2)
        if (!inBar && !seekBarDragging) return false

        val prog = ((mx - seekBarX).toFloat() / seekBarW).coerceIn(0f, 1f)
        seekBarDragProgress = prog
        if (pressed) seekBarDragging = true
        return true
    }

    private fun listControllerLayout(variant: TrackListVariant): ListControllerLayout {
        val listStartY = when (variant) {
            TrackListVariant.SEARCH -> listY
            TrackListVariant.QUEUE -> queueListStartY()
        }
        val totalCount = when (variant) {
            TrackListVariant.SEARCH -> effectiveSearchTotalCount()
            TrackListVariant.QUEUE -> queueTracks.size
        }
        val visibleCount = when (variant) {
            TrackListVariant.SEARCH -> visibleSearchRows()
            TrackListVariant.QUEUE -> visibleQueueRows()
        }
        val currentOffset = when (variant) {
            TrackListVariant.SEARCH -> currentSearchAbsoluteOffset()
            TrackListVariant.QUEUE -> queueScrollOffset
        }
        val maxOffset = (totalCount - visibleCount).coerceAtLeast(0)
        val trackY = listStartY + listControllerBtnH + listControllerGap
        val trackHeight = (listBottom - listControllerBtnH - listControllerGap - trackY).coerceAtLeast(1)
        val thumbHeight = if (maxOffset <= 0 || totalCount <= 0) {
            trackHeight
        } else {
            ((trackHeight.toFloat() * visibleCount.coerceAtLeast(1)) / totalCount.coerceAtLeast(1).toFloat())
                .roundToInt()
                .coerceIn(listControllerMinThumbH, trackHeight)
        }
        val thumbTravel = (trackHeight - thumbHeight).coerceAtLeast(0)
        val thumbY = if (thumbTravel == 0 || maxOffset == 0) {
            trackY
        } else {
            trackY + ((thumbTravel.toFloat() * currentOffset) / maxOffset.toFloat()).roundToInt()
        }
        return ListControllerLayout(
            x = listControllerX,
            width = listControllerW,
            upButtonY = listStartY,
            downButtonY = listBottom - listControllerBtnH,
            trackY = trackY,
            trackHeight = trackHeight,
            thumbY = thumbY,
            thumbHeight = thumbHeight,
            thumbTravel = thumbTravel,
            totalCount = totalCount,
            visibleCount = visibleCount,
            currentOffset = currentOffset,
            maxOffset = maxOffset,
        )
    }

    private fun handleListControllerClick(variant: TrackListVariant, mx: Int, my: Int): Boolean {
        val layout = listControllerLayout(variant)
        if (layout.maxOffset <= 0 || !layout.isWithinTrack(mx, my)) return false
        if (!layout.isWithinThumb(mx, my)) {
            val delta = if (my < layout.thumbY) -layout.visibleCount else layout.visibleCount
            when (variant) {
                TrackListVariant.SEARCH -> setSearchAbsoluteOffset(currentSearchAbsoluteOffset() + delta)
                TrackListVariant.QUEUE -> {
                    queueScrollOffset = (queueScrollOffset + delta).coerceIn(0, layout.maxOffset)
                }
            }
            return true
        }

        listControllerDragging = variant
        listControllerDragThumbOffset = my - layout.thumbY
        updateListControllerDrag(variant, my)
        return true
    }

    private fun updateListControllerDrag(variant: TrackListVariant, mouseY: Int) {
        val layout = listControllerLayout(variant)
        if (layout.maxOffset <= 0) return
        val thumbTop = (mouseY - listControllerDragThumbOffset).coerceIn(layout.trackY, layout.trackY + layout.thumbTravel)
        val ratio = if (layout.thumbTravel == 0) 0f else (thumbTop - layout.trackY).toFloat() / layout.thumbTravel.toFloat()
        val targetOffset = (ratio * layout.maxOffset.toFloat()).roundToInt().coerceIn(0, layout.maxOffset)
        when (variant) {
            TrackListVariant.SEARCH -> setSearchAbsoluteOffset(targetOffset)
            TrackListVariant.QUEUE -> queueScrollOffset = targetOffset
        }
    }

    private fun handleListClick(
        mx: Int,
        my: Int,
        tracks: List<*>,
        scrollOffset: Int,
        rowHeight: Int,
        listStartY: Int = listY,
        onPrimaryAction: (Int) -> Unit,
        onSecondaryAction: (Int) -> Unit,
        onMenu: (Int, Int, Int) -> Unit,
    ): Boolean {
        if (tracks.isEmpty()) return false
        val relRow = (my - listStartY) / rowHeight
        if (relRow < 0 || my < listStartY || relRow >= ((listBottom - listStartY) / rowHeight).coerceAtLeast(0)) return false
        val absRow = relRow + scrollOffset
        if (absRow >= tracks.size) return false

        val rowY = listStartY + relRow * rowHeight
        val bx = rowPrimaryBtnX
        val bx2 = rowSecondaryBtnX
        val by = rowY + (rowHeight - btnRowH) / 2

        if (mx in bx..(bx + btnRowW) && my in by..(by + btnRowH)) {
            onPrimaryAction(absRow)
            return true
        }
        if (mx in bx2..(bx2 + btnRowW) && my in by..(by + btnRowH)) {
            onSecondaryAction(absRow)
            return true
        }
        val menuX = rowMenuBtnX
        if (mx in menuX..(menuX + btnRowMenuW) && my in by..(by + btnRowH)) {
            onMenu(absRow, menuX, by)
            return true
        }
        return false
    }

    private fun handleSearchSourceDropdownClick(mx: Int, my: Int): Boolean {
        if (!searchSourceDropdownOpen) return false
        if (!isWithinSearchSourceDropdown(mx, my)) return false

        val sources = searchableSources()
        val index = (my - searchSourceDropdownY) / searchDropdownRowH
        val source = sources.getOrNull(index) ?: return false
        selectSearchSource(source.id)
        return true
    }

    private fun isWithinSearchSourceButton(mx: Int, my: Int): Boolean =
        mx in searchSourceX..(searchSourceX + searchSourceW) && my in searchSourceY..(searchSourceY + searchControlH)

    private fun isWithinSearchSourceDropdown(mx: Int, my: Int): Boolean {
        val height = searchableSources().size * searchDropdownRowH
        return mx in searchSourceDropdownX..(searchSourceDropdownX + searchSourceW) &&
                my in searchSourceDropdownY..(searchSourceDropdownY + height)
    }

    private fun renderRowActionMenuBackground(context: GuiGraphics) {
        val layout = rowActionMenuLayout ?: return
        context.fill(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height, menuBgCol)
        context.fill(layout.x, layout.y, layout.x + layout.width, layout.y + 1, 0xFF666688.toInt())
        context.fill(
            layout.x,
            layout.y + layout.height - 1,
            layout.x + layout.width,
            layout.y + layout.height,
            0xFF666688.toInt()
        )
        context.fill(layout.x, layout.y, layout.x + 1, layout.y + layout.height, 0xFF666688.toInt())
        context.fill(
            layout.x + layout.width - 1,
            layout.y,
            layout.x + layout.width,
            layout.y + layout.height,
            0xFF666688.toInt()
        )
    }

    private fun isWithinRowActionMenu(mx: Int, my: Int): Boolean {
        val layout = rowActionMenuLayout ?: return false
        return mx in layout.x..(layout.x + layout.width) && my in layout.y..(layout.y + layout.height)
    }

    private fun computeRowActionMenuLayout(menu: RowActionMenuState): RowActionMenuLayout {
        val menuWidth = ((menu.options.maxOfOrNull { font.width(it.label) } ?: 120) + 12)
            .coerceAtLeast(140)
            .coerceAtMost(width - margin * 2 - 4)
        val menuHeight = menu.options.size * btnRowH + (menu.options.size - 1) * menuRowGap + 6
        val maxX = (width - margin - menuWidth - 2).coerceAtLeast(margin + 2)
        val maxY = (height - margin - menuHeight - 2).coerceAtLeast(contentY)
        val menuX = (menu.anchorX + btnRowMenuW - menuWidth).coerceIn(margin + 2, maxX)
        val menuY = menu.anchorY.coerceIn(contentY, maxY)
        return RowActionMenuLayout(
            x = menuX,
            y = menuY,
            width = menuWidth,
            height = menuHeight,
        )
    }

    private fun rowActionMenuFor(
        entry: SelectionEntry,
        anchorX: Int,
        anchorY: Int,
        origin: TrackListVariant,
    ): RowActionMenuState? {
        val options = rowMenuOptionsFor(entry, origin)
        return options.takeIf { it.isNotEmpty() }?.let { RowActionMenuState(anchorX, anchorY, it) }
    }

    private fun rowActionMenuFor(
        track: TrackInfo,
        anchorX: Int,
        anchorY: Int,
        origin: TrackListVariant,
    ): RowActionMenuState? {
        val options = rowMenuOptionsFor(track, origin)
        return options.takeIf { it.isNotEmpty() }?.let { RowActionMenuState(anchorX, anchorY, it) }
    }

    private fun rowMenuOptionsFor(
        entry: SelectionEntry,
        origin: TrackListVariant = TrackListVariant.SEARCH
    ): List<RowActionMenuOption> =
        buildList {
            if (origin == TrackListVariant.SEARCH && isSearchEntryPlayNowAllowed(entry)) {
                add(RowActionMenuOption(tr("screen.moemusic.search.menu.play_now")) {
                    searchActionError = null
                    searchActionSuccess = null
                    if (entry.isDirectTrack) {
                        pendingTrackSubmitOrigin = TrackListVariant.SEARCH
                        pendingTrackSubmitRequestId = ClientPlaybackHandler.sendTrackSubmit(entry, TrackAddMode.PLAY_NOW)
                    } else {
                        pendingSelectionSubmitRequestId = ClientPlaybackHandler.sendSelectionSubmit(entry, TrackAddMode.PLAY_NOW)
                    }
                })
            }
            addAll(
                buildModerationOptions(
                    sourceId = entry.sourceId,
                    trackId = entry.directTrackId,
                    trackLabel = entry.title.ifBlank { entry.directTrackId.orEmpty() },
                    trackNote = buildTrackRuleNote(entry.title.ifBlank { entry.directTrackId.orEmpty() }, entry.artistDisplay),
                    origin = origin,
                )
            )
        }

    private fun rowMenuOptionsFor(
        track: TrackInfo,
        origin: TrackListVariant = TrackListVariant.QUEUE
    ): List<RowActionMenuOption> =
        buildModerationOptions(
            sourceId = track.sourceId,
            trackId = track.id.takeIf { it.isNotBlank() },
            trackLabel = track.title.ifBlank { track.id },
            trackNote = buildTrackRuleNote(track.title.ifBlank { track.id }, track.artistDisplay),
            origin = origin,
        )

    private fun buildModerationOptions(
        sourceId: String?,
        trackId: String?,
        trackLabel: String,
        trackNote: String?,
        origin: TrackListVariant,
    ): List<RowActionMenuOption> {
        val normalizedSourceId = sourceId?.takeIf { it.isNotBlank() } ?: return emptyList()
        return buildList {
            trackId?.takeIf { it.isNotBlank() }?.let { exactTrackId ->
                val localTrackAction = if (ContentFilterRuntime.isExactTrackBlocked(normalizedSourceId, exactTrackId)) {
                    ContentFilterRuleAction.UNBAN
                } else {
                    ContentFilterRuleAction.BAN
                }
                add(
                    RowActionMenuOption(
                        tr(
                            if (localTrackAction == ContentFilterRuleAction.BAN) {
                                "screen.moemusic.filter.menu.local_ban_track"
                            } else {
                                "screen.moemusic.filter.menu.local_unban_track"
                            }
                        )
                    ) {
                        applyLocalTrackFilterAction(
                            sourceId = normalizedSourceId,
                            trackId = exactTrackId,
                            trackLabel = trackLabel.ifBlank { exactTrackId },
                            note = trackNote,
                            action = localTrackAction,
                            origin = origin,
                        )
                    }
                )
                add(
                    RowActionMenuOption(tr("screen.moemusic.filter.menu.server_ban_track")) {
                        sendServerTrackFilterAction(
                            sourceId = normalizedSourceId,
                            trackId = exactTrackId,
                            note = trackNote,
                            ban = true,
                            origin = origin,
                        )
                    }
                )
                add(
                    RowActionMenuOption(tr("screen.moemusic.filter.menu.server_unban_track")) {
                        sendServerTrackFilterAction(
                            sourceId = normalizedSourceId,
                            trackId = exactTrackId,
                            note = trackNote,
                            ban = false,
                            origin = origin,
                        )
                    }
                )
            }
        }
    }

    private fun applyLocalTrackFilterAction(
        sourceId: String,
        trackId: String,
        trackLabel: String,
        note: String?,
        action: ContentFilterRuleAction,
        origin: TrackListVariant,
    ) {
        val result = ContentFilterRuleEditor.updateTrackRule(sourceId, trackId, action, note)
        applyClientSearchFilter()
        applyClientQueueFilter()
        setFilterNotice(
            origin = origin,
            success = localFilterNotice(
                renderLocalized(
                    trackFilterMessage(
                        action,
                        result.nowBlocked,
                        trackLabel,
                        result.changed
                    )
                )
            ),
            error = null,
        )
        rebuildScreenWidgets()
    }

    private fun sendServerTrackFilterAction(
        sourceId: String,
        trackId: String,
        note: String?,
        ban: Boolean,
        origin: TrackListVariant,
    ) {
        pendingServerFilterOrigin = origin
        setFilterNotice(origin, success = null, error = null)
        pendingContentFilterActionRequestId = ClientPlaybackHandler.sendContentFilterTrackAction(sourceId, trackId, note, ban)
        rebuildScreenWidgets()
    }

    private fun localFilterNotice(message: String): String =
        tr("screen.moemusic.filter.notice.local", message)

    private fun serverFilterNotice(message: String): String =
        tr("screen.moemusic.filter.notice.server", message)

    private fun setFilterNotice(origin: TrackListVariant, success: String?, error: String?) {
        when (origin) {
            TrackListVariant.SEARCH -> {
                searchActionError = error
                searchActionSuccess = if (error == null) success else null
            }

            TrackListVariant.QUEUE -> {
                queueError = error
                queueSuccess = if (error == null) success else null
            }
        }
    }

    private fun refreshSearchAfterServerFilterAction() {
        val sourceId = searchResultSourceId.ifBlank { currentSelectedSearchSourceId().orEmpty() }
        if (searchQuery.isBlank() || sourceId.isBlank()) {
            applyClientSearchFilter()
            rebuildScreenWidgets()
            return
        }
        selectedSearchSourceId = sourceId
        deferredSearchAbsoluteOffset = null
        requestSearchPage(
            query = searchQuery,
            sourceId = sourceId,
            offset = 0,
        )
    }

    // -------------------------------------------------------------------------
    // GuiListener — called from network thread, dispatch to render thread
    // -------------------------------------------------------------------------

    override fun onSearchSourcesChanged() {
        Minecraft.getInstance().execute {
            rowActionMenu = null
            rowActionMenuLayout = null
            syncSelectedSearchSource(ClientPlaybackHandler.cachedSearchState?.sourceId)
            if (searchResultSourceId.isNotBlank() && searchResultSourceId != currentSelectedSearchSourceId().orEmpty()) {
                clearDisplayedSearchState()
            }
            if (currentTab == Tab.SEARCH) {
                rebuildScreenWidgets()
            }
        }
    }

    override fun onSearchResponse(response: SearchResponse) {
        Minecraft.getInstance().execute {
            rowActionMenu = null
            rowActionMenuLayout = null
            val expectedRequestId = pendingSearchRequestId ?: return@execute
            if (response.request_id != expectedRequestId) {
                return@execute
            }

            pendingSearchRequestId = null
            searchLoading = false
            searchInputText = response.query
            searchError = response.failure.ifEmpty { null }
            if (response.failure.isEmpty()) {
                val pageTracks = response.entries.map { it.toApi() }
                searchQuery = response.query
                searchResultSourceId = response.source_id.ifBlank { currentSelectedSearchSourceId().orEmpty() }
                searchTotal = response.total
                searchHasMore = response.has_more
                selectedSearchSourceId = searchResultSourceId
                rawSearchResults = when {
                    response.offset <= 0 -> pageTracks
                    response.offset <= rawSearchResults.size -> rawSearchResults.take(response.offset) + pageTracks
                    else -> rawSearchResults + pageTracks
                }
                applyClientSearchFilter()
                cacheSearchState()
                if (!applyDeferredSearchAbsoluteOffsetIfNeeded()) {
                    searchScrollOffset = searchScrollOffset.coerceIn(0, maxLoadedSearchScrollOffset())
                    maybeLoadMoreSearchResults()
                }
            } else {
                deferredSearchAbsoluteOffset = null
            }
            if (currentTab == Tab.SEARCH) {
                rebuildScreenWidgets()
            }
        }
    }

    override fun onTrackSubmitResponse(response: TrackSubmitResponse) {
        Minecraft.getInstance().execute {
            if (pendingTrackSubmitRequestId != response.request_id) return@execute
            when (pendingTrackSubmitOrigin ?: TrackListVariant.SEARCH) {
                TrackListVariant.SEARCH -> {
                    if (response.failure.isEmpty()) {
                        searchActionError = null
                        searchActionSuccess = response.success.ifEmpty { null }
                        pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
                    } else {
                        searchActionSuccess = null
                        searchActionError = response.failure
                    }
                }

                TrackListVariant.QUEUE -> {
                    if (response.failure.isEmpty()) {
                        queueError = null
                        queueSuccess = response.success.ifEmpty { null }
                        pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
                    } else {
                        queueSuccess = null
                        queueError = response.failure
                    }
                }
            }
            pendingTrackSubmitRequestId = null
            pendingTrackSubmitOrigin = null
        }
    }

    override fun onIdentifierSubmitResponse(response: IdentifierSubmitResponse) {
        Minecraft.getInstance().execute {
            if (pendingIdentifierSubmitRequestId != response.request_id) return@execute
            pendingIdentifierSubmitRequestId = null
            if (response.failure.isEmpty() && response.choices.isNotEmpty()) {
                addIdentifierError = null
                addIdentifierSuccess = response.success.ifEmpty { null }
                applySelectionChoices(
                    entries = response.choices.map { it.toApi() },
                    successMessage = response.success.ifEmpty { null },
                    switchToSearchTab = true,
                )
            } else if (response.failure.isEmpty()) {
                addIdentifierError = null
                addIdentifierSuccess = response.success.ifEmpty { null }
                clearDisplayedSearchState()
                pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
            } else {
                addIdentifierSuccess = null
                addIdentifierError = response.failure
            }
        }
    }

    override fun onSelectionSubmitResponse(response: SelectionSubmitResponse) {
        Minecraft.getInstance().execute {
            if (pendingSelectionSubmitRequestId != response.request_id) return@execute
            pendingSelectionSubmitRequestId = null
            if (response.failure.isEmpty() && response.choices.isNotEmpty()) {
                applySelectionChoices(
                    entries = response.choices.map { it.toApi() },
                    successMessage = response.success.ifEmpty { null },
                    switchToSearchTab = false,
                )
            } else if (response.failure.isEmpty()) {
                searchActionError = null
                searchActionSuccess = response.success.ifEmpty { null }
                pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
            } else {
                searchActionSuccess = null
                searchActionError = response.failure
            }
        }
    }

    override fun onQueueResponse(response: QueueResponse) {
        Minecraft.getInstance().execute {
            if (pendingQueueRequestId != null && pendingQueueRequestId != response.request_id) return@execute
            rowActionMenu = null
            rowActionMenuLayout = null
            pendingQueueRequestId = null
            queueError = response.failure.ifEmpty { null }
            if (response.failure.isEmpty()) {
                rawQueueTracks = response.tracks.map { it.toApi() }
                applyClientQueueFilter()
                cacheSearchState()
            }
            if (currentTab == Tab.QUEUE) {
                rebuildScreenWidgets()
            }
        }
    }

    override fun onQueueRemoveResponse(response: QueueRemoveResponse) {
        Minecraft.getInstance().execute {
            if (pendingQueueRemoveRequestId != response.request_id) return@execute
            rowActionMenu = null
            rowActionMenuLayout = null
            pendingQueueRemoveRequestId = null
            if (response.failure.isNotEmpty()) {
                queueError = response.failure
            } else {
                queueError = null
                pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
            }
        }
    }

    override fun onPlaybackControlResponse(response: PlaybackControlResponse) {
        Minecraft.getInstance().execute {
            if (pendingPlaybackControlRequestId != response.request_id) return@execute
            pendingPlaybackControlRequestId = null
            playbackError = response.failure.ifEmpty { null }
            playbackSuccess = if (response.failure.isEmpty()) response.success.ifEmpty { null } else null
        }
    }

    override fun onLocalPlaybackBlocked(message: String) {
        Minecraft.getInstance().execute {
            playbackSuccess = null
            playbackError = message
            if (currentTab == Tab.NOW_PLAYING) {
                rebuildScreenWidgets()
            }
        }
    }

    override fun onInstancePlaybackStandby(message: String?) {
        Minecraft.getInstance().execute {
            playbackSuccess = null
            playbackError = message
            if (currentTab == Tab.NOW_PLAYING) {
                rebuildScreenWidgets()
            }
        }
    }

    override fun onContentFilterActionResponse(response: ContentFilterActionResponse) {
        Minecraft.getInstance().execute {
            if (pendingContentFilterActionRequestId != response.request_id) return@execute
            rowActionMenu = null
            rowActionMenuLayout = null
            val origin = pendingServerFilterOrigin ?: currentTab.toListVariantOrNull() ?: TrackListVariant.SEARCH
            pendingServerFilterOrigin = null
            pendingContentFilterActionRequestId = null

            if (response.failure.isNotEmpty()) {
                setFilterNotice(origin, success = null, error = serverFilterNotice(response.failure))
                rebuildScreenWidgets()
                return@execute
            }

            applyServerFilterActionOverlay(response)
            setFilterNotice(origin, success = serverFilterNotice(response.success), error = null)
            when (origin) {
                TrackListVariant.SEARCH -> refreshSearchAfterServerFilterAction()
                TrackListVariant.QUEUE -> pendingQueueRequestId = ClientPlaybackHandler.sendQueueRequest()
            }
            rebuildScreenWidgets()
        }
    }

    override fun onPlaybackStateChanged() {
        Minecraft.getInstance().execute {
            if (currentTab == Tab.NOW_PLAYING || currentTab == Tab.QUEUE) {
                rebuildScreenWidgets()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun removed() {
        ClientPlaybackHandler.guiListener = null
        listControllerDragging = null
        listControllerDragThumbOffset = 0
        super.removed()
    }

    override fun isPauseScreen() = false

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun nextAddMode(mode: TrackAddMode): TrackAddMode = when (mode) {
        TrackAddMode.NORMAL -> TrackAddMode.SKIP_AUTOPLAY
        TrackAddMode.SKIP_AUTOPLAY -> TrackAddMode.PLAY_NOW
        TrackAddMode.PLAY_NOW -> TrackAddMode.NORMAL
    }

    private fun isConfirmKey(key: Int): Boolean = key == 257 || key == 335

    private fun addModeLabel(mode: TrackAddMode): String = when (mode) {
        TrackAddMode.NORMAL -> tr("screen.moemusic.quick_add.mode.normal")
        TrackAddMode.SKIP_AUTOPLAY -> tr("screen.moemusic.quick_add.mode.autoplay")
        TrackAddMode.PLAY_NOW -> tr("screen.moemusic.quick_add.mode.now")
    }

    private fun formatListDuration(ms: Long): String = if (ms > 0L) formatDuration(ms) else "\u221E"

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "?:??"
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun renderUnavailable(entry: SelectionEntry): String =
        when (val reason = entry.unavailableReason) {
            is LocalizedText.Plain -> reason.text
            else -> renderLocalized(entry.unavailabilityMessage())
        }

    private fun renderUnavailable(track: TrackInfo): String =
        when (val reason = track.unavailableReason) {
            is LocalizedText.Plain -> reason.text
            else -> renderLocalized(track.unavailabilityMessage())
        }

    private fun renderLocalized(text: LocalizedText): String =
        ClientLocalization.render(text)

    private fun trackFilterMessage(
        action: ContentFilterRuleAction,
        nowBlocked: Boolean,
        label: String,
        changed: Boolean,
    ): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.track_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.track_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key(
            "action.moemusic.filter.track_unbanned",
            label
        )

        else -> LocalizedText.key("action.moemusic.filter.track_already_unbanned", label)
    }

    private fun artistFilterMessage(
        action: ContentFilterRuleAction,
        nowBlocked: Boolean,
        label: String,
        changed: Boolean,
    ): LocalizedText = when {
        nowBlocked && changed -> LocalizedText.key("action.moemusic.filter.artist_banned", label)
        nowBlocked -> LocalizedText.key("action.moemusic.filter.artist_already_banned", label)
        action == ContentFilterRuleAction.TOGGLE || changed -> LocalizedText.key(
            "action.moemusic.filter.artist_unbanned",
            label
        )

        else -> LocalizedText.key("action.moemusic.filter.artist_already_unbanned", label)
    }

    private fun applyServerFilterActionOverlay(response: ContentFilterActionResponse) {
        val key = exactFilterKey(response.source_id, response.value_id) ?: return
        when (response.target) {
            ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK -> {
                if (response.blocked_now) {
                    serverExactTrackOverlays += key
                } else {
                    serverExactTrackOverlays -= key
                }
            }

            ContentFilterTargetProto.CONTENT_FILTER_TARGET_ARTIST -> {
                if (response.blocked_now) {
                    serverExactArtistOverlays += key
                } else {
                    serverExactArtistOverlays -= key
                }
            }
        }
        applyClientSearchFilter()
        applyClientQueueFilter()
    }

    private fun serverFilterOverlayReason(entry: SelectionEntry): LocalizedText? =
        exactTrackOverlayReason(entry.sourceId, entry.directTrackId, entry.title) ?: exactArtistOverlayReason(entry.sourceId, entry.artists)

    private fun serverFilterOverlayReason(track: TrackInfo): LocalizedText? =
        exactTrackOverlayReason(track.sourceId, track.id, track.title) ?: exactArtistOverlayReason(track.sourceId, track.artists)

    private fun exactTrackOverlayReason(sourceId: String?, trackId: String?, displayValue: String): LocalizedText? {
        val key = exactFilterKey(sourceId, trackId) ?: return null
        if (key !in serverExactTrackOverlays) return null
        return LocalizedText.key(
            "error.moemusic.content_filter.track_blocked",
            displayValue.ifBlank { trackId.orEmpty().trim() },
        )
    }

    private fun exactArtistOverlayReason(sourceId: String?, artists: List<ArtistInfo>): LocalizedText? =
        artists.firstOrNull { artist ->
            exactFilterKey(sourceId, artist.effectiveId)?.let(serverExactArtistOverlays::contains) == true
        }?.let { artist ->
            LocalizedText.key(
                "error.moemusic.content_filter.artist_blocked",
                artist.displayName.ifBlank { artist.effectiveId },
            )
        }

    private fun exactFilterKey(sourceId: String?, valueId: String?): ExactFilterKey? {
        val normalizedSourceId = sourceId.orEmpty().trim().lowercase(Locale.ROOT)
        val normalizedValueId = valueId.orEmpty().trim().lowercase(Locale.ROOT)
        if (normalizedSourceId.isBlank() || normalizedValueId.isBlank()) return null
        return ExactFilterKey(normalizedSourceId, normalizedValueId)
    }

    private fun truncate(text: String, maxWidth: Int, font: net.minecraft.client.gui.Font): String {
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun renderFooterNotice(
        context: GuiGraphics,
        message: String?,
        color: Int,
        prefix: String = "",
    ) {
        if (message.isNullOrBlank()) return
        val y = height - margin - font.lineHeight - 4
        context.drawString(
            font,
            McText.literal(truncate(prefix + message, width - 2 * margin - 8, font)),
            margin + 4,
            y,
            color,
            true
        )
    }

    private fun buildTrackRuleNote(title: String, artistDisplay: String): String? {
        val normalizedTitle = title.trim().takeIf(String::isNotEmpty)
        val normalizedArtist = artistDisplay.trim().takeIf { it.isNotEmpty() && it != "-" }
        return when {
            normalizedTitle != null && normalizedArtist != null -> "$normalizedTitle - $normalizedArtist"
            normalizedTitle != null -> normalizedTitle
            normalizedArtist != null -> normalizedArtist
            else -> null
        }
    }

    private fun buildArtistRuleNote(artistLabel: String): String? =
        artistLabel.trim().takeIf { it.isNotEmpty() && it != "-" }

    private fun tr(key: String, vararg args: Any?): String =
        ClientLocalization.render(LocalizedText.key(key, *args))

    private fun Tab.toListVariantOrNull(): TrackListVariant? = when (this) {
        Tab.SEARCH -> TrackListVariant.SEARCH
        Tab.QUEUE -> TrackListVariant.QUEUE
        Tab.NOW_PLAYING -> null
    }

    companion object {
        val TITLE: Component = McText.translatable("screen.moemusic.title")
        private const val SEARCH_PAGE_SIZE = 20
        private const val SEARCH_PREFETCH_THRESHOLD = 2
    }
}
