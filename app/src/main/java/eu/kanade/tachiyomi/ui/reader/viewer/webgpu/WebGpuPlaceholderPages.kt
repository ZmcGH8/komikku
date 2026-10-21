// Mihon -->
package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureView
import ca.mpreg.webgpuviewer.draw.Draw
import ca.mpreg.webgpuviewer.draw.TextAlign
import ca.mpreg.webgpuviewer.draw.uploadTexture
import ca.mpreg.webgpuviewer.renderer.WebGpuRenderer
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min

/**
 * The pages a [ViewerPage] holds while it has nothing decoded to show: a failed load, an
 * in-flight load, and the chapter-boundary interstitial. All three paint themselves
 * procedurally, so none of them needs a decoded image and none of them can be evicted by the
 * decode pipeline - only by the page cache.
 */
class ErrorPage(
    private val viewer: WebGpuViewer,
    @Volatile var message: String,
    private val spreadPosition: SpreadPosition = SpreadPosition.SINGLE,
) : ImagePage.Render(0, 0) {
    override val width: Int
        get() = viewer.viewportPageWidth(spreadPosition != SpreadPosition.SINGLE)
    override val height: Int
        get() = viewer.pager.state.height

    init {
        minScale = 1f
        maxScale = 1f
        homeScale = 1f
    }

    override val backgroundColor: Int
        get() = try {
            viewer.readerBackgroundColor()
        } catch (_: Exception) {
            Color.BLACK
        }

    override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
        if (viewer.isDestroyed || dst.width < 8 || dst.height < 8) return
        val padding = try {
            with(viewer.pager.state.density) { 24.dp.toPx() }
        } catch (_: Exception) {
            24f
        }
        val size = try {
            scale * with(viewer.pager.state.density) { 16.dp.toPx() }
        } catch (_: Exception) {
            16f * scale
        }

        val cx = dst.width * (0.5f + scale * x)
        val cy = dst.height * (0.5f + scale * y)

        try {
            text(
                dst,
                viewer.activity.baseContext,
                FontFamily.Default,
                message.takeIf { it.isNotBlank() } ?: "Error",
                cx,
                cy,
                size,
                color = try {
                    viewer.readerOnBackgroundColor()
                } catch (_: Exception) {
                    Color.WHITE
                },
                align = TextAlign.Center,
                maxWidth = (dst.width - 2f * padding).coerceAtLeast(0f),
            )
        } catch (_: Exception) {
        }
    }
}

