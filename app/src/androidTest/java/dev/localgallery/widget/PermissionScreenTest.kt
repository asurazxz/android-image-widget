package dev.localgallery.widget

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.localgallery.ui.GalleryTheme
import dev.localgallery.ui.PhotoAccessPanel
import dev.localgallery.permissions.PhotoAccess
import org.junit.Rule
import org.junit.Test

class PermissionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deniedAccessOffersExplicitPermissionAction() {
        compose.setContent { GalleryTheme { PhotoAccessPanel(PhotoAccess.DENIED, {}, {}) } }
        compose.onNodeWithText("Grant full photo access").assertIsDisplayed()
        compose.onNodeWithText("Open app settings").assertIsDisplayed()
    }
}
