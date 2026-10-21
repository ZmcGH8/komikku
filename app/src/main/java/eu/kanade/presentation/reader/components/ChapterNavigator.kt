package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt
import androidx.compose.material3.Slider as ProgressSlider

@Composable
fun ChapterNavigator(
    isRtl: Boolean,
    isVerticalSlider: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    // Mihon -->
    onPageIndexChangeFinished: () -> Unit = {},
    // Mihon <--
    // KMK -->
    /**
     * Chapter-progress mode, for a book: the reader's place in the chapter on screen, 0..1.
     *
     * Non-null renders a chapter-progress slider instead of the page slider. A book's chapters have
     * no pages to count, but the one being read has a beginning and an end, and this is where the
     * reader is between them; dragging it moves through that chapter. Chapter *jumps* belong to
     * the contents list, which is what the chapter selector renders for a book.
     */
    chapterFraction: Float? = null,
    onChapterFractionChange: (Float) -> Unit = {},
    onChapterFractionChangeFinished: () -> Unit = {},
    // KMK <--
    // Mihon -->
    /**
     * Constrains the vertical layout's height when the caller sets it — the *Vertical navigator
     * height* preference, as a fraction of the space between the top and bottom bars.
     *
     * This was exactly where the preference silently died: the slider in General settings wrote it,
     * and no layout in this fork ever read it, in any viewer. Mihon's navigator takes the same
     * parameter for the same reason.
     */
    modifier: Modifier = Modifier,
    // Mihon <--
) {
    // SY -->
    if (isVerticalSlider) {
        ChapterNavigatorVert(
            isRtl = isRtl,
            onNextChapter = onNextChapter,
            enabledNext = enabledNext,
            onPreviousChapter = onPreviousChapter,
            enabledPrevious = enabledPrevious,
            currentPage = currentPage,
            currentPageText = currentPageText,
            totalPages = totalPages,
            onPageIndexChange = onPageIndexChange,
            // Mihon -->
            onPageIndexChangeFinished = onPageIndexChangeFinished,
            // Mihon <--
            // KMK -->
            chapterFraction = chapterFraction,
            onChapterFractionChange = onChapterFractionChange,
            onChapterFractionChangeFinished = onChapterFractionChangeFinished,
            // KMK <--
            // Mihon -->
            modifier = modifier,
            // Mihon <--
        )
        return
    }
    // SY <--
    val isTabletUi = isTabletUi()
    val horizontalPadding = if (isTabletUi) 24.dp else 8.dp
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val haptic = LocalHapticFeedback.current

    // Match with toolbar background color set in ReaderActivity
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    val buttonColor = IconButtonDefaults.filledIconButtonColors(
        containerColor = backgroundColor,
        disabledContainerColor = backgroundColor,
        // KMK -->
        contentColor = MaterialTheme.colorScheme.primary,
        // KMK <--
    )
    val textColor = MaterialTheme.colorScheme.onSurface

    // We explicitly handle direction based on the reader viewer rather than the system direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            // Mihon --> the caller's modifier leads the chain, so the vertical navigator's height
            // fraction reaches the layout that honours it; horizontal callers pass the default.
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            // Mihon <--
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                enabled = if (isRtl) enabledNext else enabledPrevious,
                onClick = if (isRtl) onNextChapter else onPreviousChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(
                        if (isRtl) MR.strings.action_next_chapter else MR.strings.action_previous_chapter,
                    ),
                )
            }

            // KMK --> Chapter progress: a book's position is a fraction of the chapter on screen,
            // not a page number. The percentage stands where the page counter does, and the
            // chapter's end stands where the page total does; the slider between them is the
            // same control the manga bar shows for pages.
            if (chapterFraction != null) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(backgroundColor)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = chapterProgressText(chapterFraction), color = textColor)
                        ProgressSlider(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            value = chapterFraction,
                            valueRange = 0f..1f,
                            onValueChange = onChapterFractionChange,
                            onValueChangeFinished = onChapterFractionChangeFinished,
                        )
                        Text(text = "100%", color = textColor)
                    }
                }
            } else if (totalPages > 1) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(backgroundColor)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(contentAlignment = Alignment.CenterEnd) {
                            Text(
                                // SY -->
                                text = currentPageText,
                                // SY <--
                                // KMK -->
                                color = textColor,
                                // KMK <--
                            )
                            // Taking up full length so the slider doesn't shift when 'currentPage' length changes
                            Text(text = totalPages.toString(), color = Color.Transparent)
                        }

                        val interactionSource = remember { MutableInteractionSource() }
                        val sliderDragged by interactionSource.collectIsDraggedAsState()
                        LaunchedEffect(currentPage) {
                            if (sliderDragged) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                        Slider(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            value = currentPage,
                            valueRange = 1..totalPages,
                            onValueChange = f@{
                                if (it == currentPage) return@f
                                onPageIndexChange(it - 1)
                            },
                            // Mihon -->
                            onValueChangeFinished = onPageIndexChangeFinished,
                            // Mihon <--
                            interactionSource = interactionSource,
                        )

                        Text(
                            text = totalPages.toString(),
                            // KMK -->
                            color = textColor,
                            // KMK <--
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            FilledIconButton(
                enabled = if (isRtl) enabledPrevious else enabledNext,
                onClick = if (isRtl) onPreviousChapter else onNextChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(
                        if (isRtl) MR.strings.action_previous_chapter else MR.strings.action_next_chapter,
                    ),
                )
            }
        }
    }
}

