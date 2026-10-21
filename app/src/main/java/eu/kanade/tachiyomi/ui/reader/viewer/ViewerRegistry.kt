package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.annotation.ColorInt
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewerContinuous
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import logcat.LogPriority.DEBUG
import mihon.app.di.globalAppGraph
import tachiyomi.core.common.util.system.logcat

/**
 * Pluggable factory for [Viewer] implementations.
 *
 * A provider returns a [Viewer] for the reading modes it handles, or null to
 * let the next provider try. Providers are consulted in [order]; the built-in
 * [WebGpuProvider] and [LegacyProvider] mirror the historical
 * `ReadingMode.toViewer` mapping. Custom readers (e.g. a novel viewer) register
 * via [ViewerRegistry.register] with a lower [order] to take precedence.
 *
 * ### A null return is a claim, not a shrug
 *
 * "I do not handle this" has to mean *that kind of chapter*, not *this instance of it*. A provider
 * that declines a chapter another provider is the only renderer for does not fall back to a wrong
 * viewer - it hands the reader to an implementation that cannot display the content, which shows as
 * an empty page with no error anywhere. That is worse than failing loudly.
 *
 * So the image providers refuse text chapters outright ([declinesTextChapters]), and if nothing
 * handles what is left, [create] throws rather than silently rendering nothing.
 */
interface ViewerProvider {
    val order: Int
    fun create(mode: ReadingMode, activity: ReaderActivity, @ColorInt seedColor: Int?): Viewer?

    /**
     * True for viewers that cannot render a text chapter and must not be handed one.
     *
     * Checked before [create] so the refusal is explicit and uniform, rather than each provider
     * re-deriving "is this text?" from whatever state happens to be loaded.
     */
    val declinesTextChapters: Boolean get() = false
}

/**
 * True when the chapter on screen is served as text rather than images.
 *
 * Read from the loader, which is the authoritative signal: [TextPageLoader] is what
 * [PageLoaderRegistry] selects for a `TextSource` and for nothing else. Asking the page list instead
 * needs the pages to exist first and reports "not text" while they do not - indistinguishable from a
 * genuinely non-text chapter, which is the exact ambiguity that let an EPUB reach the pager.
 */
internal fun ReaderActivity.isTextChapter(): Boolean {
    val chapter = viewModel.state.value.currentChapter
    // KMK --> The loader's identity is the claim, so the answer is logged with everything needed to
    // see *why* it came out the way it did: which chapter, which loader, and whether pages exist yet.
    // A null loader is not an error - it is the normal state before a chapter is loaded - but it is
    // also the state in which a text chapter would be handed to an image viewer, so it is worth a line.
    val loader = chapter?.pageLoader
    val isText = loader is TextPageLoader
    logcat {
        "isTextChapter: chapter=${chapter?.chapter?.url ?: "null"} " +
            "loader=${loader?.let { it::class.simpleName } ?: "null"} " +
            "isText=$isText pages=${chapter?.pages?.size ?: "not loaded"}"
    }
    return isText
}

object ViewerRegistry {
    private val providers = mutableListOf<ViewerProvider>(WebGpuProvider, LegacyProvider)

    /**
     * The simple name of [value]'s class, or `"?"` when there is none to report.
     *
     * Both spellings of this in Kotlin have an edge: `KClass.simpleName` is nullable, which will not
     * drop straight into `sorted()`, and `Any.javaClass` is deprecated on a `KClass` receiver. Every
     * decision line below needs the name of a *loader*, a *viewer* or a *provider* — three different
     * static types, so there is no single receiver form that covers them. Routing through one helper is
     * what keeps the log statements readable and keeps the compiler out of the argument.
     */
    private fun nameOf(value: Any): String = value::class.simpleName ?: "?"

    fun register(provider: ViewerProvider) {
        providers.removeAll { it::class == provider::class }
        providers.add(provider)
        providers.sortBy { it.order }
    }

