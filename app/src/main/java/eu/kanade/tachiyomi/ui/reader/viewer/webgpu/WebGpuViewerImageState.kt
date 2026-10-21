// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import ca.mpreg.webgpuviewer.renderer.UpscalerArtCnn
import ca.mpreg.webgpuviewer.renderer.UpscalerCatmullRom
import ca.mpreg.webgpuviewer.transition.TransitionBasic
import ca.mpreg.webgpuviewer.transition.TransitionCube
import ca.mpreg.webgpuviewer.transition.TransitionCubeOuter
import ca.mpreg.webgpuviewer.transition.TransitionFade
import ca.mpreg.webgpuviewer.transition.TransitionFadeWhite
import ca.mpreg.webgpuviewer.transition.TransitionFlip
import ca.mpreg.webgpuviewer.transition.TransitionFlipLeft
import ca.mpreg.webgpuviewer.transition.TransitionFlipRight
import ca.mpreg.webgpuviewer.transition.TransitionNone
import ca.mpreg.webgpuviewer.transition.TransitionSphere
import ca.mpreg.webgpuviewer.transition.TransitionStackDown
import ca.mpreg.webgpuviewer.transition.TransitionStackLeft
import ca.mpreg.webgpuviewer.transition.TransitionStackRight
import ca.mpreg.webgpuviewer.transition.TransitionStackUp
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.TransitionAnimation
import kotlinx.coroutines.launch

// Everything that turns reader preferences into viewer state: the GPU filter chain, the page
// offset, the transition, the upscaler and the debug HUD.
//
// Split out of the viewer class because it is the *answer* to "which settings changed and what does
// that cost" - the settings channel in `WebGpuConfig` decides that and calls in here - and reads as
// a list of independent decisions rather than as part of the viewer's lifecycle.

/**
 * Re-resolves spread pairing after [WebGpuConfig.shiftDoublePage] toggles.
 * Positions derive live, so a re-fetch is enough to re-pair everything.
 */
internal fun WebGpuViewer.refreshSpreads() {
    if (isDestroyed) return
    try {
        pager.state.invalidate()
    } catch (_: Exception) {
    }
}

// KMK -->
/**
 * Contrast floor the e-ink preset applies.
 *
 * E-ink panels have no backlight to wash grey out, so a mid contrast reads as flat and the page
 * looks blank in bright surroundings. The floor is applied on top of whatever the reader chose
 * rather than replacing it, so raising contrast past it still works.
 */
internal const val EINK_MIN_CONTRAST = 1.15f

/** The contrast actually handed to the filter chain; see [EINK_MIN_CONTRAST]. Pure. */
internal fun effectiveContrast(contrast: Float, einkPreset: Boolean): Float =
    if (einkPreset) maxOf(contrast, EINK_MIN_CONTRAST) else contrast

/**
 * Whether the brightness/contrast pass has anything to do.
 *
 * Both knobs are neutral at 0 and 1 respectively, so the untouched pair is exactly the identity
 * transform - attaching it anyway would cost a full-screen GPU pass per frame to reproduce the input
 * exactly. The comparison is against 1f rather than a tolerance because both values come from an
 * integer percent preference, so there is nothing in between to be fuzzy about.
 */
internal fun isBrightnessContrastActive(brightness: Float, contrast: Float): Boolean =
    brightness != 0f || contrast != 1f

/**
 * Whether the LUT pass has anything to do.
 *
 * Three things have to agree, and only the first is obvious from the settings screen: a LUT must
 * have actually loaded - which a missing or malformed custom file does not produce - the intensity
 * must be above zero, and the preset must not be the "none" sentinel. The chain runs them in a
 * fixed order, so a pass attached that does nothing still costs bandwidth ahead of the ones that do.
 */
internal fun isLutActive(hasLut: Boolean, intensity: Float, preset: String): Boolean =
    hasLut && intensity > 0f && preset != WEBGPU_LUT_PRESET_NONE

internal fun WebGpuViewer.applyPageOffset() {
    try {
        val offset = config.pageOffset
        if (offset == 0) {
            pager.translationX = 0f
            return
        }
        val w = if (pager.width > 0) pager.width else activity.resources.displayMetrics.widthPixels
        pager.translationX = w * offset / 100f * 0.5f
    } catch (_: Exception) {
    }
}
// KMK <--

/**
 * Applies state-only reader settings (transition, cutout, zoom floors, gap,
 * theme colors) without touching decoded pages. Prefs that change what a decode
 * produces (crop, dual-page geometry, match-heights, theme background baking)
 * still rebuild via [WebGpuViewer.config]'s settings channel when the diff's highest
 * impact is REDECODE or above.
 */
internal fun WebGpuViewer.applyImageState() {
    if (isDestroyed) return
    // KMK --> A theme change comes through here.
    cachedBackgroundColor = null
    cachedOnBackgroundColor = null
    // KMK <--
    // KMK -->
    // Post-process color filters apply live with no page re-decode: uniforms
    // update in place and the chain reconciles attach/detach every state change.
    try {
        darkModeFilter.amoled = config.webgpuDarkModeAmoled
        darkModeFilter.tolerance = config.darkModeTolerance
        darkModeFilter.chunkRange = config.darkModeChunkRange
        darkModeFilter.enabled = config.webgpuDarkMode
        val effective = effectiveContrast(config.contrast, config.einkPreset)
        brightnessContrastFilter.brightness = config.brightness
        brightnessContrastFilter.contrast = effective
        brightnessContrastFilter.enabled = isBrightnessContrastActive(config.brightness, effective)
        hlgFilter.exposure = config.hlgExposure
        hlgFilter.enabled = config.hlgEnabled
        einkGrayscaleFilter.saturation = if (config.einkPreset) 0f else 1f
        einkGrayscaleFilter.enabled = config.einkPreset
        lutFilter.intensity = config.lutIntensity
        lutFilter.enabled = true
        resolveLutFilter()
        val lutActive = isLutActive(
            hasLut = lutFilter.lut != null,
            intensity = config.lutIntensity,
            preset = config.lutPreset,
        )
        val desired = buildList {
            if (brightnessContrastFilter.enabled) add(brightnessContrastFilter)
            if (hlgFilter.enabled) add(hlgFilter)
            if (lutActive) add(lutFilter)
            if (einkGrayscaleFilter.enabled) add(einkGrayscaleFilter)
            if (darkModeFilter.enabled) add(darkModeFilter)
        }
        val current = pager.state.filters.filters
        if (current != desired) {
            pager.state.filters.filters = desired
        } else {
            pager.state.invalidate()
        }
    } catch (_: Exception) {
    }
    try {
        applyTranslationCompare()
    } catch (_: Exception) {
    }
    try {
        syncPerfHud()
    } catch (_: Exception) {
    }
    // KMK <--
    // KMK -->
    // Swap the tile upscaler only on a real change: assigning drops every
    // generated tile, so an unconditional set here would re-gen tiles on
    // each state-only change. The cached instance is reused because the
    // setter is identity-guarded. Unsupported devices fall back to
    // Catmull-Rom inside the tile path by themselves.
    try {
        val wantArtCnn = config.artCnnUpscaler
        val hasArtCnn = pager.state.upscaler is UpscalerArtCnn
        if (wantArtCnn != hasArtCnn) {
            pager.state.upscaler = if (wantArtCnn) {
                (artCnnUpscaler ?: UpscalerArtCnn().also { artCnnUpscaler = it })
            } else {
                UpscalerCatmullRom()
            }
        }
    } catch (_: Exception) {
    }
    // KMK <--
    // KMK -->
    // Fast render skips the tile cache on decoded pages (direct mipmap draw);
    // flipping back reuses the same path once, tiles regenerate on demand.
    try {
        val fast = config.fastRender
        synchronized(lock) {
            pageCache.values.forEach {
                (it.imagePage as? ImagePage.ImageSingle)?.let { single ->
                    if (!single.isAnimated) single.highQuality = !fast
                }
            }
        }
        pager.state.invalidate()
    } catch (_: Exception) {
    }
    // KMK <--
    pager.state.apply {
        val isDual = isDualPageMode()
        transition = if (config.einkPreset) {
            if (isVertical) TransitionNone.Vertical else TransitionNone
        } else {
            when (if (isDual) config.transitionAnimationDual else config.transitionAnimation) {
                // KMK -->
                TransitionAnimation.NONE -> if (isVertical) TransitionNone.Vertical else TransitionNone
                // KMK <--
                TransitionAnimation.BASIC -> if (isVertical) TransitionBasic.Vertical else TransitionBasic
                TransitionAnimation.FLIP -> TransitionFlip
                TransitionAnimation.FLIP_LEFT -> TransitionFlipLeft
                TransitionAnimation.FLIP_RIGHT -> TransitionFlipRight
                TransitionAnimation.STACK_LEFT -> TransitionStackLeft
                TransitionAnimation.STACK_RIGHT -> TransitionStackRight
                TransitionAnimation.STACK_UP -> TransitionStackUp
                TransitionAnimation.STACK_DOWN -> TransitionStackDown
                TransitionAnimation.SPHERE -> TransitionSphere
                TransitionAnimation.CUBE_INSIDE -> TransitionCube
                TransitionAnimation.CUBE_OUTSIDE -> TransitionCubeOuter
                TransitionAnimation.FADE -> TransitionFade
                TransitionAnimation.FADE_WHITE -> TransitionFadeWhite
            }
        }

        when (if (isDual) config.cutoutModeDual else config.cutoutMode) {
            ReaderPreferences.CutoutMode.IGNORE -> avoidCutout = false

            ReaderPreferences.CutoutMode.AVOID -> {
                avoidCutout = true
                alwaysAvoidCutout = false
            }

            ReaderPreferences.CutoutMode.SHIFT -> {
                avoidCutout = true
                alwaysAvoidCutout = true
            }
        }

        (this as? ImageViewerContinuousState)?.let {
            // KMK -->
            // WEBTOON (long strip, no gap) always locks zoom-out to the strip
            // width; CONTINUOUS_VERTICAL follows the disable-zoom-out pref.
            val isWebtoonStrip = (this@applyImageState as? WebGpuViewerContinuous)?.useGap == false
            val lockToStrip = isWebtoonStrip || config.zoomOutDisabled
            homeScale = config.continuousMinWidth / 100f
            minScale = if (lockToStrip) 0f else 0.1f
            // Never clobber the reader's zoom here: these listeners fire on
            // state-only changes too. Only lift out of an illegal range
            // (homeScale's own setter already lifts scale when the floor
            // itself rises).
            if (lockToStrip && scale < homeScale) scale = homeScale

            if ((this@applyImageState as? WebGpuViewerContinuous)?.useGap == true) {
                pageGap = config.continuousGap / 100f
            } else {
                // WEBTOON (useGap = false) must pin the gap to 0 rather than leave it. The
                // strip clears to transparent black (ImageViewerState.renderPass), so any gap
                // the state still holds paints a hard black band between every page.
                pageGap = 0f
            }
            // KMK <--
        }
    }
}

