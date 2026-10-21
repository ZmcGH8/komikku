package eu.kanade.presentation.more.settings.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue.Settled
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.LocalPreferenceEnabled
import eu.kanade.presentation.more.settings.LocalPreferenceHighlighted
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.tachiyomi.data.track.Tracker
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
private const val TRAILING_FADE_MILLIS = 120
// KMK <--

@Composable
fun TrackingPreferenceWidget(
    modifier: Modifier = Modifier,
    tracker: Tracker,
    checked: Boolean,
    onClick: (() -> Unit)? = null,
    // KMK -->
    isPriority: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /**
     * Swiping the row right-to-left force-logs-out [tracker]. Deliberately
     * independent of [checked]: a tracker derives "logged in" from credential
     * presence, so an expired session or stale cookie can leave a row reading as
     * logged out while the service still holds a usable session. Tapping such a
     * row opens the login dialog instead, with no way to clear the leftovers.
     */
    onSwipeLogout: (() -> Unit)? = null,
    // KMK <--
) {
    val highlighted = LocalPreferenceHighlighted.current
    // KMK -->
    val rowEnabled = LocalPreferenceEnabled.current
    val dismissState = rememberSwipeToDismissBoxState(
        // Same 25% trigger as the library-update-error rows.
        positionalThreshold = { totalDistance -> totalDistance * 0.25f },
    )
    val latestSwipeLogout by rememberUpdatedState(onSwipeLogout)
    val swiping = dismissState.dismissDirection != Settled
    val trailingAlpha by animateFloatAsState(
        targetValue = if (swiping) 0f else 1f,
        animationSpec = tween(TRAILING_FADE_MILLIS),
        label = "trackerTrailingAlpha",
    )
    // KMK <--
    // KMK --> Keys on settledValue so logout fires once the finger lifts rather than
    // mid-drag, then resets the row — logout keeps the row, so it must slide back.
    LaunchedEffect(dismissState.settledValue) {
        if (dismissState.settledValue != Settled) {
            latestSwipeLogout?.invoke()
            dismissState.reset()
        }
    }
    // KMK <--
    // KMK --> Trailing-only: a start-to-end swipe begins at the screen edge and
    // collides with the system back gesture.
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        backgroundContent = { LogoutDismissBackground(dismissState) },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = onSwipeLogout != null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .highlightBackground(highlighted),
        ) {
            Row(
                modifier = Modifier
                    // KMK -->
                    .combinedClickable(
                        enabled = rowEnabled && (onClick != null || onLongClick != null),
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongClick?.invoke() },
                    )
                    // KMK <--
                    .fillMaxWidth()
                    .padding(horizontal = PrefsHorizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackLogoIcon(tracker)
                Text(
                    text = tracker.name,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = TitleFontSize,
                )
                // KMK -->
                if (isPriority) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        modifier = Modifier
                            .alpha(trailingAlpha)
                            .padding(4.dp)
                            .size(32.dp),
                        tint = Color(0xFFFFC107),
                        contentDescription = stringResource(KMR.strings.pref_priority_tracker),
                    )
                }
                // KMK <--
                if (checked) {
                    Icon(
                        imageVector = Icons.Outlined.Done,
                        modifier = Modifier
                            .alpha(trailingAlpha)
                            .padding(4.dp)
                            .size(32.dp),
                        tint = Color(0xFF4CAF50),
                        contentDescription = stringResource(MR.strings.login_success),
                    )
                }
            }
        }
    }
    // KMK <--
}

// KMK -->
@Composable
private fun LogoutDismissBackground(state: SwipeToDismissBoxState) {
    val swiping = state.dismissDirection != Settled
    val backgroundColor by animateColorAsState(
        if (!swiping) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.errorContainer
                .copy(alpha = if (state.targetValue == Settled) 0.45f else 1f)
        },
    )
    // KMK --> The row body is transparent, so an always-drawn icon would sit under
    // the star/checkmark at rest. Reveal it only for the drag that can trigger it.
    val iconAlpha by animateFloatAsState(
        targetValue = if (swiping) 1f else 0f,
        animationSpec = tween(TRAILING_FADE_MILLIS),
        label = "logoutIconAlpha",
    )
    // KMK <--
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = PrefsHorizontalPadding),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Logout,
            contentDescription = stringResource(MR.strings.logout),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.alpha(iconAlpha),
        )
    }
}
// KMK <--
