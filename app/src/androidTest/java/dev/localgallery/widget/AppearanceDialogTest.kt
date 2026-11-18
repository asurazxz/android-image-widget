package dev.localgallery.widget

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.localgallery.ui.AppearanceDialog
import dev.localgallery.ui.GalleryAppearance
import dev.localgallery.ui.GalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppearanceDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidHexDisablesApplyUntilAValidColorIsEntered() {
        val applied = showDialog()
        compose.onNodeWithTag("appearance-custom").performClick().assertIsSelected()
        val hex = compose.onNodeWithTag("appearance-hex").performScrollTo()
        val apply = compose.onNodeWithTag("appearance-apply")

        hex.performTextReplacement("#C0FFEX")
        compose.onNodeWithText("Enter a six-digit hex color, such as #22644F.").assertExists()
        apply.assertIsNotEnabled()

        hex.performTextReplacement("#40c9a2")
        apply.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(GalleryAppearance(custom = true, seed = 0xFF40C9A2.toInt())), applied)
        }
        compose.onNodeWithText("Appearance").assertDoesNotExist()
    }

    @Test fun cancelDiscardsAnEditedColor() {
        val applied = showDialog(GalleryAppearance(custom = true))
        compose.onNodeWithTag("appearance-swatch-ocean").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.runOnIdle { assertEquals(emptyList<GalleryAppearance>(), applied) }
        compose.onNodeWithText("Appearance").assertDoesNotExist()
    }

    @Test fun revertingToDefaultKeepsTheMostRecentValidCustomColor() {
        val applied = showDialog(GalleryAppearance(custom = true))
        val hex = compose.onNodeWithTag("appearance-hex").performScrollTo()
        hex.performTextReplacement("123ABC")
        hex.performTextReplacement("#")

        compose.onNodeWithTag("appearance-default").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("appearance-apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(GalleryAppearance(custom = false, seed = 0xFF123ABC.toInt())), applied)
        }
    }

    private fun showDialog(appearance: GalleryAppearance = GalleryAppearance()): List<GalleryAppearance> {
        val applied = mutableListOf<GalleryAppearance>()
        val show = mutableStateOf(true)
        compose.setContent {
            GalleryTheme {
                if (show.value) {
                    AppearanceDialog(
                        appearance = appearance,
                        onApply = {
                            applied += it
                            show.value = false
                        },
                        onDismiss = { show.value = false },
                    )
                }
            }
        }
        return applied
    }
}
