// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState.ContinuousPosition
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuReadingPositionStore.PositionData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// Restoring a reading position after the chapter is re-opened.
//
// Both paths share one shape, and it is the whole reason they cannot simply be applied at
// `setChapters` time: the page they have to measure against is still a placeholder. A placeholder
// reserves a viewport height, so restoring straight away lands the viewport in what will later be
// empty space - a black screen, then phantom page walks on the first scroll. Both therefore wait for
// the target page to decode and only then apply the stored offset, against real heights.
//
// Each arms its viewer's "restore pending" id first, so a page report landing in the meantime does
// not save the fresh default position over the stored one.

/** How long the restore waits for the target page before giving up (10 polls of 100ms). */
private const val RESTORE_POLL_LIMIT = 100

/**
 * Continuous: waits for the saved page to decode, then restores documentY, zoom and horizontal
 * offset. Aborts if the reader scrolls, navigates, or the chapter changes first.
 */
internal fun WebGpuViewer.restoreContinuousAfterDecode(
    chapterId: Long?,
    targetIndex: Int,
    stored: PositionData,
) {
    val cont = pager as? ImageViewContinuous
    val st = cont?.state
    if (st == null) {
        pendingContinuousRestoreChapterId = null
        return
    }
    val restoreChapterId = chapterId
    val anchorPage = currentPage
    val startDocY = try {
        st.documentY
    } catch (_: Exception) {
        0f
    }
    scope.launch {
        try {
            if (!awaitRestoreTarget(restoreChapterId, targetIndex, anchorPage)) return@launch
            // The user moved the viewport while the page decoded: their position is more recent
            // than the one being restored, so it wins.
            val nowDocY = try {
                st.documentY
            } catch (_: Exception) {
                startDocY
            }
            if (!nowDocY.isFinite() || !startDocY.isFinite() || abs(nowDocY - startDocY) > 2f) return@launch
            // KMK --> pageIndexHint is relative to the state's anchor page, not absolute -
            // resolveDocumentYForRestore hands it to documentYForPageIndex, which subtracts the
            // anchor's own index from it. This path only runs when the anchor was rebuilt from the
            // saved page itself, so the saved page IS the anchor and the hint is 0. Passing the
            // stored absolute index instead made the walk step `absolute - anchor` pages in the
            // wrong direction - wrong position, and an O(absolute) chain walk.
            st.restorePosition(
                ContinuousPosition(
                    documentY = stored.documentY,
                    scale = stored.zoom,
                    offsetX = stored.offsetX,
                    pageIndexHint = 0,
                    fractionWithinPage = stored.fraction,
                ),
                animate = false,
            )
            // KMK <--
            try {
                pager.state.invalidate()
            } catch (_: Exception) {}
        } finally {
            if (pendingContinuousRestoreChapterId == restoreChapterId) {
                pendingContinuousRestoreChapterId = null
            }
        }
    }
}

/**
 * Paged parity for [restoreContinuousAfterDecode]: waits for the same target page, then reapplies
 * the stored zoom and horizontal pan clamped to the live page's own bounds.
 */
internal fun WebGpuViewer.restorePagedZoomAfterDecode(
    chapterId: Long?,
    targetIndex: Int,
    needed: Boolean,
    stored: PositionData?,
) {
    if (!needed || stored == null) return
    val restoreChapterId = chapterId
    val anchorPage = currentPage
    val wantZoom = stored.zoom
    val wantX = stored.offsetX
    scope.launch {
        try {
            if (!awaitRestoreTarget(restoreChapterId, targetIndex, anchorPage)) return@launch
            try {
                val page = pager.state.getPage(0)
                if (page != null && wantZoom.isFinite()) {
                    page.scale = wantZoom.coerceIn(page.minScale, page.maxScale)
                    if (wantX.isFinite() && wantX != 0f) {
                        val minX = page.minX(page.scale)
                        val maxX = page.maxX(page.scale)
                        page.animateTo(targetX = wantX.coerceIn(minX, maxX), targetY = page.y)
                    }
                }
            } catch (_: Exception) {
            }
            try {
                pager.state.invalidate()
            } catch (_: Exception) {}
        } finally {
            if (pendingPagedRestoreChapterId == restoreChapterId) {
                pendingPagedRestoreChapterId = null
            }
        }
    }
}

/**
 * Polls until the saved page has decoded (or failed) and the surface has a size.
 *
 * Returns false - and the caller applies nothing - if the viewer is torn down, the chapter changes,
 * the reader navigates away, or the wait runs out. An [ErrorPage] counts as ready: the page is
 * never going to decode, and restoring onto it is better than never restoring at all.
 */
private suspend fun WebGpuViewer.awaitRestoreTarget(
    restoreChapterId: Long?,
    targetIndex: Int,
    anchorPage: ViewerPage?,
): Boolean {
    var waited = 0
    while (waited < RESTORE_POLL_LIMIT) {
        if (isDestroyed) return false
        if (viewerChapters?.currChapter?.chapter?.id != restoreChapterId) return false
        val target = synchronized(lock) {
            findInCache(PageKey.Reader(restoreChapterId, targetIndex)) as? ViewerReaderPage
        }
        val surfaceReady = try {
            pager.state.width > 0 && pager.state.height > 0
        } catch (_: Exception) {
            false
        }
        if (surfaceReady && (target?.isDecoded == true || target?.imagePage is ErrorPage)) {
            return currentPage === anchorPage
        }
        delay(100)
        waited++
    }
    return false
}
// KMK <--
