package exh.yakuyomi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.PipelineErrorCode
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.Translator

/**
 * Runs the library pipeline across the slices of a tall page and stitches the results back into one
 * full-size bitmap.
 *
 * Slicing happens before the pipeline's own 4000px pre-scale so detail is preserved on very tall
 * pages. Every slice is translated on its own bitmap, which is why the translator arrives as a
 * factory: vision-capable providers must see their own slice rather than the whole page.
 *
 * Failure policy is all-or-nothing. Any [PageResult.Failed] slice fails the page so no partially
 * translated page is ever shown; skipped slices contribute their original artwork. A page where
 * every slice was skipped reports [PageResult.Skipped].
 */
class LongPageStitcher(
    private val components: NativeComponents,
    private val configFactory: EngineConfigFactory,
) {

    /**
     * Translates [page] slice by slice and returns one full-size result.
     *
     * [page] is only read, never recycled: the caller owns it and reuses it for the failure paths.
     */
    suspend fun translate(
        page: Bitmap,
        cfg: EngineConfig,
        translatorFactory: suspend (Bitmap) -> Translator?,
    ): PageResult {
        val width = page.width
        val height = page.height
        val slices = LongPageSlicer.buildSlices(height, LongPageSlicer.planCuts(page))

        // A page that planned into fewer than two slices is not actually tall; run it whole.
        if (slices.size < 2) return runWholePage(page, cfg, translatorFactory)

        val pieces = mutableListOf<SlicePiece>()
        var failure: PageResult.Failed? = null

        for (slice in slices) {
            val input = try {
                Bitmap.createBitmap(page, 0, slice.y, width, slice.height)
            } catch (t: Throwable) {
                failure = PageResult.Failed("slice crop failed: ${t.message}", PipelineErrorCode.UNKNOWN)
                break
            }
            val result = try {
                val translator = try {
                    translatorFactory(input)
                } catch (t: Throwable) {
                    failure = PageResult.Failed("slice translator failed: ${t.message}", PipelineErrorCode.TRANSLATE_FAILED)
                    break
                }
                if (translator == null) {
                    failure = PageResult.Failed("no translator for slice", PipelineErrorCode.TRANSLATE_FAILED)
                    break
                }
                runSlice(input, cfg, translator)
            } finally {
                runCatching { input.recycle() }
            }
            when (result) {
                is PageResult.Failed -> {
                    failure = result
                    break
                }
                is PageResult.Skipped -> {
                    val art = try {
                        Bitmap.createBitmap(page, 0, slice.y, width, slice.height)
                    } catch (t: Throwable) {
                        failure = PageResult.Failed("slice art copy failed: ${t.message}", PipelineErrorCode.UNKNOWN)
                        break
                    }
                    pieces += SlicePiece(slice, art, result.stats, null, result.reason)
                }
                is PageResult.Translated -> pieces += SlicePiece(slice, result.page, result.stats, result.analysis, null)
            }
        }

        failure?.let {
            recyclePieces(pieces)
            return it
        }
        if (pieces.size != slices.size) {
            recyclePieces(pieces)
            return PageResult.Failed("sliced translation incomplete", PipelineErrorCode.UNKNOWN)
        }
        if (pieces.all { it.analysis == null }) {
            val stats = sumStats(pieces.map { it.stats })
            val reason = pieces.mapNotNull { it.skipReason }.distinct().take(2).joinToString("; ")
                .ifBlank { "No text detected" }
            recyclePieces(pieces)
            return PageResult.Skipped(reason, stats, PipelineErrorCode.DETECT_FAILED)
        }
        return stitch(pieces, slices, width, height)
    }

    private suspend fun runWholePage(
        page: Bitmap,
        cfg: EngineConfig,
        translatorFactory: suspend (Bitmap) -> Translator?,
    ): PageResult = Pipeline(
        components.detector,
        components.ocr,
        translatorFactory(page),
        components.inpainter,
        cfg,
        configFactory.resolveTypeface(),
    ).translatePage(page)

    private suspend fun runSlice(bitmap: Bitmap, cfg: EngineConfig, translator: Translator): PageResult = Pipeline(
        components.detector,
        components.ocr,
        translator,
        components.inpainter,
        cfg,
        configFactory.resolveTypeface(),
    ).translatePage(bitmap)

    private fun stitch(
        pieces: List<SlicePiece>,
        slices: List<LongPageSlicer.Slice>,
        width: Int,
        height: Int,
    ): PageResult {
        val stitched = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            recyclePieces(pieces)
            return PageResult.Failed("slice stitch failed: ${t.message}", PipelineErrorCode.UNKNOWN)
        }
        try {
            val canvas = Canvas(stitched)
            // Lower slices first, upper last: the upper slice owns the overlap band, so it must win.
            for (i in pieces.indices.reversed()) {
                val piece = pieces[i]
                val dst = Rect(0, piece.slice.y, width, piece.slice.y + piece.slice.height)
                canvas.drawBitmap(piece.bitmap, null, dst, null)
            }
        } catch (t: Throwable) {
            recyclePieces(pieces)
            runCatching { stitched.recycle() }
            return PageResult.Failed("slice stitch failed: ${t.message}", PipelineErrorCode.UNKNOWN)
        }

        val mask = stitchMask(pieces, width, height)
        val regions = offsetRegions(pieces, slices)
        pieces.forEach { runCatching { it.bitmap.recycle() } }
        val stats = sumStats(pieces.map { it.stats })
        return PageResult.Translated(stitched, stats, buildAnalysis(mask, regions, width, height))
    }

    /**
     * The analysis mask is optional: the pipeline reports regions even when it could not build one.
     * When a translated page has regions but no mask, an empty mask is allocated so callers always
     * get a mask for a translated page.
     */
    private fun buildAnalysis(mask: Bitmap?, regions: List<TextRegion>, width: Int, height: Int): PageAnalysis? {
        if (mask != null) return PageAnalysis(mask, regions)
        if (regions.isEmpty()) return null
        val fallback = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return null
        return PageAnalysis(fallback, regions)
    }

    private fun stitchMask(pieces: List<SlicePiece>, width: Int, height: Int): Bitmap? {
        if (pieces.none { it.analysis != null }) return null
        val mask = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            recycleAnalysisMasks(pieces)
            return null
        }
        return try {
            val canvas = Canvas(mask)
            for (i in pieces.indices.reversed()) {
                val piece = pieces[i]
                val sliceMask = piece.analysis?.mask ?: continue
                if (sliceMask.isRecycled) continue
                val dst = Rect(0, piece.slice.y, width, piece.slice.y + piece.slice.height)
                canvas.drawBitmap(sliceMask, null, dst, null)
            }
            mask
        } catch (_: Throwable) {
            runCatching { mask.recycle() }
            null
        } finally {
            recycleAnalysisMasks(pieces)
        }
    }

    /**
     * Moves per-slice regions into page coordinates, keeping only regions whose centre lies in the
     * slice that owns it - the upper slice owns the shared overlap band.
     */
    private fun offsetRegions(
        pieces: List<SlicePiece>,
        slices: List<LongPageSlicer.Slice>,
    ): List<TextRegion> {
        val out = mutableListOf<TextRegion>()
        for (i in pieces.indices) {
            val piece = pieces[i]
            val analysis = piece.analysis ?: continue
            for (region in analysis.regions) {
                val cyPage = region.cy + piece.slice.y
                if (!LongPageSlicer.ownsCenter(i, slices, cyPage)) continue
                val lines = region.lines.map { line ->
                    TextLine(line.quad.map { Pt(it.x, it.y + piece.slice.y) }, line.score).also {
                        it.direction = line.direction
                        it.text = line.text
                        it.translatedText = line.translatedText
                        it.tightQuad = line.tightQuad?.map { p -> Pt(p.x, p.y + piece.slice.y) }
                    }
                }
                TextRegion(lines, region.direction, region.angle, region.cx, cyPage, region.boxW, region.boxH).also {
                    it.translatedText = region.translatedText
                    it.onArt = region.onArt
                    it.dbgStd = region.dbgStd
                    it.dbgWhite = region.dbgWhite
                    out += it
                }
            }
        }
        return out
    }

    private class SlicePiece(
        val slice: LongPageSlicer.Slice,
        val bitmap: Bitmap,
        val stats: PageStats,
        val analysis: PageAnalysis?,
        val skipReason: String?,
    )

    private fun recyclePieces(pieces: List<SlicePiece>) {
        pieces.forEach { piece ->
            runCatching { piece.bitmap.recycle() }
            piece.analysis?.mask?.let { mask -> runCatching { mask.recycle() } }
        }
    }

    private fun recycleAnalysisMasks(pieces: List<SlicePiece>) {
        pieces.mapNotNull { it.analysis?.mask }.forEach { mask -> runCatching { mask.recycle() } }
    }

    /**
     * Sums per-slice stats. Every field of [PageStats] is listed explicitly; a field added to the
     * engine later must be added here too, or sliced pages will report it as zero.
     */
    private fun sumStats(all: List<PageStats>): PageStats = PageStats(
        lines = all.sumOf { it.lines },
        regions = all.sumOf { it.regions },
        kept = all.sumOf { it.kept },
        detectMs = all.sumOf { it.detectMs },
        ocrMs = all.sumOf { it.ocrMs },
        translateMs = all.sumOf { it.translateMs },
        inpaintMs = all.sumOf { it.inpaintMs },
        renderMs = all.sumOf { it.renderMs },
        wallMs = all.sumOf { it.wallMs },
        promptTokens = all.sumOf { it.promptTokens },
        completionTokens = all.sumOf { it.completionTokens },
    )
}
