package eu.kanade.presentation.manga.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Container/content pair for the "Start reading" extended FAB on the manga details screen.
 *
 * The FAB is tinted with the manga's own cover colour so it reads as part of the
 * cover-seeded theme. Two rules keep that from producing an unreadable button:
 *
 * - the *label* colour is decided by WCAG relative luminance rather than by Material's
 *   `contentColorFor`, which only recognises the handful of `primaryContainer` roles and
 *   would fall back to `onSurface` on an arbitrary vibrant cover colour;
 * - a saturated cover colour is toned down first, because a fully saturated FAB next to
 *   the seeded body text reads as a foreign element rather than as part of the theme.
 *
 * Animated because the seed is only known once the cover has been decoded and sampled,
 * which on a cold start is a frame or two after the screen appears.
 */
@Immutable
data class ReadButtonColors(
    val container: Color,
    val content: Color,
)

@Composable
fun rememberReadButtonColors(seed: Color?): ReadButtonColors {
    val scheme = MaterialTheme.colorScheme
    val target = seed?.let { resolveReadButtonColors(it, scheme) }
        ?: ReadButtonColors(scheme.primaryContainer, scheme.onPrimaryContainer)

    val container by animateColorAsState(targetValue = target.container, label = "readButtonContainer")
    val content by animateColorAsState(targetValue = target.content, label = "readButtonContent")
    return ReadButtonColors(container, content)
}

/**
 * Luminance below this is treated as "dark surface" for the label colour. 0.5 is the
 * conventional sRGB cut-off and matches what the reader's own contrast helpers use, so
 * the button and the reader agree on which covers are pale.
 */
private const val LIGHT_SURFACE_LUMINANCE = 0.5f

/**
 * Caps how far the FAB may drift from the seeded theme's own primary. A fully saturated
 * cover colour is a deliberate accent, not a background: keeping the container close to
 * `primary` preserves the seeded hue while restoring Material's contrast guarantees.
 */
private const val MAX_SEED_MIX = 0.65f

internal fun resolveReadButtonColors(seed: Color, scheme: ColorScheme): ReadButtonColors {
    val mixed = Color(
        red = lerp(scheme.primary.red, seed.red, MAX_SEED_MIX),
        green = lerp(scheme.primary.green, seed.green, MAX_SEED_MIX),
        blue = lerp(scheme.primary.blue, seed.blue, MAX_SEED_MIX),
        alpha = 1f,
    )
    val label = if (mixed.luminance() > LIGHT_SURFACE_LUMINANCE) Color.Black else Color.White
    return ReadButtonColors(container = mixed, content = label)
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction
