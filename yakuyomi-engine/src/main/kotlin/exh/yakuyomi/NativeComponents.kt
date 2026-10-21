package exh.yakuyomi

import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.Ocr

/**
 * The three native sessions the pipeline needs, built and warmed together.
 *
 * The NCNN/ONNX backends are not thread-safe and are expensive to construct, so [YakuyomiEngine]
 * keeps exactly one instance alive and guards it with a mutex. Grouping them means a build either
 * yields all three or none, and teardown cannot forget one.
 */
class NativeComponents(
    val detector: Detector,
    val ocr: Ocr,
    val inpainter: Inpainter,
) {
    fun closeAll() {
        runCatching { detector.close() }
        runCatching { ocr.close() }
        runCatching { inpainter.close() }
    }
}
