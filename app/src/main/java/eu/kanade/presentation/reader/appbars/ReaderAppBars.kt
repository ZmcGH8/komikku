package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import kotlinx.collections.immutable.ImmutableSet
import tachiyomi.presentation.core.components.material.padding

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

// SY -->
enum class NavBarType {
    VerticalRight,
    VerticalLeft,
    Bottom,
}
// SY <--

@Composable
fun ReaderAppBars(
    visible: Boolean,

    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,

    viewer: Viewer?,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    // Mihon -->
    onPageIndexChangeFinished: () -> Unit = {},
    // Mihon <--
    // Mihon -->
    /**
     * The vertical navigator's height, as a fraction (0..1] of the space between the top and bottom
     * bars — the *Vertical navigator height* preference, which this fork's layout had stopped
     * consuming, so the slider wrote a value nothing read in any viewer.
     */
    verticalNavigatorHeight: Float = 1f,
    // Mihon <--
    // KMK -->
    /**
     * Chapter-progress mode, for a book: the reader's place in the chapter on screen, 0..1.
     *
     * Non-null renders a chapter-progress slider instead of the page slider — a book has pages
     * without number, and its chapters flow into each other, so the bar shows where the reader is
     * in the chapter they are reading. The prev/next chapter buttons step chapters either way.
     */
    chapterFraction: Float? = null,
    onChapterFractionChange: (Float) -> Unit = {},
    onChapterFractionChangeFinished: () -> Unit = {},
    // KMK <--

    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // KMK --> Null for a chapter whose source cannot answer. The bottom bar draws a button only for a
    // callback it was given, so an extension without a contents or without search simply shows none.
    onClickContents: (() -> Unit)? = null,
    onClickSearch: (() -> Unit)? = null,
    // KMK <--
    // SY -->
    isExhToolsVisible: Boolean,
    onSetExhUtilsVisibility: (Boolean) -> Unit,
    isAutoScroll: Boolean,
    isAutoScrollEnabled: Boolean,
    onToggleAutoscroll: (Boolean) -> Unit,
    autoScrollFrequency: String,
    onSetAutoScrollFrequency: (String) -> Unit,
    onClickAutoScrollHelp: () -> Unit,
    onClickRetryAll: () -> Unit,
    onClickRetryAllHelp: () -> Unit,
    onClickBoostPage: () -> Unit,
    onClickBoostPageHelp: () -> Unit,
    navBarType: NavBarType,
    currentPageText: String,
    enabledButtons: ImmutableSet<String>,
    currentReadingMode: ReadingMode,
    dualPageSplitEnabled: Boolean,
    doublePages: Boolean,
    onClickChapterList: () -> Unit,
    onClickPageLayout: () -> Unit,
    onClickShiftPage: () -> Unit,
    // SY <--
) {
    // KMK -->
    val isRtl = viewer is R2LPagerViewer || (viewer as? WebGpuViewer)?.isReversed == true
    // KMK <--
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeOut(animationSpec = readerBarsFadeAnimationSpec),
        ) {
            // SY -->
            Column {
                // SY <--
                ReaderTopBar(
                    modifier = Modifier
                        .background(backgroundColor)
                        .clickable(onClick = onClickTopAppBar),
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    navigateUp = navigateUp,
                    bookmarked = bookmarked,
                    onToggleBookmarked = onToggleBookmarked,
                    // SY -->
                    onOpenInWebView = null, // onOpenInWebView,
                    onOpenInBrowser = null, // onOpenInBrowser,
                    onShare = null, // onShare,
                    // SY <--
                )
                // SY -->
                ExhUtils(
                    isVisible = isExhToolsVisible,
                    onSetExhUtilsVisibility = onSetExhUtilsVisibility,
                    backgroundColor = backgroundColor,
                    isAutoScroll = isAutoScroll,
                    isAutoScrollEnabled = isAutoScrollEnabled,
                    onToggleAutoscroll = onToggleAutoscroll,
                    autoScrollFrequency = autoScrollFrequency,
                    onSetAutoScrollFrequency = onSetAutoScrollFrequency,
                    onClickAutoScrollHelp = onClickAutoScrollHelp,
                    onClickRetryAll = onClickRetryAll,
                    onClickRetryAllHelp = onClickRetryAllHelp,
                    onClickBoostPage = onClickBoostPage,
                    onClickBoostPageHelp = onClickBoostPageHelp,
                )
            }
            // SY <--
        }

        // KMK -->
        when (navBarType) {
            NavBarType.VerticalLeft -> {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally(
                        initialOffsetX = { -it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeIn(animationSpec = readerBarsFadeAnimationSpec),
                    exit = slideOutHorizontally(
                        targetOffsetX = { -it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeOut(animationSpec = readerBarsFadeAnimationSpec),
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.Start),
                ) {
                    // Mihon --> A full-height slot with the shortened navigator anchored to the
                    // bottom of it. Shrinking the navigator itself instead would leave the freed
                    // space *below* the weighted middle — pushing the bottom bar up off the bottom
                    // of the screen — because the weighted slot sizes itself to its content. The
                    // Box takes the full slot, keeps the bar where it belongs, and the fraction
                    // empties into the space above the navigator, where it reads as "the navigator
                    // is shorter", not as "everything moved".
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        ChapterNavigator(
                            isRtl = isRtl,
                            onNextChapter = onNextChapter,
                            enabledNext = enabledNext,
                            onPreviousChapter = onPreviousChapter,
                            enabledPrevious = enabledPrevious,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            onPageIndexChange = onPageIndexChange,
                            onPageIndexChangeFinished = onPageIndexChangeFinished,
                            // SY -->
                            isVerticalSlider = true,
                            currentPageText = currentPageText,
                            // SY <--
                            // KMK -->
                            chapterFraction = chapterFraction,
                            onChapterFractionChange = onChapterFractionChange,
                            onChapterFractionChangeFinished = onChapterFractionChangeFinished,
                            // KMK <--
                            // Mihon -->
                            modifier = Modifier.fillMaxHeight(verticalNavigatorHeight),
                            // Mihon <--
                        )
                    }
                    // Mihon <--
                }
            }

            NavBarType.VerticalRight -> {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally(
                        initialOffsetX = { it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeIn(animationSpec = readerBarsFadeAnimationSpec),
                    exit = slideOutHorizontally(
                        targetOffsetX = { it },
                        animationSpec = readerBarsSlideAnimationSpec,
                    ) +
                        fadeOut(animationSpec = readerBarsFadeAnimationSpec),
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.End),
                ) {
                    // Mihon --> Same full-height, bottom-anchored slot as the left-hand variant.
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        ChapterNavigator(
                            isRtl = isRtl,
                            onNextChapter = onNextChapter,
                            enabledNext = enabledNext,
                            onPreviousChapter = onPreviousChapter,
                            enabledPrevious = enabledPrevious,
                            currentPage = currentPage,
                            totalPages = totalPages,
                            onPageIndexChange = onPageIndexChange,
                            onPageIndexChangeFinished = onPageIndexChangeFinished,
                            // SY -->
                            isVerticalSlider = true,
                            currentPageText = currentPageText,
                            // SY <--
                            // KMK -->
                            chapterFraction = chapterFraction,
                            onChapterFractionChange = onChapterFractionChange,
                            onChapterFractionChangeFinished = onChapterFractionChangeFinished,
                            // KMK <--
                            // Mihon -->
                            modifier = Modifier.fillMaxHeight(verticalNavigatorHeight),
                            // Mihon <--
                        )
                    }
                    // Mihon <--
                }
            }
            // KMK <--
            else -> Spacer(modifier = Modifier.weight(1f))
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeOut(animationSpec = readerBarsFadeAnimationSpec),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                // SY -->
                if (navBarType == NavBarType.Bottom) {
                    // SY <--
                    ChapterNavigator(
                        isRtl = isRtl,
                        onNextChapter = onNextChapter,
                        enabledNext = enabledNext,
                        onPreviousChapter = onPreviousChapter,
                        enabledPrevious = enabledPrevious,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageIndexChange = onPageIndexChange,
                        onPageIndexChangeFinished = onPageIndexChangeFinished,
                        // SY -->
                        isVerticalSlider = false,
                        currentPageText = currentPageText,
                        // SY <--
                        // KMK -->
                        chapterFraction = chapterFraction,
                        onChapterFractionChange = onChapterFractionChange,
                        onChapterFractionChangeFinished = onChapterFractionChangeFinished,
                        // KMK <--
                    )
                }
                ReaderBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                        .padding(horizontal = MaterialTheme.padding.small)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    readingMode = readingMode,
                    onClickReadingMode = onClickReadingMode,
                    orientation = orientation,
                    onClickOrientation = onClickOrientation,
                    cropEnabled = cropEnabled,
                    onClickCropBorder = onClickCropBorder,
                    onClickSettings = onClickSettings,
                    // SY -->
                    enabledButtons = enabledButtons,
                    currentReadingMode = currentReadingMode,
                    dualPageSplitEnabled = dualPageSplitEnabled,
                    doublePages = doublePages,
                    onClickChapterList = onClickChapterList,
                    onClickWebView = onOpenInWebView,
                    onClickBrowser = onOpenInBrowser,
                    onClickShare = onShare,
                    // KMK -->
                    onClickContents = onClickContents,
                    onClickSearch = onClickSearch,
                    // KMK <--
                    onClickPageLayout = onClickPageLayout,
                    onClickShiftPage = onClickShiftPage,
                    // SY <--
                )
            }
        }
    }
}
