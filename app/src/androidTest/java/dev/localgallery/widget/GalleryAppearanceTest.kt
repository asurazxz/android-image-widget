package dev.localgallery.widget

import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import dev.localgallery.ui.GalleryAppearance
import dev.localgallery.ui.GalleryAppearanceSettings
import dev.localgallery.ui.galleryColorScheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAppearanceTest {
    @Test fun aNewSettingsObserverReadsTheSavedModeAndCustomColor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = GalleryAppearanceSettings.observe(context).first()
        val custom = GalleryAppearance(custom = true, seed = 0xFF774C96.toInt())
        try {
            GalleryAppearanceSettings.save(context, custom)
            assertEquals(custom, GalleryAppearanceSettings.observe(context).first())
            GalleryAppearanceSettings.save(context, custom.copy(custom = false))
            assertEquals(custom.copy(custom = false), GalleryAppearanceSettings.observe(context).first())
        } finally {
            GalleryAppearanceSettings.save(context, previous)
        }
    }

    @Test fun customLightAndDarkSchemesKeepActionAndRecoveryTextReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seeds = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFAEE00, 0xFF853E81)
        for (seed in seeds) {
            val appearance = GalleryAppearance(custom = true, seed = seed.toInt())
            val light = galleryColorScheme(context, appearance, dark = false)
            val dark = galleryColorScheme(context, appearance, dark = true)
            assertNotEquals(light.surface, dark.surface)
            for (scheme in listOf(light, dark)) {
                val pairs = listOf(
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSurface to scheme.surface,
                    scheme.primary to scheme.surface,
                )
                for ((foreground, background) in pairs) {
                    val brighter = maxOf(foreground.luminance(), background.luminance())
                    val darker = minOf(foreground.luminance(), background.luminance())
                    assertTrue("Contrast for ${seed.toString(16)}: $foreground on $background",
                        (brighter + 0.05f) / (darker + 0.05f) >= 4.5f)
                }
            }
        }
    }
}
