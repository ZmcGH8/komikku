package eu.kanade.presentation.manga.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import kotlin.math.max
import kotlin.math.min

// KMK -->
private const val MAX_BITMAP_DIM = 2048

/** Height of the crop viewport. Fixed, so the frame geometry below is a constant offset. */
private val CROP_VIEWPORT_HEIGHT = 420.dp

/** How far one nudge moves the image, as a fraction of the crop frame's own size. */
private const val NUDGE_FRACTION = 0.04f

/** One zoom button press. Matches the ratio the pinch gesture feels natural at. */
private const val ZOOM_STEP = 1.25f

/** Hold a nudge button this long before it starts repeating, then repeat every interval below. */
private const val HOLD_REPEAT_START_MS = 320L
private const val HOLD_REPEAT_INTERVAL_MS = 90L

/** Nudge and zoom targets. Sized past the 48dp minimum touch target. */
private val CONTROL_BUTTON_SIZE = 56.dp

/** Aspect options for the crop frame, expressed as width / height. */
private val COVER_CROP_RATIOS = listOf(
    2f / 3f,
    1f,
    3f / 2f,
)

/**
 * Lets the user pan/zoom an image inside a fixed crop frame before it is used
 * as a manga cover. The cropped result is written to cache storage and handed
 * back as a [Uri] via [onCropped]; [onUseOriginal] skips cropping entirely.
 */
