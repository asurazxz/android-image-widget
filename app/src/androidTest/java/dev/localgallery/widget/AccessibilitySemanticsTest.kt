package dev.localgallery.widget

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.localgallery.media.MediaAlbum
import dev.localgallery.media.MediaImage
import dev.localgallery.ui.AlbumRow
import dev.localgallery.ui.GalleryTheme
import dev.localgallery.ui.SourceModeControl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccessibilitySemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceOptionsAreLabeledSingleRadioTargetsWithUsableSizeAndEnabledState() {
        val selected = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        compose.setContent {
            GalleryTheme {
                SourceModeControl(selected.value, enabled = enabled.value) { selected.value = it }
            }
        }

        val entire = compose.onNodeWithTag("source-entire")
        val selectedAlbums = compose.onNodeWithTag("source-selected")
        compose.onNodeWithText("Entire gallery").assertExists()
        compose.onNodeWithText("Selected albums").assertExists()
        entire.assertIsSelected().assertIsEnabled().assertHeightIsAtLeast(56.dp)
        selectedAlbums.assertIsEnabled().assertHeightIsAtLeast(56.dp)
        compose.onAllNodes(hasTestTag("source-entire") and hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("source-selected") and hasClickAction()).assertCountEquals(1)
        entire.performClick()
        compose.runOnIdle { assertEquals(true, selected.value) }
        selectedAlbums.performClick()
        compose.runOnIdle { assertEquals(false, selected.value) }
        selectedAlbums.assertIsSelected()

        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("source-entire").assertIsNotEnabled()
        compose.onNodeWithTag("source-selected").assertIsNotEnabled()
    }

    @Test fun albumRowExposesItsNameCountAndCheckedStateOnOneLargeTarget() {
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val album = MediaAlbum(
            key = "external:camera",
            name = "Camera",
            count = 4,
            cover = MediaImage(
                key = "cover",
                uri = Uri.parse("content://invalid/cover"),
                volume = "external",
                bucketId = "camera",
                bucketName = "Camera",
                mimeType = "image/jpeg",
                width = 1,
                height = 1,
            ),
        )
        compose.setContent {
            GalleryTheme { AlbumRow(album, checked.value, enabled = enabled.value) { checked.value = !checked.value } }
        }
        val albumTarget = compose.onNodeWithTag("album-toggle-${album.key}")
        albumTarget.assertIsOff().assertIsEnabled().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Camera").assertExists()
        compose.onNodeWithText("4 photos · external").assertExists()
        compose.onAllNodes(hasTestTag("album-toggle-${album.key}") and hasClickAction()).assertCountEquals(1)
        albumTarget.performClick().assertIsOn()

        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("album-toggle-${album.key}").assertIsOn().assertIsNotEnabled()
    }

    @Test fun rotationOptionsHaveLabeledSingleRadioTargetsAndPreserveTestTags() {
        val rotation = mutableStateOf(30L)
        val enabled = mutableStateOf(true)
        compose.setContent {
            GalleryTheme { RotationOptionsControl(rotation.value, enabled = enabled.value) { rotation.value = it } }
        }
        val thirtyMinutes = compose.onNodeWithTag("rotation-radio-30")
        thirtyMinutes.assertIsSelected().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("rotation-option-30").assertExists()
        compose.onNodeWithText("Every 30 minutes").assertExists()
        compose.onAllNodes(hasTestTag("rotation-radio-30") and hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("rotation-radio-custom").performClick()
        compose.runOnIdle { assertEquals(60L, rotation.value) }
        compose.onNodeWithTag("rotation-radio-custom").assertIsSelected()

        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("rotation-radio-30").assertIsNotEnabled()
    }
}
