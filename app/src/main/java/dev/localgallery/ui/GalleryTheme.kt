package dev.localgallery.ui

import android.content.Context
import android.graphics.Color.TRANSPARENT
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.material.color.MaterialColors
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal data class GalleryAppearance(val custom: Boolean = false, val seed: Int = 0xFF22644F.toInt())

private val Context.appearanceStore by preferencesDataStore(
    name = "gallery_appearance",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
private val customThemeKey = booleanPreferencesKey("custom_theme")
private val customSeedKey = intPreferencesKey("custom_seed")

internal object GalleryAppearanceSettings {
    // RemoteViews composition-error callbacks cannot suspend to read the store.
    @Volatile var latest = GalleryAppearance()
        private set

    fun observe(context: Context) = context.applicationContext.appearanceStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            GalleryAppearance(
                custom = preferences[customThemeKey] ?: false,
                seed = preferences[customSeedKey] ?: GalleryAppearance().seed,
            ).also { latest = it }
        }

    suspend fun save(context: Context, appearance: GalleryAppearance) {
        context.applicationContext.appearanceStore.edit { preferences ->
            preferences[customThemeKey] = appearance.custom
            preferences[customSeedKey] = appearance.seed
        }
        latest = appearance
    }
}

internal fun galleryColorScheme(context: Context, appearance: GalleryAppearance, dark: Boolean) = when {
    appearance.custom -> accentColorScheme(appearance.seed, dark)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    else -> accentColorScheme(GalleryAppearance().seed, dark)
}

private fun accentColorScheme(seed: Int, dark: Boolean): ColorScheme {
    val roles = MaterialColors.getColorRoles(seed, !dark)
    val primary = Color(roles.accent)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val tint = if (dark) 0.08f else 0.04f
    return base.copy(
        primary = primary,
        onPrimary = Color(roles.onAccent),
        primaryContainer = Color(roles.accentContainer),
        onPrimaryContainer = Color(roles.onAccentContainer),
        secondary = primary,
        onSecondary = Color(roles.onAccent),
        secondaryContainer = Color(roles.accentContainer),
        onSecondaryContainer = Color(roles.onAccentContainer),
        surface = lerp(base.surface, primary, tint),
        background = lerp(base.background, primary, tint),
        surfaceContainer = lerp(base.surfaceContainer, primary, tint),
        surfaceContainerHigh = lerp(base.surfaceContainerHigh, primary, tint),
        surfaceContainerHighest = lerp(base.surfaceContainerHighest, primary, tint),
        surfaceTint = primary,
    )
}

@Composable fun GalleryTheme(content: @Composable () -> Unit) = GalleryTheme(GalleryAppearance(), content)

@Composable internal fun GalleryTheme(appearance: GalleryAppearance, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    // Wallpaper changes invalidate the activity configuration. Resolve that scheme from its current resources.
    val colors = if (!appearance.custom && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        galleryColorScheme(context, appearance, dark)
    } else {
        remember(appearance, dark) { galleryColorScheme(context, appearance, dark) }
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable internal fun ComponentActivity.GalleryActivityTheme(
    appearance: GalleryAppearance,
    content: @Composable () -> Unit,
) {
    GalleryTheme(appearance) {
        val useDarkIcons = MaterialTheme.colorScheme.surface.luminance() > 0.179f
        SideEffect {
            val bars = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { !useDarkIcons }
            enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        }
        content()
    }
}
