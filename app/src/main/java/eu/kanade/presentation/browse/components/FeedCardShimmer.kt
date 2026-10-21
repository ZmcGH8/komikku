package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.CoverShimmer
import eu.kanade.presentation.components.LineShimmer
import eu.kanade.presentation.components.SkeletonBox
import tachiyomi.presentation.core.components.material.padding

/**
 * Loading placeholders for the feed screens.
 *
 * These stand in for a [GlobalSearchResultItem] header plus the [GlobalSearchCardRow] beneath it,
 * which is what every feed screen renders once its items arrive. They existed to be the layout they
 * stand in for: a feed's first load is a network round-trip per source, and the centred spinner
 * these replaced left the screen empty for all of it.
 *
 * The card-row cell is deliberately not the shared `ComfortableGridItemShimmer`. That one is
 * `fillMaxWidth` with a 2:3 cover, which is right for a grid cell but wrong here: a feed row is a
 * horizontally scrolling strip of fixed-width cards whose cover starts at 96dp and switches to
 * 205dp under the panorama preference. Stretching those cells to the screen width would re-flow the
 * strip horizontally the moment real results landed - the exact cost a placeholder is meant to
 * avoid.
 *
 * `presentation-core`'s `FeedShimmer` draws list items and has no call site. It is the wrong shape
 * for the same reason and is left alone rather than wired in.
 */
@Composable
internal fun FeedListShimmer(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    feedCount: Int = 4,
    cardsPerFeed: Int = 6,
    panorama: Boolean = false,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        items(feedCount) {
            FeedResultShimmer(cardsPerFeed, panorama)
        }
    }
}

/** One feed's worth of placeholder: the source header, then its horizontal card strip. */
@Composable
private fun FeedResultShimmer(cardCount: Int, panorama: Boolean) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            LineShimmer(0.4f)
            LineShimmer(0.22f)
        }
        FeedCardRowShimmer(cardCount = cardCount, panorama = panorama)
    }
}

/** Placeholder for [GlobalSearchCardRow]: a horizontally scrolling strip of fixed-width cards. */
@Composable
internal fun FeedCardRowShimmer(
    cardCount: Int,
    modifier: Modifier = Modifier,
    panorama: Boolean = false,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        items(cardCount) {
            FeedCardShimmer(panorama = panorama)
        }
    }
}

/**
 * One card in the strip.
 *
 * [panorama] mirrors the wide-cover preference, since a strip that re-flows when that setting is on
 * is the same defect this placeholder exists to prevent.
 */
@Composable
private fun FeedCardShimmer(panorama: Boolean, modifier: Modifier = Modifier) {
    val width = if (panorama) PANORAMA_CARD_WIDTH else COMPACT_CARD_WIDTH
    Column(
        modifier = modifier.width(width.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        CoverShimmer(ratio = 2f / 3f, corner = 4.dp)
        // Three title lines, matching the comfortable grid item this card wraps.
        LineShimmer(1f)
        LineShimmer(0.75f)
        LineShimmer(0.4f)
    }
}

/**
 * Placeholder for a feed-reordering screen's row: [FeedOrderListItem] is an elevated full-width
 * card holding a drag handle, a title and a delete button, which is a different shape from both the
 * card strip and the manga list, so it gets its own rather than a reuse that would reflow.
 */
@Composable
internal fun FeedOrderListShimmer(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    itemCount: Int = 6,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        items(itemCount) {
            FeedOrderRowShimmer()
        }
    }
}

@Composable
private fun FeedOrderRowShimmer(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.small)
            .padding(start = MaterialTheme.padding.small, end = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(
            width = ICON_EDGE.dp,
            height = ICON_EDGE.dp,
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            corner = 4.dp,
        )
        LineShimmer(0.55f)
        SkeletonBox(
            width = ICON_EDGE.dp,
            height = ICON_EDGE.dp,
            modifier = Modifier.padding(start = MaterialTheme.padding.small),
            corner = 4.dp,
        )
    }
}

/** Material's standard icon box, so the placeholder's handle and button match the real row's. */
private const val ICON_EDGE = 24

/**
 * Card widths in dp, mirroring the values [MangaItem] uses for the real feed cards. Duplicated
 * rather than imported because that composable resolves the width inline from the panorama
 * preference and exposes no constant for it.
 */
private const val COMPACT_CARD_WIDTH = 96
private const val PANORAMA_CARD_WIDTH = 205