@Composable
fun CoverCropDialog(
    sourceUri: Uri,
    onDismissRequest: () -> Unit,
    onCropped: (Uri) -> Unit,
    onUseOriginal: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var bitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    // KMK -->
    var decodeFailed by remember(sourceUri) { mutableStateOf(false) }
    // KMK <--
    var rotationSteps by remember(sourceUri) { mutableIntStateOf(0) }
    var isRotating by remember(sourceUri) { mutableStateOf(false) }
    var frameRatio by remember(sourceUri) { mutableFloatStateOf(COVER_CROP_RATIOS.first()) }
    var scale by remember(sourceUri) { mutableFloatStateOf(1f) }
    var offset by remember(sourceUri) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(sourceUri) {
        bitmap = withContext(Dispatchers.IO) { decodeDownscaledBitmap(context, sourceUri) }
        // KMK -->
        decodeFailed = bitmap == null
        // KMK <--
    }

    LaunchedEffect(rotationSteps) {
        val current = bitmap ?: return@LaunchedEffect
        if (rotationSteps % 4 == 0 || isRotating) return@LaunchedEffect
        isRotating = true
        bitmap = withContext(Dispatchers.IO) {
            Bitmap.createBitmap(
                current,
                0,
                0,
                current.width,
                current.height,
                Matrix().apply { postRotate(90f) },
                true,
            )
        }
        isRotating = false
    }

    // A new source or frame invalidates the previous framing
    LaunchedEffect(bitmap, frameRatio) {
        scale = 1f
        offset = Offset.Zero
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        // KMK --> The constraints sit above the whole sheet content, not just the crop viewport,
        // so the frame geometry computed here is also in scope for the nudge controls below. It
        // used to be scoped to the viewport and handed back out through a remembered lambda,
        // which meant a fresh lambda instance was written to state on every recomposition.
        // KMK <--
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val containerWpx = with(density) { maxWidth.toPx() }
            val containerHpx = with(density) { CROP_VIEWPORT_HEIGHT.toPx() }
            val frameWpx = min(containerWpx, containerHpx * frameRatio)
            val frameHpx = frameWpx / frameRatio
            val frameLeftPx = (containerWpx - frameWpx) / 2f
            val frameTopPx = (containerHpx - frameHpx) / 2f
            val bmp = bitmap
            val baseScale = bmp
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { max(frameWpx / it.width, frameHpx / it.height) }
                ?: 1f
            val totalScale = baseScale * scale

            // How far the image may be offset before an edge of it would enter the frame.
            fun panLimitX(atScale: Float) = max(0f, ((bmp?.width ?: 0) * baseScale * atScale - frameWpx) / 2f)
            fun panLimitY(atScale: Float) = max(0f, ((bmp?.height ?: 0) * baseScale * atScale - frameHpx) / 2f)

            fun export() {
                val source = bmp ?: return
                val srcRect = computeCropRect(
                    bmp = source,
                    baseScale = baseScale,
                    totalScale = totalScale,
                    offset = offset,
                    containerWpx = containerWpx,
                    containerHpx = containerHpx,
                    frameWpx = frameWpx,
                    frameHpx = frameHpx,
                ) ?: return
                scope.launch {
                    val outUri = withContext(Dispatchers.IO) { writeCroppedBitmap(context, source, srcRect) }
                    if (outUri != null) onCropped(outUri)
                }
            }

            // KMK --> Discrete nudges. Dragging is the wrong tool for fine framing here: the
            // gesture surface is the entire viewport, so a tap meant to shift the image a few
            // pixels lands wherever the digitiser drifted, and a drift smaller than a fingertip
            // has no correction. A button is a large fixed target that steps by a known amount,
            // and holding it repeats, so coarse and precise moves share one control.
            // KMK <--
            fun nudge(dxFraction: Float, dyFraction: Float) {
                if (bmp == null) return
                offset = Offset(
                    (offset.x + dxFraction * frameWpx).coerceIn(-panLimitX(scale), panLimitX(scale)),
                    (offset.y + dyFraction * frameHpx).coerceIn(-panLimitY(scale), panLimitY(scale)),
                )
            }

            fun zoomBy(factor: Float) {
                if (bmp == null) return
                val next = (scale * factor).coerceIn(1f, 8f)
                if (next == scale) return
                scale = next
                // Zooming out can leave the image smaller than the frame, which pulls the pan
                // limits in; without re-clamping the offset the frame would show empty space.
                offset = Offset(
                    offset.x.coerceIn(-panLimitX(next), panLimitX(next)),
                    offset.y.coerceIn(-panLimitY(next), panLimitY(next)),
                )
            }

            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CROP_VIEWPORT_HEIGHT)
                        // The scaled image overflows this box, and the scrim only covers the box.
                        .clipToBounds(),
                ) {
                    bmp?.let { source ->
                        val bitmapWidth = with(density) { source.width.toDp() }
                        val bitmapHeight = with(density) { source.height.toDp() }
                        Image(
                            bitmap = source.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.Center)
                                // Laid out 1:1 so `baseScale` (derived from the bitmap's own size) and
                                // the exported rect agree with what is on screen. `requiredSize`, since
                                // a larger bitmap would otherwise be clamped to the viewport.
                                .requiredSize(bitmapWidth, bitmapHeight)
                                .graphicsLayer {
                                    // Read the transform states here so gestures apply immediately
                                    scaleX = baseScale * scale
                                    scaleY = baseScale * scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )

                        // Gesture surface spans the whole viewport: hit testing uses layout bounds,
                        // not the graphics layer transform, so the image itself is the wrong target.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(source, frameRatio, containerWpx, containerHpx) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val next = (scale * zoom).coerceIn(1f, 8f)
                                        // Bounds follow the zoom being applied this frame, not the
                                        // one it started from.
                                        val maxX = max(0f, (source.width * baseScale * next - frameWpx) / 2f)
                                        val maxY = max(0f, (source.height * baseScale * next - frameHpx) / 2f)
                                        scale = next
                                        offset = Offset(
                                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                                            (offset.y + pan.y).coerceIn(-maxY, maxY),
                                        )
                                    }
                                },
                        )
                    }

                    // Dim everything outside of the crop frame
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawBehind {
                                drawRect(Color.Black.copy(alpha = 0.55f))
                                drawRect(
                                    Color.Black,
                                    topLeft = Offset(frameLeftPx, frameTopPx),
                                    size = Size(frameWpx, frameHpx),
                                    blendMode = BlendMode.Clear,
                                )
                            },
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .border(2.dp, Color.White)
                            .size(with(density) { frameWpx.toDp() }, with(density) { frameHpx.toDp() }),
                    )

                    // KMK -->
                    when {
                        bmp != null -> Unit
                        decodeFailed -> Text(
                            text = stringResource(MR.strings.decode_image_error),
                            modifier = Modifier.align(Alignment.Center),
                        )

                        else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    // KMK <--
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    COVER_CROP_RATIOS.forEachIndexed { index, ratio ->
                        FilterChip(
                            selected = frameRatio == ratio,
                            onClick = { frameRatio = ratio },
                            label = {
                                Text(
                                    text = when (index) {
                                        0 -> stringResource(KMR.strings.crop_ratio_portrait)
                                        1 -> stringResource(KMR.strings.crop_ratio_square)
                                        else -> stringResource(KMR.strings.crop_ratio_wide)
                                    },
                                )
                            },
                        )
                    }
                }

                // KMK -->
                CropAdjustControls(
                    enabled = bmp != null,
                    onNudge = ::nudge,
                    onZoom = ::zoomBy,
                )
                // KMK <--

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // KMK -->
                    TextButton(onClick = onUseOriginal, enabled = bitmap != null || decodeFailed) {
                        // KMK <--
                        Text(text = stringResource(KMR.strings.action_crop_use_original))
                    }
                    Box(modifier = Modifier.weight(1f))
                    IconButton(onClick = { rotationSteps++ }, enabled = bitmap != null && !isRotating) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.RotateRight, contentDescription = null)
                    }
                    TextButton(
                        onClick = { export() },
                        enabled = bitmap != null,
                    ) {
                        Text(text = stringResource(MR.strings.action_save))
                    }
                }
            }
        }
    }
}

/**
 * D-pad plus a zoom pair for the crop viewport. Each control steps the image by a fixed amount
 * and repeats while held, so the same buttons cover a one-pixel correction and a full reframe.
 */
