// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.content.res.Configuration
import ca.mpreg.webgpuviewer.viewer.ImagePage
import kotlin.math.roundToInt

// KMK -->
/** Floor for a spread side upload: below this the GPU rejects the texture (gralloc 0x3b). */
internal const val SPREAD_MIN_SIDE_DIM = 8

/** Hard ceiling for any rescaled spread dimension. */
internal const val SPREAD_MAX_DIM = 8192

/** Clamp any spread dimension into the uploadable 1..[SPREAD_MAX_DIM] range. Pure math. */
internal fun clampSpreadDim(value: Int): Int = value.coerceIn(1, SPREAD_MAX_DIM)

/**
 * Width preserving aspect when scaling [srcWidth]x[srcHeight] to [targetHeight], clamped to
 * 1..[SPREAD_MAX_DIM]; null on non-positive dims (hard noop, never divides by zero).
 * Pure math, no Android dependency.
 */
internal fun scaledSpreadWidth(srcWidth: Int, srcHeight: Int, targetHeight: Int): Int? {
    if (srcWidth <= 0 || srcHeight <= 0 || targetHeight <= 0) return null
    return (srcWidth.toFloat() * targetHeight / srcHeight).roundToInt().coerceIn(1, SPREAD_MAX_DIM)
}

/** A resolved height-match: which side is shorter and the clamped taller height. */
internal data class SpreadHeightMatchPlan(val shorterIsLeft: Boolean, val targetHeight: Int)

/**
 * Resolve one deterministic shorter-to-taller pass, or null when it is a noop (zero dims,
 * already equal heights). The target is the clamped taller height, so the taller side is
 * never shrunk (which collapsed spreads to tiny on e-ink resume). Pure math.
 */
internal fun resolveSpreadHeightMatch(
    leftWidth: Int,
    leftHeight: Int,
    rightWidth: Int,
    rightHeight: Int,
): SpreadHeightMatchPlan? {
    if (leftWidth <= 0 || leftHeight <= 0 || rightWidth <= 0 || rightHeight <= 0) return null
    if (leftHeight == rightHeight) return null
    return SpreadHeightMatchPlan(
        shorterIsLeft = leftHeight < rightHeight,
        targetHeight = clampSpreadDim(maxOf(leftHeight, rightHeight)),
    )
}

/** True when a decoded side is big enough to rescale or rescale toward. Pure math. */
internal fun isSpreadSideViable(width: Int, height: Int): Boolean =
    width >= SPREAD_MIN_SIDE_DIM && height >= SPREAD_MIN_SIDE_DIM

/**
 * Gate for firing a rescale: needs a plan, retained source bytes, and no rescale already
 * running. Evicted bytes (freed on eviction) are terminal for the pass - the next decode
 * re-arms via fresh bytes - so a dead spread can never spin a retry storm. Pure math.
 */
internal fun shouldAttemptSpreadRescale(
    hasBytes: Boolean,
    rescaleInFlight: Boolean,
    plan: SpreadHeightMatchPlan?,
): Boolean = plan != null && hasBytes && !rescaleInFlight
// KMK <--

/**
 * Check if dual page mode is currently active based on config and view dimensions.
 * Dual page is never active for continuous (scrolling) viewers.
 * Single source of truth: the legacy split toggle plus the pager's
 * single/double/automatic layout switch (automatic pairs pages in landscape,
 * mirroring PagerViewer's setDoublePageMode).
 */
