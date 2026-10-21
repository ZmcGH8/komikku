// KMK -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition

/**
 * The zoom start side named by a preference value.
 *
 * 1 is "auto", which resolves from the reading direction; every other value names a side outright.
 *
 * Pure so it can be tested without a viewer: a wrong value here does not fail, it silently offsets
 * every zoomed page by half a screen, which is the kind of report that never gets traced back here.
 */
internal fun resolveZoomStartPosition(prefValue: Int, isReversed: Boolean): ZoomStartPosition = when (prefValue) {
    1 -> if (isReversed) ZoomStartPosition.RIGHT else ZoomStartPosition.LEFT
    2 -> ZoomStartPosition.LEFT
    3 -> ZoomStartPosition.RIGHT
    else -> ZoomStartPosition.CENTER
}

/** Scale types that the fit-mode anchor acts on; anything else keeps the library's own default. */
internal const val FIT_SCALE_WIDTH = 3
internal const val FIT_SCALE_HEIGHT = 4
internal const val FIT_SCALE_ORIGINAL = 5

/**
 * The home scale for a fit-mode scale type, or null when the type is not one fit mode handles.
 *
 * Types 3 and 4 fit width and height respectively; 5 is "original size" at 1:1, which relies on
 * [resolveOriginalSizeMinScale] to stop the reader zooming out past the page.
 *
 * A non-positive trimmed dimension is null rather than a scale: it is the divisor, and dividing by
 * it yields Infinity. The content box is *not* guarded, because a cutout taller than the screen makes
 * it negative and the original behaviour - clamp the scale to the floor - is the right one.
 */
internal fun resolveFitModeScale(
    scaleType: Int,
    contentWidth: Float,
    contentHeight: Float,
    trimWidth: Float,
    trimHeight: Float,
): Float? {
    if (scaleType != FIT_SCALE_WIDTH && scaleType != FIT_SCALE_HEIGHT && scaleType != FIT_SCALE_ORIGINAL) {
        return null
    }
    if (trimWidth <= 0f || trimHeight <= 0f) return null
    return when (scaleType) {
        FIT_SCALE_WIDTH -> contentWidth / trimWidth
        FIT_SCALE_HEIGHT -> contentHeight / trimHeight
        else -> 1f
    }.coerceAtLeast(MIN_HOME_SCALE)
}

/**
 * Smallest scale a page may sit at.
 *
 * The library's own fallback when an image page has no parent size is 0.01, so nothing below this is
 * reachable and clamping here cannot reject a scale the reader could actually have chosen.
 */
internal const val MIN_HOME_SCALE = 0.01f

/**
 * The scale that keeps the whole untrimmed page inside the content box; see fit-mode type 5.
 *
 * A zero page dimension makes that term infinite rather than NaN, so [minOf] discards it on its own -
 * which is why this needs no guard of its own.
 */
internal fun resolveOriginalSizeMinScale(
    contentWidth: Float,
    contentHeight: Float,
    pageWidth: Int,
    pageHeight: Int,
): Float = minOf(contentWidth / pageWidth, contentHeight / pageHeight).coerceAtLeast(MIN_HOME_SCALE)

/** Wide pages need a trim wider than the screen before a half-width zoom means anything. */
internal const val WIDE_PAGE_MIN_ASPECT = 1.1f

/**
 * The home scale that fits half a wide page to the screen, or null when the page is not wide.
 *
 * Null for every "leave it alone" case, which is what lets the caller fall through to fit mode: the
 * page already fits, it is not wide, or it is wide but no wider than twice the screen's own aspect -
 * in which case showing it at half width would not actually gain anything.
 *
 * The aspect test uses the *smaller* of the trimmed and untrimmed ratios, so cropping cannot promote
 * a narrow page to wide.
 */
