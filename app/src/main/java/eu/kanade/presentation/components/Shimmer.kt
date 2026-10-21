package eu.kanade.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.shimmer

/**
 * The shapes every loading placeholder in the app is built from.
 *
 * They lived privately inside the library's shimmer, so no other list could show a placeholder
 * without re-declaring them - and the ones that did ended up drawing list-shaped boxes inside a grid.
 * A placeholder has to be the layout it stands in for: the content visibly re-flows the instant real
 * data lands, so grey boxes of the wrong shape cost more than the wait they cover.
 */
@Composable
internal fun SkeletonBox(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp? = null,
    corner: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(RoundedCornerShape(corner))
            .shimmer(),
    )
}

/** A text line filling [fraction] of its row, so a stack of them reads as a title block. */
@Composable
internal fun LineShimmer(fraction: Float, modifier: Modifier = Modifier) {
    SkeletonBox(height = 10.dp, modifier = modifier.fillMaxWidth(fraction))
}

@Composable
internal fun CoverShimmer(
    ratio: Float,
    corner: Dp,
    modifier: Modifier = Modifier,
) {
    SkeletonBox(
        corner = corner,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio),
    )
}

/** Grid cell for a cover with a title and a subtitle under it. */
@Composable
internal fun CompactGridItemShimmer(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(2.dp)) {
        CoverShimmer(ratio = 2f / 3f, corner = 4.dp)
        Spacer(Modifier.height(6.dp))
        LineShimmer(0.7f)
        Spacer(Modifier.height(4.dp))
        LineShimmer(0.25f)
    }
}

/** Grid cell for a cover with a three-line title block under it. */
@Composable
internal fun ComfortableGridItemShimmer(panorama: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(2.dp)) {
        CoverShimmer(ratio = if (panorama) 3f / 2f else 2f / 3f, corner = 6.dp)
        Spacer(Modifier.height(8.dp))
        LineShimmer(0.85f)
        Spacer(Modifier.height(6.dp))
        LineShimmer(0.5f)
        Spacer(Modifier.height(6.dp))
        LineShimmer(0.3f)
    }
}