/**
 * Rebuilds the posted LUT when the preset/custom path changed. The custom file is parsed off the
 * decode thread and dropped if a newer selection arrived while it was being read.
 */
internal fun WebGpuViewer.resolveLutFilter() {
    val preset = config.lutPreset
    val path = config.lutCustomPath
    val key = "$preset|$path"
    if (appliedLutKey == key) return
    if (preset == WEBGPU_LUT_PRESET_NONE) {
        appliedLutKey = key
        lutFilter.lut = null
        return
    }
    val builtIn = webgpuBuiltInLut(preset)
    if (builtIn != null) {
        appliedLutKey = key
        lutFilter.lut = builtIn
        return
    }
    if (preset == WEBGPU_LUT_PRESET_CUSTOM && path.isNotBlank()) {
        val generation = ++lutResolveGeneration
        scope.launch(decodeDispatcher) {
            try {
                val parsed = webgpuParseCustomLut(path)
                if (generation != lutResolveGeneration || isDestroyed) return@launch
                appliedLutKey = key
                lutFilter.lut = parsed
                try {
                    pager.state.invalidate()
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            }
        }
        return
    }
    appliedLutKey = key
    lutFilter.lut = null
}

/**
 * Swaps each translated page between its baked result and the original it replaced, without
 * letting either go unreferenced.
 */
internal fun WebGpuViewer.applyTranslationCompare() {
    val showOriginal = config.compareTranslation
    synchronized(lock) {
        if (isDestroyed) return
        pageCache.values.toList().forEach { page ->
            val readerPage = page as? ViewerReaderPage ?: return@forEach
            if (!readerPage.hasTranslation) return@forEach
            val original = readerPage.compareOriginal ?: return@forEach
            if (original.destroyed) return@forEach
            if (showOriginal) {
                val displayed = readerPage.imagePage
                if (displayed !== original && displayed is ImagePage.ImageSingle) {
                    readerPage.compareTranslated?.let {
                        if (it !== displayed) {
                            try {
                                it.cleanup()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    readerPage.compareTranslated = displayed
                    readerPage.imagePage = original
                }
            } else {
                val parked = readerPage.compareTranslated ?: return@forEach
                if (parked.destroyed) {
                    readerPage.compareTranslated = null
                    return@forEach
                }
                if (readerPage.imagePage !== parked) {
                    readerPage.imagePage = parked
                    readerPage.compareTranslated = null
                }
            }
        }
    }
    try {
        pager.state.invalidate()
    } catch (_: Exception) {
    }
}

/**
 * Debug frame-timing overlay. Gated on the perf-HUD preference *and* a debuggable build type, and
 * self-limiting: the text is only re-read from the renderer twice a second even though the view is
 * brought to front every frame the spinner is spinning.
 */
internal fun WebGpuViewer.syncPerfHud() {
    val want = config.perfHud && eu.kanade.tachiyomi.util.system.isDebugBuildType && !isDestroyed
    try {
        ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.profilingEnabled = want
    } catch (_: Exception) {
    }
    if (!want) {
        try {
            perfHudView?.visibility = View.GONE
        } catch (_: Exception) {
        }
        return
    }
    val parent = pager.parent as? ViewGroup ?: return
    val hud = try {
        perfHudView ?: TextView(pager.context).apply {
            setBackgroundColor(0x99000000.toInt())
            setTextColor(0xFF00FF00.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 8, 12, 8)
            visibility = View.GONE
            perfHudView = this
        }
    } catch (_: Exception) {
        null
    } ?: return
    if (hud.parent !== parent) {
        try {
            (hud.parent as? ViewGroup)?.removeView(hud)
            parent.addView(
                hud,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP or android.view.Gravity.START,
                ),
            )
        } catch (_: Exception) {
            return
        }
    }
    try {
        hud.visibility = View.VISIBLE
        hud.bringToFront()
        val now = android.os.SystemClock.uptimeMillis()
        if (now - perfHudLastUpdate < 500) return
        perfHudLastUpdate = now
        val renderer = ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
        val poolKb = try {
            pager.state.filters.poolBytes() / 1024
        } catch (_: Exception) {
            -1L
        }
        hud.text = "avg %.1fms · fps %.0f · tile %dKB".format(
            renderer.recentAvgFrameTimeMs,
            renderer.estimatedFps,
            poolKb,
        )
    } catch (_: Exception) {
    }
}
// Mihon <--