internal fun resolveWideZoomScale(
    trimWidth: Int,
    trimHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): Float? {
    if (screenWidth <= 0 || screenHeight <= 0) return null
    // Zero or negative trims are already excluded by the fits-on-screen test below, except that a
    // zero trim height would divide to Infinity - harmless, but guarding it keeps every ratio finite
    // and makes the three bail-outs legible as one condition.
    if (trimWidth <= 0 || trimHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    if (trimWidth <= screenWidth) return null

    val aspectRatio = minOf(
        trimWidth.toFloat() / trimHeight.toFloat(),
        imageWidth.toFloat() / imageHeight.toFloat(),
    )
    if (aspectRatio < WIDE_PAGE_MIN_ASPECT) return null
    if (aspectRatio <= 2f * screenWidth.toFloat() / screenHeight) return null

    return (screenWidth.toFloat() / (trimWidth / 2f)).takeIf { it.isFinite() && it > 0f }
}

/**
 * How far to push a fitted page down so its first row clears a display cutout.
 *
 * Fractional because it divides by the page's own drawn height, so it survives a rotation without
 * being recomputed. Clamped at zero: a cutout shorter than the trim offset would otherwise push the
 * page up and out of the viewport.
 */
internal fun resolveCutoutHomeY(
    cutoutPx: Float,
    trimTopY: Float,
    homeScale: Float,
    screenHeight: Float,
): Float {
    if (homeScale <= 0f || screenHeight <= 0f) return 0f
    return maxOf(0f, (cutoutPx - trimTopY) / (homeScale * screenHeight))
}

/**
 * The zoom stack every decoded page has to pass through before it is shown.
 *
 * All three run on a freshly built [ImagePage.ImageSingle] - at decode, and again after a
 * dual-page height-match rescale or a translation swap replaced the image - so they are collected
 * here rather than repeated at each of those call sites.
 */
internal fun WebGpuViewer.applyZoomPolicy(page: ImagePage.ImageSingle) {
    if (!isDualPageMode()) {
        if (!applyWideZoomIfNeeded(page)) {
            applyFitModeAnchor(page)
        }
    }
    applyDoubleTapZoomPolicy(page)
}

/**
 * The viewer library always performs its built-in double-tap zoom, so when the
 * preference is disabled the page's max scale is clamped to its home scale - the
 * zoom animation then lands where it started. The paged "disable zoom in" pref
 * clamps the same way, and the library additionally caps pinch/double-tap-drag
 * gestures at a restricted maxScale. The library sentinel -1f restores the computed default.
 */
internal fun WebGpuViewer.applyDoubleTapZoomPolicy(page: ImagePage.ImageSingle) {
    if (isDestroyed || isContinuous) return
    if (config.doubleTapZoom && !config.disableZoomIn) {
        try {
            page.maxScale = -1f
        } catch (_: Exception) {
        }
        return
    }
    val w = try {
        pager.state.width
    } catch (_: Exception) {
        0
    }
    val h = try {
        pager.state.height
    } catch (_: Exception) {
        0
    }
    if (w <= 0 || h <= 0) return
    try {
        page.maxScale = page.homeScale
    } catch (_: Exception) {
    }
}

internal fun WebGpuViewer.applyWideZoomIfNeeded(page: ImagePage.ImageSingle): Boolean {
    if (isDestroyed || !config.landscapeZoom) return false
    val image = page.image ?: return false

    val screenW = try {
        pager.state.width
    } catch (_: Exception) {
        0
    }
    val screenH = try {
        pager.state.height
    } catch (_: Exception) {
        0
    }
    if (screenW <= 0 || screenH <= 0) return false

    // Scale to fit half the image width to the full screen width, or decline when the page is not
    // wide enough for that to mean anything - in which case fit mode takes over.
    val wideScale = resolveWideZoomScale(
        trimWidth = page.trimWidth,
        trimHeight = page.trimHeight,
        imageWidth = image.width,
        imageHeight = image.height,
        screenWidth = screenW,
        screenHeight = screenH,
    ) ?: return false

    page.homeScale = wideScale

    // need to set parent for positioning to work
    page.parent = pager.state

    val minX = page.minX(page.homeScale)
    val maxX = page.maxX(page.homeScale)

    val startX = when (config.imageZoomType) {
        ZoomStartPosition.LEFT -> maxX
        ZoomStartPosition.RIGHT -> minX
        ZoomStartPosition.CENTER -> 0f
    }

    page.homeX = startX
    page.scale = page.homeScale
    page.x = startX
    page.y = page.homeY
    return true
}

internal fun WebGpuViewer.applyFitModeAnchor(page: ImagePage.ImageSingle) {
    if (isDestroyed) return
    val scaleType = config.imageScaleType

    val image = page.image ?: return

    val screenW = try {
        pager.state.width
    } catch (_: Exception) {
        0
    }
    val screenH = try {
        pager.state.height
    } catch (_: Exception) {
        0
    }
    if (screenW <= 0 || screenH <= 0) return

    val w = page.trimWidth.toFloat()
    val h = page.trimHeight.toFloat()

    val cutoutTopPx = pager.state.cutoutTopPx
    val contentW = screenW.toFloat()
    val contentH = if (pager.state.avoidCutout && cutoutTopPx > 0f) screenH - cutoutTopPx else screenH.toFloat()

    val homeScale = resolveFitModeScale(scaleType, contentW, contentH, w, h) ?: return
    page.homeScale = homeScale

    page.parent = pager.state

    if (scaleType == FIT_SCALE_ORIGINAL) { // original size
        val minScaleComputed = resolveOriginalSizeMinScale(contentW, contentH, page.width, page.height)
        if (page.homeScale < minScaleComputed) {
            page.minScale = page.homeScale
        }
    }

    // zoom start for fit height/original size
    if (scaleType == FIT_SCALE_HEIGHT || scaleType == FIT_SCALE_ORIGINAL) {
        val minX = page.minX(page.homeScale)
        val maxX = page.maxX(page.homeScale)
        page.homeX = when (config.imageZoomType) {
            ZoomStartPosition.LEFT -> maxX
            ZoomStartPosition.RIGHT -> minX
            ZoomStartPosition.CENTER -> 0f
        }
    }

    // push below cutout for fit width/original size
    val trimTop = image.trim?.top ?: 0
    val imageTopY = (screenH - page.height * page.homeScale) / 2f
    val trimTopY = imageTopY + trimTop * page.homeScale
    if ((scaleType == FIT_SCALE_WIDTH || scaleType == FIT_SCALE_ORIGINAL) && h * page.homeScale > screenH) {
        val target = if (pager.state.avoidCutout && cutoutTopPx > 0f) {
            if (pager.state.alwaysAvoidCutout) cutoutTopPx / 2f else cutoutTopPx
        } else {
            0f
        }
        page.homeY = resolveCutoutHomeY(target, trimTopY, page.homeScale, screenH.toFloat())
    }

    page.scale = page.homeScale
    page.x = page.homeX
    page.y = page.homeY
}
// KMK <--
