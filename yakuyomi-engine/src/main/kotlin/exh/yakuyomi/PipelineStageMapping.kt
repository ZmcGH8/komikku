package exh.yakuyomi

import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.PipelineErrorCode

/**
 * Maps the engine's terminal result onto the stage the page reached.
 *
 * A [li.joye.yakuyomi.engine.PipelineErrorCode] names the stage that failed, which is more precise
 * than anything the page count can imply, so failures report the real stage instead of a guess.
 */
internal fun stageForFailure(code: PipelineErrorCode): TranslationStatus.PipelineStage = when (code) {
    PipelineErrorCode.DETECT_FAILED -> TranslationStatus.PipelineStage.DETECTING
    PipelineErrorCode.OCR_FAILED -> TranslationStatus.PipelineStage.OCR
    PipelineErrorCode.GROUP_FAILED -> TranslationStatus.PipelineStage.OCR
    PipelineErrorCode.TRANSLATE_FAILED -> TranslationStatus.PipelineStage.TRANSLATING
    PipelineErrorCode.ALL_FILTERED -> TranslationStatus.PipelineStage.TRANSLATING
    PipelineErrorCode.INPAINT_FAILED -> TranslationStatus.PipelineStage.INPAINTING
    PipelineErrorCode.RENDER_FAILED -> TranslationStatus.PipelineStage.TYPESETTING
    PipelineErrorCode.INVALID_BITMAP -> TranslationStatus.PipelineStage.IDLE
    PipelineErrorCode.TIMEOUT -> TranslationStatus.PipelineStage.TRANSLATING
    PipelineErrorCode.UNKNOWN -> TranslationStatus.PipelineStage.IDLE
}

/**
 * Last stage a page actually reached, read from the per-stage timings the pipeline reports.
 *
 * Checks the stages in pipeline order and returns the furthest one with recorded work. A stage that
 * measured 0ms still ran - a fast OCR pass routinely does - so this is the furthest point reached,
 * not the slowest stage, and a 0ms suffix does not mean the stage was skipped.
 */
internal fun lastStageReached(stats: PageStats): TranslationStatus.PipelineStage = when {
    stats.renderMs > 0 -> TranslationStatus.PipelineStage.TYPESETTING
    stats.inpaintMs > 0 -> TranslationStatus.PipelineStage.INPAINTING
    stats.translateMs > 0 -> TranslationStatus.PipelineStage.TRANSLATING
    stats.ocrMs > 0 -> TranslationStatus.PipelineStage.OCR
    else -> TranslationStatus.PipelineStage.DETECTING
}