@Composable
fun ChapterNavigatorVert(
    isRtl: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    // Mihon -->
    onPageIndexChangeFinished: () -> Unit = {},
    // Mihon <--
    // KMK -->
    /** Chapter-progress mode, for a book: see the horizontal variant's doc. */
    chapterFraction: Float? = null,
    onChapterFractionChange: (Float) -> Unit = {},
    onChapterFractionChangeFinished: () -> Unit = {},
    // KMK <--
    // Mihon -->
    /** The caller's height constraint for the vertical layout; see the horizontal variant's doc. */
    modifier: Modifier = Modifier,
    // Mihon <--
) {
    val isTabletUi = isTabletUi()
    val verticalPadding = if (isTabletUi) 24.dp else 8.dp

    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = verticalPadding, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Match with toolbar background color set in ReaderActivity
        val backgroundColor = MaterialTheme.colorScheme
            .surfaceColorAtElevation(3.dp)
            .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
        val buttonColor = IconButtonDefaults.filledIconButtonColors(
            containerColor = backgroundColor,
            disabledContainerColor = backgroundColor,
            // KMK -->
            contentColor = MaterialTheme.colorScheme.primary,
            // KMK <--
        )
        val textColor = MaterialTheme.colorScheme.onSurface

        FilledIconButton(
            enabled = enabledPrevious,
            onClick = onPreviousChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(MR.strings.action_previous_chapter),
                modifier = Modifier.rotate(90f),
            )
        }

        // KMK --> Chapter progress, the vertical bar's shape of the same mode the horizontal bar
        // renders: percentage at the top, the chapter's end at the bottom, and the rotated slider
        // between them.
        if (chapterFraction != null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(backgroundColor)
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = chapterProgressText(chapterFraction), color = textColor)
                // KMK --> Always LTR here, unlike the page slider below. `isRtl` describes how a
                // *comic* advances, and mirroring is right for that: page 1 of an RTL manga is on the
                // right, so the bar counts the other way. A document is not a comic. "How far through
                // this chapter am I" is measured from its first line downward whatever direction its
                // text runs, and a book's own stylesheet already lays RTL text out correctly. Mirroring
                // this bar put 100% at the top and counted down, against labels saying otherwise.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ProgressSlider(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .graphicsLayer {
                                rotationZ = 90f
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(
                                    Constraints(
                                        minWidth = constraints.minHeight,
                                        maxWidth = constraints.maxHeight,
                                        minHeight = constraints.minWidth,
                                        maxHeight = constraints.maxWidth,
                                    ),
                                )
                                layout(placeable.height, placeable.width) {
                                    placeable.place(0, -placeable.height)
                                }
                            }
                            .weight(1f),
                        value = chapterFraction,
                        valueRange = 0f..1f,
                        onValueChange = onChapterFractionChange,
                        onValueChangeFinished = onChapterFractionChangeFinished,
                    )
                }
                Text(text = "100%", color = textColor)
            }
        } else if (totalPages > 1) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(backgroundColor)
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    // SY -->
                    text = currentPageText,
                    // SY <--
                    // KMK -->
                    color = textColor,
                    // KMK <--
                )

                val interactionSource = remember { MutableInteractionSource() }
                val sliderDragged by interactionSource.collectIsDraggedAsState()
                LaunchedEffect(currentPage) {
                    if (sliderDragged) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                // KMK -->
                // Mirroring via RTL before the 90° rotation is what inverts vertical progression for RTL.
                CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Slider(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .graphicsLayer {
                                rotationZ = 90f
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(
                                    Constraints(
                                        minWidth = constraints.minHeight,
                                        maxWidth = constraints.maxHeight,
                                        minHeight = constraints.minWidth,
                                        maxHeight = constraints.maxWidth,
                                    ),
                                )
                                layout(placeable.height, placeable.width) {
                                    placeable.place(0, -placeable.height)
                                }
                            }
                            .weight(1f),
                        value = currentPage,
                        valueRange = 1..totalPages,
                        onValueChange = f@{
                            if (it == currentPage) return@f
                            onPageIndexChange(it - 1)
                        },
                        // Mihon -->
                        onValueChangeFinished = onPageIndexChangeFinished,
                        // Mihon <--
                        interactionSource = interactionSource,
                    )
                }
                // KMK <--

                Text(
                    text = totalPages.toString(),
                    // KMK -->
                    color = textColor,
                    // KMK <--
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        FilledIconButton(
            enabled = enabledNext,
            onClick = onNextChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = stringResource(MR.strings.action_next_chapter),
                modifier = Modifier.rotate(90f),
            )
        }
    }
}

