package eu.kanade.presentation.util

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransitionContent
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import logcat.LogPriority
import logcat.logcat
import mihon.core.concurrency.AppDispatchersHolder
import java.util.WeakHashMap

/**
 * For invoking back press to the parent activity
 */
val LocalBackPress: ProvidableCompositionLocal<(() -> Unit)?> = staticCompositionLocalOf { null }

interface Tab : cafe.adriel.voyager.navigator.tab.Tab {
    suspend fun onReselect(navigator: Navigator) {}

    // SY -->
    @Composable
    fun isEnabled(): Boolean = true
    // SY <--
}

abstract class Screen : Screen {
    override val key: ScreenKey = "${this::class.qualifiedName}#$uniqueScreenKey#${System.identityHashCode(this)}"
}

/**
 * A variant of ScreenModel.coroutineScope except with the IO dispatcher instead of the
 * main dispatcher.
 */
val ScreenModel.ioCoroutineScope: CoroutineScope
    get() = ScreenModelStore.getOrPutDependency(
        screenModel = this,
        name = "ScreenModelIoCoroutineScope",
        factory = { key -> CoroutineScope(AppDispatchersHolder.get().io + SupervisorJob()) + CoroutineName(key) },
        onDispose = { scope -> scope.cancel() },
    )

interface AssistContentScreen {
    fun onProvideAssistUrl(): String?
}

// KMK -->
/**
 * Pure slide + fade transition builder for Voyager push/pop.
 *
 * @param pop true when the transition is a stack pop (mirrors the slide direction).
 */
fun navigatorTransition(pop: Boolean): AnimatedContentTransitionScope<Screen>.() -> ContentTransform = {
    val enterOffset: (Int) -> Int = { if (pop) -it / 6 else it / 6 }
    val exitOffset: (Int) -> Int = { if (pop) it / 6 else -it / 6 }
    slideInHorizontally(tween(UiMotion.SCREEN_ENTER, easing = UiMotion.EMPHASIZED), enterOffset) +
        fadeIn(tween(UiMotion.SCREEN_ENTER, easing = UiMotion.EMPHASIZED)) togetherWith
        slideOutHorizontally(tween(UiMotion.SCREEN_EXIT, easing = UiMotion.EMPHASIZED), exitOffset) +
        fadeOut(tween(UiMotion.SCREEN_EXIT, easing = UiMotion.EMPHASIZED))
}

@Composable
fun DefaultNavigatorScreenTransition(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    ScreenTransition(
        navigator = navigator,
        transition = {
            navigatorTransition(navigator.lastEvent == StackEvent.Pop).invoke(this)
        },
        modifier = modifier,
    )
}
// KMK <--

@Composable
fun ScreenTransition(
    navigator: Navigator,
    transition: AnimatedContentTransitionScope<Screen>.() -> ContentTransform,
    modifier: Modifier = Modifier,
    content: ScreenTransitionContent = { it.Content() },
) {
    val slots = remember { ScreenSaveableSlots() }
    AnimatedContent(
        targetState = navigator.lastItem,
        transitionSpec = { transition() },
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        label = "screen-transition",
    ) { screen ->
        if (isPreviewBuildType) {
            logcat(LogPriority.ERROR) { "ScreenTransition: ${screen.key}" }
        }
        // NEVER key this off the target: AnimatedContent keeps the outgoing screen composed while
        // the incoming one is composed, so one Screen can be in the composition twice (pushed twice,
        // or popped then re-pushed before the exit finishes). Voyager registers under
        // "${screen.key}:$key" and SaveableStateHolder rejects a duplicate registration, and the
        // settings categories are singletons, so every visit reuses one key. A per-composition slot
        // instead - owned by `slots` rather than by this content, so it outlives the exit animation.
        val composition = remember { Any() }
        val slot = slots.acquire(screen, composition)
        DisposableEffect(composition) {
            onDispose { slots.release(composition) }
        }
        val saveableKey = "screen-transition-${screen.key}@${System.identityHashCode(slot)}"
        navigator.saveableState(saveableKey, screen) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                content(screen)
            }
        }
    }
}

/**
 * Hands every screen composed by [ScreenTransition] the saveable-state key it will keep using for
 * as long as it sits on the navigator stack.
 *
 * A key held by `remember` inside the screen's own content does not survive: AnimatedContent
 * disposes that content as soon as the screen's exit animation ends, so popping back to a screen
 * composes it under a brand new key, one the [androidx.compose.runtime.saveable.SaveableStateHolder]
 * has never seen. Everything the screen keeps in `rememberSaveable` - the scroll position of a
 * browse grid, the offset of a chapter list - came back at its default, which is why every list in
 * the app jumped back to the top on the way back from a detail screen.
 *
 * So the key is owned here, one slot per screen, instead of by the content that gets disposed, and
 * each composition asks for the slot of the screen it is rendering. That gives three things at
 * once: the same screen coming back finds the slot it left, a composition that merely recomposes
 * keeps the key it already registered under, and the one case where AnimatedContent has a screen
 * composed twice - pushed again before the previous animation finished - gets a second slot rather
 * than a duplicate registration, which [androidx.compose.runtime.saveable.SaveableStateHolder]
 * rejects outright.
 *
 * Entries are weak: a screen that leaves the stack is dropped along with its slot, which is also
 * when Voyager drops the saved state itself (`Navigator.dispose` clears every key under
 * `screen.key`), so nothing is kept alive for a screen nobody can return to.
 */
private class ScreenSaveableSlots {
    private val slotOfScreen = WeakHashMap<Screen, Any>()

    /** Slot each live composition holds, keyed by that composition's own token. */
    private val slotOfComposition = mutableMapOf<Any, Any>()

    fun acquire(screen: Screen, composition: Any): Any {
        // Already asked on an earlier recomposition of this same composition: the key it registered
        // under must not change under it, or the holder would drop everything saved so far.
        slotOfComposition[composition]?.let { return it }
        val known = slotOfScreen[screen]
        val held = known != null && slotOfComposition.any { it.value === known }
        val slot = if (held) Any() else (known ?: Any())
        slotOfComposition[composition] = slot
        slotOfScreen[screen] = slot
        return slot
    }

    fun release(composition: Any) {
        slotOfComposition.remove(composition)
    }
}
