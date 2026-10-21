package exh.yakuyomi

import android.graphics.Typeface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation

/**
 * Turns user preferences into the native engine's configuration, and answers the two rendering
 * decisions that depend on them: which typeface to typeset with and whether a page must be forced
 * to horizontal layout.
 *
 * Extracted from [YakuyomiEngine], which owns native session lifetime. Keeping the mapping here
 * means the clamping ranges for every knob live in one readable place, and adding a knob does not
 * mean editing a class that also manages native handles.
 */
class EngineConfigFactory(
    private val prefs: TranslationPreferences,
) {

    /**
     * The preferences that are baked into the native detector/OCR/inpainter sessions when they are
     * constructed. Changing any of them requires those sessions to be rebuilt.
     *
     * This list lives beside the mapping that reads them rather than in the engine that consumes the
     * result, so a knob added to [defaultConfig] is registered here in the same edit. Previously the
     * engine merged these by hand, and a new tuning value could be added to the config and silently
     * ignored at runtime because the session was never rebuilt.
     *
     * Render settings are deliberately absent: [renderConfig] is read per page, so they take effect
     * without a rebuild.
     */
    fun configChanges(): Flow<Unit> = merge(
        *listOf(
            prefs.detectorInputSize().changes().map { Unit },
            prefs.detectorBoxThreshold().changes().map { Unit },
            prefs.detectorSegThreshold().changes().map { Unit },
            prefs.ocrMinProb().changes().map { Unit },
            prefs.ocrBicubic().changes().map { Unit },
            prefs.ocrUnsharp().changes().map { Unit },
            prefs.inpainterMethod().changes().map { Unit },
            prefs.inpainterTileSize().changes().map { Unit },
            prefs.inpainterMaskDilate().changes().map { Unit },
            prefs.inpainterBboxPad().changes().map { Unit },
            prefs.inpainterUniformFastPath().changes().map { Unit },
        ).toTypedArray(),
    )

    fun renderConfig(): RenderConfig {
        val defaults = EngineConfig().render
        val max = prefs.renderFontSizeMax().get().coerceIn(30, 100)
        val min = prefs.renderFontSizeMin().get().coerceIn(6, 20).coerceAtMost(max - 2)
        return defaults.copy(
            // "fixed" makes outline resolve to the fill colour on light backgrounds; the renderer
            // then skips the outline there, because stroking black-on-black closes glyph counters.
            colorMode = "fixed",
            fixedTextColor = resolveTextColor(),
            fontScale = prefs.renderFontScale().get().coerceIn(0.6f, 1.2f),
            expandW = prefs.renderExpandW().get().coerceIn(1.0f, 2.0f),
            expandH = prefs.renderExpandH().get().coerceIn(1.0f, 2.0f),
            tateChuYoko = prefs.renderTateChuYoko().get(),
            fontSizeMax = max,
            fontSizeMin = min,
            colTrim = 1,
            rowTrim = 1,
            lineSpacing = 1.05f,
            adaptiveStroke = true,
            rtlSupport = false,
        )
    }

    /** Custom hex override wins when parseable; otherwise the preset int preference. */
    private fun resolveTextColor(): Int {
        val hex = prefs.translationTextColorHex().get().trim().removePrefix("#")
        if (hex.isNotEmpty()) {
            val v = hex.toLongOrNull(16) ?: return prefs.translationTextColor().get()
            return when (hex.length) {
                6 -> 0xFF000000.toInt() or v.toInt() // RRGGBB -> opaque
                8 -> v.toInt() // AARRGGBB
                else -> prefs.translationTextColor().get()
            }
        }
        return prefs.translationTextColor().get()
    }

    fun defaultConfig(): EngineConfig {
        val defaults = EngineConfig()
        return EngineConfig(
            render = renderConfig(),
            detector = defaults.detector.copy(
                dbnetInputSize = prefs.detectorInputSize().get().coerceIn(512, 1536),
                dbBoxThreshold = prefs.detectorBoxThreshold().get().coerceIn(0.3f, 0.9f),
                segThreshold = prefs.detectorSegThreshold().get().coerceIn(0.05f, 0.4f),
            ),
            ocr = defaults.ocr.copy(
                minProb = prefs.ocrMinProb().get().coerceIn(0.2f, 0.9f),
                useBicubic = prefs.ocrBicubic().get(),
                ocrUnsharp = prefs.ocrUnsharp().get(),
                adaptiveConcurrency = true,
            ),
            inpainter = defaults.inpainter.copy(
                method = prefs.inpainterMethod().get().takeIf { it in setOf("aot", "boxfill") } ?: "aot",
                tileSize = prefs.inpainterTileSize().get().coerceIn(256, 1024),
                maskDilate = prefs.inpainterMaskDilate().get().coerceIn(4f, 48f),
                bboxPad = prefs.inpainterBboxPad().get().coerceIn(0, 32),
                uniformFastPath = prefs.inpainterUniformFastPath().get(),
                featherRadius = 1,
                preserveAspect = true,
            ),
            translator = defaults.translator,
            pipeline = defaults.pipeline,
        )
    }

    fun horizontalConfig(): EngineConfig = defaultConfig().copy(
        render = defaultConfig().render.copy(orientation = TextOrientation.HORIZONTAL),
    )

    /**
     * The user-selected typeset font, or null for "default" so the engine falls back to the system
     * font. Resolved per call so a preference change takes effect without restarting.
     */
    fun resolveTypeface(): Typeface? {
        val name = prefs.fontFamily().get()
        if (name.isBlank() || name.equals("default", ignoreCase = true)) return null
        return try {
            Typeface.create(name, Typeface.NORMAL)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Vertical layout is inherited from the source page, which is wrong when the target language
     * reads left-to-right. CJK targets keep AUTO so vertical text stays vertical.
     */
    fun shouldForceHorizontal(targetLang: String?): Boolean {
        val lang = targetLang?.trim()?.lowercase() ?: return false
        val cjkTargets = setOf("ja", "zh", "ko")
        return cjkTargets.none { lang.startsWith(it) }
    }
}
