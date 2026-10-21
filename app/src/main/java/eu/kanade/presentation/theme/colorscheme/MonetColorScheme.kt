package eu.kanade.presentation.theme.colorscheme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.DynamicScheme
import com.materialkolor.toColorScheme
import mihon.app.di.globalAppGraph

/**
 * Wallpaper-derived theming.
 *
 * Android 12+ exposes the system palette directly. Below that there is no such API, so the seed is
 * the wallpaper's own colours - sampled by [WallpaperSeedSampler] where that is possible, and read
 * from `getWallpaperColors` while the sample is still running - and then the system accent. When
 * none is available the scheme falls back to the app's own accent rather than to a fixed built-in
 * theme, so selecting "Monet" never silently means "some unrelated static theme".
 *
 * [isAmoled] only reaches the compat scheme. On Android 12+ the system palette is already dark and
 * [BaseColorScheme] layers the pure-black override on top; below that the containers come from
 * materialkolor, and [BaseColorScheme] skips its container override for Monet so the system values
 * survive where they exist. Left unset, those containers stayed at their seeded tone, so "pure black
 * dark mode" blackened the background and surface but left every card, sheet and list item at the
 * wallpaper's own hue.
 */
internal class MonetColorScheme(
    context: Context,
    sampledSeed: Color? = null,
    isAmoled: Boolean = false,
) : BaseColorScheme() {

    private val monet: BaseColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> MonetSystemColorScheme(context)
        // A sampled wallpaper colour beats getWallpaperColors, which is null until the system has
        // extracted the wallpaper and washed out on the ones it has. Null while the sample is still
        // running, so the scheme still comes up immediately on the chain below.
        else -> MonetCompatColorScheme(sampledSeed ?: dynamicSeedOrDefault(context), isAmoled)
    }

    override val darkScheme
        get() = monet.darkScheme

    override val lightScheme
        get() = monet.lightScheme
}

@RequiresApi(Build.VERSION_CODES.S)
private class MonetSystemColorScheme(context: Context) : BaseColorScheme() {
    override val lightScheme = dynamicLightColorScheme(context)
    override val darkScheme = dynamicDarkColorScheme(context)
}

internal class MonetCompatColorScheme(seed: Color, isAmoled: Boolean = false) : BaseColorScheme() {
    override val lightScheme = generateColorSchemeFromSeed(seed = seed, dark = false, isAmoled = false)
    override val darkScheme = generateColorSchemeFromSeed(seed = seed, dark = true, isAmoled = isAmoled)

    companion object {
        fun generateColorSchemeFromSeed(seed: Color, dark: Boolean, isAmoled: Boolean): ColorScheme {
            return DynamicScheme(
                seedColor = seed,
                isDark = dark,
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
                style = PaletteStyle.TonalSpot,
            )
                .toColorScheme(isAmoled = isAmoled)
        }
    }
}

/**
 * Best available stand-in for the Android 12+ system palette on older releases.
 *
 * `getWallpaperColors` needs no permission but does throw `SecurityException` on some
 * OEM builds and returns null whenever the wallpaper has not been extracted yet, so both
 * are treated as "try the next source" rather than as failures.
 */
private fun dynamicSeedOrDefault(context: Context): Color {
    wallpaperSeed(context)?.let { return it }
    systemAccentSeed(context)?.let { return it }
    // Same source the Custom theme seeds from, so "Monet" degrades into the user's own
    // accent instead of a hardcoded colour that drifts away from the brand.
    return Color(globalAppGraph.uiPreferences.colorTheme().get())
}

private fun wallpaperSeed(context: Context): Color? {
    // WallpaperManager.getWallpaperColors was added in API 27 and minSdk is 26.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return runCatching {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor
            // android.graphics.Color -> Compose Color. androidx.core.graphics has no toArgb for
            // this type (only androidx.compose.ui.graphics.toArgb, for the other direction), and
            // Color::value is a ULong, not the ARGB int the Color(Int) factory takes.
            ?.let { Color(android.graphics.Color.argb(it.alpha(), it.red(), it.green(), it.blue())) }
    }.getOrNull()
}

private fun systemAccentSeed(context: Context): Color? {
    // system_accent1_* resources were added in API 31; resolving one below that throws.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        Color(context.getColor(android.R.color.system_accent1_100))
    }.getOrNull()
}