@Composable
private fun CropAdjustControls(
    enabled: Boolean,
    onNudge: (dxFraction: Float, dyFraction: Float) -> Unit,
    onZoom: (factor: Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Signed for the viewport, not the image: nudging "up" moves the framing up the picture,
            // which draws the image further down and so takes a larger positive offset. Nudging the
            // image itself the other way leaves every arrow showing the part of the cover above the
            // one asked for.
            RepeatIconButton(
                icon = Icons.Outlined.ArrowUpward,
                contentDescription = stringResource(KMR.strings.crop_move_up),
                enabled = enabled,
                onStep = { onNudge(0f, NUDGE_FRACTION) },
            )
            Row {
                RepeatIconButton(
                    icon = Icons.Outlined.ArrowBack,
                    contentDescription = stringResource(KMR.strings.crop_move_left),
                    enabled = enabled,
                    onStep = { onNudge(NUDGE_FRACTION, 0f) },
                )
                Box(modifier = Modifier.size(CONTROL_BUTTON_SIZE))
                RepeatIconButton(
                    icon = Icons.Outlined.ArrowForward,
                    contentDescription = stringResource(KMR.strings.crop_move_right),
                    enabled = enabled,
                    onStep = { onNudge(-NUDGE_FRACTION, 0f) },
                )
            }
            RepeatIconButton(
                icon = Icons.Outlined.ArrowDownward,
                contentDescription = stringResource(KMR.strings.crop_move_down),
                enabled = enabled,
                onStep = { onNudge(0f, -NUDGE_FRACTION) },
            )
        }
        Box(modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RepeatIconButton(
                icon = Icons.Outlined.ZoomIn,
                contentDescription = stringResource(KMR.strings.crop_zoom_in),
                enabled = enabled,
                onStep = { onZoom(ZOOM_STEP) },
            )
            RepeatIconButton(
                icon = Icons.Outlined.ZoomOut,
                contentDescription = stringResource(KMR.strings.crop_zoom_out),
                enabled = enabled,
                onStep = { onZoom(1f / ZOOM_STEP) },
            )
        }
    }
}

/**
 * Steps [onStep] once per tap, and again on a timer for as long as the touch is held.
 *
 * The clickable owns the tap and the pointer input owns only the hold, with [repeatFired] telling
 * the release that the hold already produced its step. Splitting them that way means a tap still
 * works even if the press handler never runs, which is the failure that matters: a nudge button
 * that does nothing on tap is worse than one that cannot repeat.
 */
@Composable
private fun RepeatIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val currentOnStep by rememberUpdatedState(onStep)
    var held by remember { mutableStateOf(false) }
    var repeatFired by remember { mutableStateOf(false) }

    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        delay(HOLD_REPEAT_START_MS)
        while (held) {
            repeatFired = true
            currentOnStep()
            delay(HOLD_REPEAT_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .size(CONTROL_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = { if (repeatFired) repeatFired = false else currentOnStep() },
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        repeatFired = false
                        held = true
                        // tryAwaitRelease throws on cancellation, which would otherwise leave
                        // `held` set and the repeat loop running after the finger lifts.
                        try {
                            tryAwaitRelease()
                        } finally {
                            held = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f),
        )
    }
}

private class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

private fun computeCropRect(
    bmp: Bitmap,
    baseScale: Float,
    totalScale: Float,
    offset: Offset,
    containerWpx: Float,
    containerHpx: Float,
    frameWpx: Float,
    frameHpx: Float,
): CropRect? {
    if (totalScale <= 0f) return null
    val dispW = bmp.width * totalScale
    val dispH = bmp.height * totalScale
    val imgLeft = containerWpx / 2f + offset.x - dispW / 2f
    val imgTop = containerHpx / 2f + offset.y - dispH / 2f
    val frameLeft = (containerWpx - frameWpx) / 2f
    val frameTop = (containerHpx - frameHpx) / 2f

    val left = ((frameLeft - imgLeft) / totalScale).coerceIn(0f, bmp.width.toFloat())
    val top = ((frameTop - imgTop) / totalScale).coerceIn(0f, bmp.height.toFloat())
    val right = ((frameLeft + frameWpx - imgLeft) / totalScale).coerceIn(0f, bmp.width.toFloat())
    val bottom = ((frameTop + frameHpx - imgTop) / totalScale).coerceIn(0f, bmp.height.toFloat())

    val width = (right - left).toInt()
    val height = (bottom - top).toInt()
    if (width < 8 || height < 8) return null
    return CropRect(left.toInt(), top.toInt(), width, height)
}

private suspend fun writeCroppedBitmap(context: Context, source: Bitmap, rect: CropRect): Uri? {
    return runCatching {
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
        val dir = File(context.cacheDir, "cover_crop").apply { mkdirs() }
        File(dir, "crop_${System.currentTimeMillis()}.jpg").also { file ->
            file.outputStream().use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            cropped.recycle()
        }.let(Uri::fromFile)
    }.getOrNull()
}

private suspend fun decodeDownscaledBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    // KMK -->
    // Routed through Coil so formats only the app's decoders support (AVIF, JXL,
    // HEIF, JP2) can be cropped; BitmapFactory fails on them and left the dialog
    // loading forever. Software bitmaps are required for cropping/export.
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(MAX_BITMAP_DIM, MAX_BITMAP_DIM)
        .allowHardware(false)
        .build()
    val drawable = context.imageLoader.execute(request).image?.asDrawable(context.resources)
    (drawable as? BitmapDrawable)?.bitmap
    // KMK <--
}.getOrNull()
// KMK <--