class ProgressPage(
    private val viewer: WebGpuViewer,
    var foregroundColor: Int = try {
        viewer.readerOnBackgroundColor()
    } catch (_: Exception) {
        Color.WHITE
    },
) : ImagePage.Render(0, 0) {
    override val width: Int
        get() = viewer.viewportPageWidth(viewer.isDualPageMode())
    override val height: Int
        get() = viewer.pager.state.height

    @Volatile
    var progress: Float = 0f

    init {
        minScale = 1f
        maxScale = 1f
        homeScale = 1f
    }

    override val backgroundColor: Int
        get() = try {
            viewer.readerBackgroundColor()
        } catch (_: Exception) {
            Color.BLACK
        }

    override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
        if (viewer.isDestroyed || dst.width < 8 || dst.height < 8) return
        // Its own footprint, so the page carries its background wherever a transition puts it.
        fillPage(dst, x, y, scale, backgroundColor)
        val cx = dst.width * (0.5f + scale * x)
        val cy = dst.height * (0.5f + scale * y)
        val full = try {
            width * 0.5f * scale
        } catch (_: Exception) {
            return
        }
        if (full <= 0f || full < 8f) return
        val sizePx = full * 0.55f
        // KMK --> The percentage is always drawn, including while the load is indeterminate
        // (cached or unknown-length, where progressFlow never advances and progress stays 0).
        // Hiding it until bytes arrived left the placeholder as a bare spinner with no reading at
        // all, which is indistinguishable from the stall it was meant to reassure about - "0%"
        // reads as "started", the pineapple alone reads as "hung". Clamped so a progressFlow that
        // overshoots cannot render past 100.
        //
        // It is also drawn independently of the icon, which is the second half of the same fix:
        // the icon is a per-device upload that is legitimately unavailable for the first frame
        // after a surface attach or a device loss, and gating the reading on it left a completely
        // blank page - the one case where nothing at all was on screen to show progress.
        val textPx = (full * 0.09f).coerceAtLeast(12f)
        try {
            text(
                dst,
                viewer.activity.baseContext,
                FontFamily.Default,
                "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                cx,
                cy + sizePx * 0.5f + textPx * 1.1f,
                textPx,
                foregroundColor,
                align = TextAlign.Center,
            )
        } catch (_: Exception) {
        }
        try {
            drawSplashPineapple(cx, cy, sizePx, dst)
        } catch (_: Exception) {
        }
    }

    /**
     * Splash-screen pineapple, uploaded once per GPU device and shared; the spin eases per
     * revolution like the splash exit loop. A noop until that upload lands.
     */
    private fun drawSplashPineapple(cx: Float, cy: Float, sizePx: Float, dst: GPUTexture) {
        val view = loadPineappleView() ?: return
        val t = (System.currentTimeMillis() % 1200L) / 1200f
        val eased = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
        // KMK --> Tint with the reader on-background color, not white: ic_houri is a white
        // monochrome vector, so a white tint is invisible on light reader backgrounds.
        sprite(view, cx, cy, sizePx, dst, eased * 2f * PI.toFloat(), foregroundColor)
        // KMK <--
    }

    // KMK -->
    // The resolved view for the frame, and the shared-cache generation it was resolved at.
    // render() runs for every frame a placeholder is on screen, and re-resolving meant taking the
    // companion's lock and re-reading the GPUDevice each time - a global monitor on the render
    // path, held for as long as a page takes to load. The generation only moves on a device change,
    // a publish or a destroy, so this is a volatile read on the fast path.
    @Volatile
    private var spinView: GPUTextureView? = null

    @Volatile
    private var spinViewGeneration: Long = -1L

    private fun loadPineappleView(): GPUTextureView? {
        if (pineappleGeneration == spinViewGeneration) return spinView
        val view = resolvePineappleView()
        spinView = view
        // Read after resolving, so a generation that moved mid-resolve is not cached as current.
        spinViewGeneration = pineappleGeneration
        return view
    }

    private fun resolvePineappleView(): GPUTextureView? {
        loadPineappleTexture() ?: return null
        return synchronized(ProgressPage) { pineappleView }
    }
    // KMK <--

    private fun loadPineappleTexture(): GPUTexture? {
        // KMK --> The upload binds the texture to the creating GPUDevice, so the shared
        // cache must follow device recreation (device-lost re-init) - a texture from
        // the old device draws nothing.
        val device = try {
            WebGpuRenderer.device
        } catch (_: Exception) {
            // Never initialized, so nothing cached can belong to a live device.
            return null
        }
        // `device` keeps naming the lost device until recovery replaces it, so the
        // identity check below cannot see the loss - it would happily hand back the texture
        // bound to the dead device, which is the exact "draws nothing" case above. `isAvailable`
        // is what goes false at the loss, so it is the gate that sees it.
        if (!WebGpuRenderer.isAvailable) return null
        synchronized(ProgressPage) {
            if (pineappleDevice !== device) {
                try {
                    pineappleTexture?.destroy()
                } catch (_: Exception) {
                }
                pineappleTexture = null
                pineappleView = null
                pineappleDevice = device
                pineappleGeneration++
            }
            pineappleTexture?.let { return it }
            if (uploadInFlight) return null
            uploadInFlight = true
        }
        // KMK <--
        try {
            val texture = uploadPineappleTexture()
            synchronized(ProgressPage) {
                // A device change or destroy() may have landed mid-upload: only
                // publish if this texture still belongs to the current device.
                if (pineappleDevice === device && pineappleTexture == null) {
                    pineappleTexture = texture
                    // One view per texture, not per frame: render() runs for as long as this
                    // placeholder is on screen and the spin is time-based, so this is every frame.
                    pineappleView = try {
                        texture?.createView()
                    } catch (_: Exception) {
                        null
                    }
                    pineappleGeneration++
                } else {
                    try {
                        texture?.destroy()
                    } catch (_: Exception) {
                    }
                }
                return pineappleTexture
            }
        } finally {
            synchronized(ProgressPage) {
                uploadInFlight = false
            }
        }
    }

    private fun uploadPineappleTexture(): GPUTexture? {
        val context = try {
            viewer.activity.baseContext
        } catch (_: Exception) {
            return null
        }
        val drawable = try {
            ContextCompat.getDrawable(context, R.drawable.ic_houri)
        } catch (_: Exception) {
            null
        } ?: return null
        val bitmap = try {
            Bitmap.createBitmap(PINEAPPLE_PX, PINEAPPLE_PX, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return null
        }
        drawable.setBounds(0, 0, PINEAPPLE_PX, PINEAPPLE_PX)
        drawable.draw(Canvas(bitmap))
        val argb = IntArray(PINEAPPLE_PX * PINEAPPLE_PX)
        bitmap.getPixels(argb, 0, PINEAPPLE_PX, 0, 0, PINEAPPLE_PX, PINEAPPLE_PX)
        bitmap.recycle()
        val buffer = ByteBuffer.allocateDirect(argb.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (px in argb) {
            buffer.put((px shr 16 and 0xFF).toByte())
            buffer.put((px shr 8 and 0xFF).toByte())
            buffer.put((px and 0xFF).toByte())
            buffer.put((px ushr 24 and 0xFF).toByte())
        }
        buffer.flip()
        return try {
            Draw.uploadTexture(PINEAPPLE_PX, PINEAPPLE_PX, buffer)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PINEAPPLE_PX = 512

        @Volatile
        private var pineappleTexture: GPUTexture? = null

        // KMK --> Built with the texture and rebuilt with it: a view is bound to its texture's
        // device, so a device swap has to drop this alongside the texture above.
        @Volatile
        private var pineappleView: GPUTextureView? = null

        // KMK --> Device the cached texture was uploaded to; see loadPineappleTexture.
        @Volatile
        private var pineappleDevice: GPUDevice? = null

        // KMK --> Bumped on every change to the shared texture or view - device swap, first
        // publish, teardown. A placeholder caches the view it resolved against the generation it
        // read, so it re-resolves exactly when the view it holds has stopped being the current one.
        @Volatile
        private var pineappleGeneration: Long = 0L

        // KMK --> Guards concurrent first-frame uploads: without it every
        // ProgressPage rendering its first frame uploads its own copy and all
        // but one leak. The per-frame spin itself is driven by the viewer's
        // existing invalidate loop (WebGpuViewer progress poller), never by
        // re-uploading here — render() only reads the shared texture.
        @Volatile
        private var uploadInFlight = false

        /**
         * Destroys the shared spinner texture and clears the cache (viewer
         * teardown). Idempotent; safe to call with no texture cached.
         */
        fun destroyPineappleTexture() {
            synchronized(ProgressPage) {
                try {
                    pineappleTexture?.destroy()
                } catch (_: Exception) {
                }
                pineappleTexture = null
                pineappleView = null
                pineappleDevice = null
                pineappleGeneration++
            }
        }
        // KMK <--
    }
}

class TransitionPage(
    private val viewer: WebGpuViewer,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
) : ImagePage.Render(0, 0) {
    /** Square, and never a spread side - [WebGpuViewer.buildSpreadPage] hands it back whole. */
    override val width: Int
        get() = min(viewer.pager.state.width, viewer.pager.state.height)
    override val height: Int
        get() = width

    init {
        minScale = 1f
        maxScale = 1f
        homeScale = 1f
    }

    override val backgroundColor: Int
        get() = try {
            viewer.readerBackgroundColor()
        } catch (_: Exception) {
            Color.BLACK
        }

    // Per-frame render() must not resolve resources or density: chapter names and the
    // locale change only on chapter swap, and density only on display change.
    private var cachedText: String? = null
    private var cachedPrevId: Long? = null
    private var cachedPrevName: String? = null
    private var cachedNextId: Long? = null
    private var cachedNextName: String? = null
    private var cachedLocale: java.util.Locale? = null
    private var cachedDensity = -1f
    private var cachedPadding = 24f
    private var cachedUnit = 16f

    override fun render(dst: GPUTexture, x: Float, y: Float, scale: Float) {
        if (viewer.isDestroyed || dst.width < 8 || dst.height < 8) return
        // Its own footprint, so the page carries its background wherever a transition puts it.
        fillPage(dst, x, y, scale, backgroundColor)
        val prevCh = prevChapter?.chapter
        val nextCh = nextChapter?.chapter
        val locale = try {
            java.util.Locale.getDefault()
        } catch (_: Exception) {
            null
        }
        var text = cachedText
        if (text == null || prevCh?.id != cachedPrevId || prevCh?.name != cachedPrevName ||
            nextCh?.id != cachedNextId || nextCh?.name != cachedNextName || locale != cachedLocale
        ) {
            val lines: MutableList<String> = mutableListOf()
            try {
                if (prevCh != null) {
                    lines.add(viewer.activity.stringResource(MR.strings.action_previous_chapter) + ": " + prevCh.name)
                }
                if (nextCh != null) {
                    lines.add(viewer.activity.stringResource(MR.strings.action_next_chapter) + ": " + nextCh.name)
                }
            } catch (_: Exception) {
            }
            text = lines.joinToString("\n")
            cachedText = text
            cachedPrevId = prevCh?.id
            cachedPrevName = prevCh?.name
            cachedNextId = nextCh?.id
            cachedNextName = nextCh?.name
            cachedLocale = locale
        }
        if (text.isBlank()) return

        val density = try {
            viewer.pager.state.density.density
        } catch (_: Exception) {
            -1f
        }
        if (density != cachedDensity) {
            cachedDensity = density
            cachedPadding = if (density > 0f) 24f * density else 24f
            cachedUnit = if (density > 0f) 16f * density else 16f
        }
        val padding = cachedPadding
        val size = scale * cachedUnit

        val cx = dst.width * (0.5f + scale * x)
        val cy = dst.height * (0.5f + scale * y)

        try {
            text(
                dst,
                viewer.activity.baseContext,
                FontFamily.Default,
                text,
                cx,
                cy,
                size,
                try {
                    viewer.readerOnBackgroundColor()
                } catch (_: Exception) {
                    Color.WHITE
                },
                align = TextAlign.Center,
                maxWidth = (dst.width - 2f * padding).coerceAtLeast(0f),
            )
        } catch (_: Exception) {
        }
    }
}
// Mihon <--
