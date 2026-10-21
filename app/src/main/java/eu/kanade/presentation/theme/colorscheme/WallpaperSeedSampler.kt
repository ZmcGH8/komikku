package eu.kanade.presentation.theme.colorscheme

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.palette.graphics.Palette
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.ui.model.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.globalAppGraph
import mihon.core.concurrency.AppDispatchersHolder
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds a seed colour from the wallpaper on releases that expose no system palette.
 *
 * `WallpaperManager.getWallpaperColors` is the cheap route, but it hands back whatever the system
 * extracted: null for live and third-party wallpapers until something has extracted them, and a
 * desaturated primary for wallpapers dominated by photographic or dark content. Sampling the
 * drawable ourselves always yields something, and quantising it finds the colour a person would
 * have picked by eye.
 *
 * Decoding and quantising a wallpaper is far too slow for composition, so it runs once on the IO
 * dispatcher and the result is published as Compose state. The theme reads that state and re-keys
 * its `remember` on it, so the app starts on the fallback and settles onto the wallpaper's own
 * colours a frame later instead of blocking the first frame or never updating at all.
 */
@Inject
@SingleIn(AppScope::class)
class WallpaperSeedSampler(private val context: Context) {

    private val _seed = mutableStateOf<Color?>(null)
    val seed: State<Color?> = _seed

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchersHolder.get().io)

    private val lock = Any()

    /**
     * Bumped whenever the wallpaper changes, so a sample that started against the previous one
     * cannot publish a colour the user has already replaced.
     */
    private var generation = 0L

    /** The wallpaper generation the published seed was sampled for. */
    private var sampledGeneration = -1L

    private var sampling = false

    private val receiverRegistered = AtomicBoolean()

    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val resample: Boolean
            synchronized(lock) {
                generation++
                // Left to the in-flight sample to notice the generation moved, which is the only
                // path that can guarantee the published colour matches the wallpaper on screen.
                resample = !sampling
            }
            // Cleared rather than kept until the new colour lands: the theme already keys on this
            // value, so emptying it re-resolves through the cheap fallback and the sampled colour
            // arrives as a fresh update.
            _seed.value = null
            if (resample) ensureSampled()
        }
    }

    /**
     * Samples the wallpaper unless its generation has already been sampled or is being sampled.
     *
     * Cheap to call repeatedly: both guards sit in a short synchronized block, so once a generation
     * is sampled this returns without touching the dispatcher.
     */
    fun ensureSampled() {
        // Android 12+ has a real system palette, so the wallpaper is not the source of truth there.
        // Returning before registering matters: the receiver would otherwise be woken by every
        // wallpaper change, call straight back into here, and do nothing - forever.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        registerReceiverOnce()

        val mine = synchronized(lock) {
            if (sampledGeneration == generation || sampling) return
            sampling = true
            generation
        }

        scope.launch {
            val sampled = runCatching { sample() }.getOrNull()
            val stale = synchronized(lock) {
                sampling = false
                // Marked current under the same lock ensureSampled reads it with. Written outside it,
                // the write can be missed by that reader and the next call re-samples a generation
                // that is already done.
                if (sampled != null && generation == mine) sampledGeneration = mine
                generation != mine
            }
            when {
                // The wallpaper changed while this ran, so the colour is for one that has already
                // been replaced. Dropped; its generation is unsampled now and picked up here.
                stale -> ensureSampled()
                sampled != null -> _seed.value = sampled
                else -> logcat(LogPriority.DEBUG) { "No seed could be sampled from the wallpaper" }
            }
        }
    }

    private fun registerReceiverOnce() {
        // Registered lazily rather than from init: this class is only reachable from the Monet theme
        // on releases below Android 12, and registering eagerly would hold a receiver for the life of
        // the process over a wallpaper that may never be sampled.
        if (!receiverRegistered.compareAndSet(false, true)) return
        runCatching {
            val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(wallpaperReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(wallpaperReceiver, filter)
            }
        }.onFailure { error ->
            // Without the receiver the seed is still correct for this process; it just will not
            // follow a wallpaper change until the app is next opened.
            receiverRegistered.set(false)
            logcat(LogPriority.WARN, error) { "Could not observe wallpaper changes" }
        }
    }

    private fun sample(): Color? {
        val bitmap = decodeWallpaper() ?: return null
        val palette = runCatching {
            Palette.from(bitmap)
                .clearFilters()
                .maximumColorCount(32)
                .generate()
        }.getOrNull()
        // Deliberately not recycled. toBitmap can hand back a BitmapDrawable's own bitmap rather
        // than a copy, and the decoded sample is a quarter of a megabyte - not worth risking a
        // wallpaper the system still owns.
        palette ?: return null

        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.lightMutedSwatch
            ?: palette.darkMutedSwatch
            ?: palette.dominantSwatch
            ?: return null

        return Color(swatch.rgb)
    }

    private fun decodeWallpaper(): Bitmap? {
        // getDrawable is the only public route - getBitmap is hidden from the SDK - and it answers
        // for live and composed wallpapers too, which getWallpaperColors does not. The drawable is
        // rendered straight to the sample size below, so no full-resolution copy is ever held.
        val drawable = runCatching {
            WallpaperManager.getInstance(context).getDrawable(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return null
        return drawable.toSampledBitmap()
    }
}

/**
 * Renders the drawable straight into a bitmap small enough for the quantiser.
 *
 * Palette runs a full-image histogram, so a full-resolution bitmap buys nothing and costs a lot of
 * allocation on low-end devices - a 1440x3120 ARGB buffer is ~18MB before the encoder has looked at
 * a pixel. Rendering at the target size never materialises it, rather than materialising it and then
 * scaling it down.
 */
private fun Drawable.toSampledBitmap(): Bitmap? {
    // Non-positive intrinsic size is the one case the scaling below cannot express, and it is
    // answered here rather than rounded into a 1x1. Everything else toBitmapOrNull handles itself.
    val width = intrinsicWidth
    val height = intrinsicHeight
    if (width <= 0 || height <= 0) return null

    val longest = maxOf(width, height)
    val scale = if (longest > MAX_SAMPLE_EDGE) MAX_SAMPLE_EDGE.toFloat() / longest else 1f
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)

    return toBitmapOrNull(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
}

private const val MAX_SAMPLE_EDGE = 256

/**
 * Starts sampling if needed and returns the seed as Compose state, so the caller can key a
 * `remember` on it and rebuild the scheme once the sample lands.
 */
@Composable
internal fun rememberWallpaperSeed(appTheme: AppTheme): Color? {
    // Only Monet reads a wallpaper seed, and Android 12+ has a real system palette, so neither needs
    // the sampler. Returning before the accessor is resolved keeps the graph from creating it - and
    // its receiver - for a wallpaper nothing will ever sample.
    if (appTheme != AppTheme.MONET || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
    val sampler = globalAppGraph.wallpaperSeedSampler
    LaunchedEffect(sampler) { sampler.ensureSampled() }
    return sampler.seed.value
}