// KMK -->
/** A chapter-progress fraction as the whole percent the bar shows. */
private fun chapterProgressText(fraction: Float): String = "${(fraction * 100f).roundToInt()}%"
// KMK <--

@Preview
@Composable
private fun ChapterNavigatorPreview() {
    var currentPage by remember { mutableIntStateOf(1) }
    TachiyomiPreviewTheme {
        ChapterNavigator(
            isRtl = false,
            onNextChapter = {},
            enabledNext = true,
            onPreviousChapter = {},
            enabledPrevious = true,
            currentPage = currentPage,
            totalPages = 10,
            onPageIndexChange = { currentPage = (it + 1) },
            // SY -->
            currentPageText = "1",
            isVerticalSlider = false,
            // SY <--
        )
        // KMK -->
        ChapterNavigator(
            isRtl = false,
            onNextChapter = {},
            enabledNext = true,
            onPreviousChapter = {},
            enabledPrevious = true,
            currentPage = currentPage,
            totalPages = 10,
            onPageIndexChange = { currentPage = (it + 1) },
            currentPageText = "1",
            isVerticalSlider = true,
        )
        ChapterNavigator(
            isRtl = true,
            onNextChapter = {},
            enabledNext = true,
            onPreviousChapter = {},
            enabledPrevious = true,
            currentPage = currentPage,
            totalPages = 10,
            onPageIndexChange = { currentPage = (it + 1) },
            currentPageText = "1",
            isVerticalSlider = true,
        )
        // KMK <--
    }
}