    fun create(mode: ReadingMode, activity: ReaderActivity, @ColorInt seedColor: Int?): Viewer {
        val isText = activity.isTextChapter()
        // KMK --> Logged with the state the decision was actually made from. `isText` is the whole
        // fork, and its input - the open chapter's loader - is null early and set later, so a report
        // of "my book opens as pictures" is unanswerable without seeing which of those it was.
        // Every log line here names a class through [nameOf], because both spellings have a problem of
        // their own: `KClass.simpleName` is nullable (so it will not drop straight into `sorted()`),
        // and `Any.javaClass` is deprecated on a `KClass` receiver. The one total form is a helper.
        val registered = providers.map { nameOf(it) }.sorted()
        logcat {
            "ViewerRegistry: mode=$mode isTextChapter=$isText " +
                "loader=${activity.viewModel.state.value.currentChapter?.pageLoader?.let { nameOf(it) } ?: "null"} " +
                "registered=$registered"
        }
        for (provider in providers.sortedBy { it.order }) {
            if (isText && provider.declinesTextChapters) {
                // Logged, not merely skipped in silence: this is the path that produced a blank
                // reader twice, and one line here is what makes the next report diagnosable.
                logcat(DEBUG) {
                    "ViewerRegistry: ${provider::class.simpleName} cannot render text, declining chapter"
                }
                continue
            }
            val viewer = runCatching { provider.create(mode, activity, seedColor) }.getOrNull()
            logcat {
                "ViewerRegistry:   ${nameOf(provider)}(order=${provider.order}) -> ${viewer?.let { nameOf(it) } ?: "declined"}"
            }
            if (viewer != null) {
                logcat { "ViewerRegistry: viewer chosen = ${nameOf(viewer)} for mode=$mode (isText=$isText)" }
                return viewer
            }
        }
        error(
            if (isText) {
                "No ViewerProvider handled reading mode $mode for a text chapter. The novel viewer " +
                    "must always claim one: refusing to fall back to an image viewer is deliberate."
            } else {
                "No ViewerProvider handled reading mode: $mode"
            },
        )
    }
}

private object WebGpuProvider : ViewerProvider {
    override val order: Int = 0
    override val declinesTextChapters: Boolean get() = true

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        // KMK --> Gate the WebGPU renderer behind a total-RAM check: on low-memory devices the
        // native renderer crashes with an uncatchable SIGSEGV (null GPUTexture in createView)
        // when it cannot allocate GPU-visible memory. Fall back to the legacy pager viewers.
        // KMK <--
        val basePreferences = globalAppGraph.basePreferences
        val wantsHighQuality = basePreferences.highQualityRenderer().get() &&
            exh.yakuyomi.DeviceMemory.isWebGpuSupported(activity)
        if (!wantsHighQuality) return null
        val isWebGpuAvailable = try {
            ca.mpreg.webgpuviewer.renderer.WebGpuRenderer.isAvailable
        } catch (_: Throwable) {
            false
        }
        if (!isWebGpuAvailable) return null
        return try {
            when (mode) {
                ReadingMode.LEFT_TO_RIGHT -> WebGpuViewer(activity, isReversed = false, isVertical = false)
                ReadingMode.RIGHT_TO_LEFT -> WebGpuViewer(activity, isReversed = true, isVertical = false)
                ReadingMode.VERTICAL -> WebGpuViewer(activity, isReversed = false, isVertical = true)
                ReadingMode.WEBTOON -> WebGpuViewerContinuous(activity, useGap = false)
                ReadingMode.CONTINUOUS_VERTICAL -> WebGpuViewerContinuous(activity, useGap = true)
                ReadingMode.DEFAULT -> null
            }
        } catch (e: Throwable) {
            android.util.Log.w("ReadingMode", "WebGPU viewer failed, falling back to pager", e)
            null
        }
    }
}

private object LegacyProvider : ViewerProvider {
    override val order: Int = 100
    override val declinesTextChapters: Boolean get() = true

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        return when (mode) {
            ReadingMode.LEFT_TO_RIGHT -> L2RPagerViewer(activity, seedColor = seedColor)
            ReadingMode.RIGHT_TO_LEFT -> R2LPagerViewer(activity, seedColor = seedColor)
            ReadingMode.VERTICAL -> VerticalPagerViewer(activity, seedColor = seedColor)
            ReadingMode.WEBTOON -> WebtoonViewer(activity, seedColor = seedColor)
            ReadingMode.CONTINUOUS_VERTICAL -> WebtoonViewer(activity, isContinuous = false, seedColor = seedColor)
            ReadingMode.DEFAULT -> null
        }
    }
}