fun WebGpuViewer.isDualPageMode(): Boolean {
    if (isContinuous) return false
    if (config.dualPageSplit) return true
    if (config.doublePages) return true
    if (config.autoDoublePages) {
        return activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    return false
}

/**
 * Check if the given page can form a spread with the next page.
 * Uses page.spreadPosition to determine: anchor + partner = spread
 * RTL: RIGHT is anchor, looks for LEFT on next
 * LTR: LEFT is anchor, looks for RIGHT on next
 */
internal fun WebGpuViewer.canFormSpread(page: ViewerReaderPage): Boolean {
    return spreadPartner(page) != null
}

// KMK -->
/** Who [page] pairs with, or null. One verdict for [buildSpreadPage] and progress reporting. */
internal fun WebGpuViewer.spreadPartner(page: ViewerReaderPage): ViewerReaderPage? {
    if (!isDualPageMode()) return null
    val anchorPosition = if (isReversed xor config.invertDoublePages) SpreadPosition.RIGHT else SpreadPosition.LEFT
    val partnerPosition = if (isReversed xor config.invertDoublePages) SpreadPosition.LEFT else SpreadPosition.RIGHT
    if (page.spreadPosition != anchorPosition) return null
    val next = (page.next as? ViewerReaderPage)?.takeIf { it.page.chapter == page.page.chapter } ?: return null
    return next.takeIf { it.spreadPosition == partnerPosition && canPairShapes(page, it) }
}

/** Page to report progress for - the spread's lastmost page, not the anchor. */
internal fun WebGpuViewer.progressPage(page: ViewerPage): ViewerReaderPage? {
    val readerPage = page as? ViewerReaderPage ?: return null
    return spreadPartner(readerPage) ?: readerPage
}
// KMK <--

/**
 * Get the anchor page for a spread.
 * RTL: anchor is RIGHT, for LEFT page returns previous RIGHT
 * LTR: anchor is LEFT, for RIGHT page returns previous LEFT
 */
internal fun WebGpuViewer.getSpreadAnchor(page: ViewerPage): ViewerPage {
    if (!isDualPageMode()) return page
    if (page !is ViewerReaderPage) return page

    val anchorPosition = if (isReversed xor config.invertDoublePages) SpreadPosition.RIGHT else SpreadPosition.LEFT
    val partnerPosition = if (isReversed xor config.invertDoublePages) SpreadPosition.LEFT else SpreadPosition.RIGHT

    // If this is a partner page, check if previous is anchor
    if (page.spreadPosition == partnerPosition) {
        val prev = page.prev as? ViewerReaderPage ?: return page
        if (prev.page.chapter == page.page.chapter && prev.spreadPosition == anchorPosition &&
            canPairShapes(prev, page)
        ) {
            return prev
        }
    }

    // This page is the anchor or standalone
    return page
}

internal fun WebGpuViewer.buildSpreadPage(page: ViewerPage): ImagePage {
    // For ViewerTransitionPage, return its imagePage directly
    if (page !is ViewerReaderPage) {
        return page.imagePage
    }

    // Only form spreads in dual page mode
    if (!isDualPageMode()) {
        return page.imagePage
    }

    // Whatever the page is holding takes its half of the seam, decoded or not:
    // [ImagePage.ImageSpread] draws a [ImagePage.Render] side into its own half. A page left
    // out would take the whole viewport instead, hiding its partner with it.
    val imagePage = page.imagePage

    if (page.spreadPosition == SpreadPosition.SINGLE) {
        retireSpread(page.spreadPage)
        page.spreadPage = null
        return imagePage
    }

    val nextReaderPage = spreadPartner(page)
    val partnerImagePage = nextReaderPage?.imagePage

    // LEFT/RIGHT map directly to the spread's left/right slot - independent of reading
    // direction, which only decides which side is the anchor for pairing purposes above.
    val left = if (page.spreadPosition == SpreadPosition.LEFT) imagePage else partnerImagePage
    val right = if (page.spreadPosition == SpreadPosition.RIGHT) imagePage else partnerImagePage

    // Reuse existing spread if the sides match - preserves transform state
    val previous = page.spreadPage
    if (existing(left, right, previous)) {
        return previous!!
    }
    // KMK --> Retire the spread being replaced. Its sides change identity whenever one of them
    // decodes, rescales, or is swapped for a translation - so a partner decode used to build a new
    // spread and drop the old one on the floor, with nothing calling cleanup() on it. The library
    // keys the tile cache by page identity, so that abandoned spread kept its whole grid, and the
    // tiles stayed resident because the only sweep drops grids for *destroyed* pages. Reusing it
    // would not have worked either: the sides are different objects, so the cached grid no longer
    // matches what it would draw.
    // KMK <--
    retireSpread(previous)
    val spread = ImagePage.ImageSpread(left, right)
    page.spreadPage = spread

    // KMK -->
    maybeScheduleSpreadHeightMatch(page, spread, nextReaderPage)
    // KMK <--
    return spread
}

private fun retireSpread(spread: ImagePage.ImageSpread?) {
    try {
        spread?.cleanup()
    } catch (_: Exception) {
    }
}

private fun existing(left: ImagePage?, right: ImagePage?, spreadPage: ImagePage.ImageSpread?): Boolean {
    return spreadPage != null && spreadPage.left === left && spreadPage.right === right
}
// Mihon <--
