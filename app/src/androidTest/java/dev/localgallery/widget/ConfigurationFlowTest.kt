package dev.localgallery.widget

// Exercises the widget configuration activity result contract.

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.media.MediaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConfigurationFlowTest {
    @get:Rule
    val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
    )

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun savingConfigurationReturnsOkWithWidgetIdAndPersistedPhoto() {
        val (context, host, id) = bindWidget()
        val dao = WidgetDatabase.get(context).widgets()
        var photo: android.net.Uri? = null
        var scenarioToClose: ActivityScenario<PhotoWidgetConfigurationActivity>? = null
        try {
            photo = addPhoto(context)
            val scenario = launchConfiguration(context, id)
            scenarioToClose = scenario
            awaitSaveEnabled()
            composeRule.onNodeWithText("Save widget").performClick()

            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(id, result.resultData?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            val saved = kotlinx.coroutines.runBlocking { dao.get(id) }
                ?: throw AssertionError("Saving should persist a configured widget row")
            assertNotNull("Saving should select the initial photo", saved.currentUri)
            assertTrue(saved.currentUri!!.startsWith("content://"))
            assertEquals("CROP", saved.displayMode)
        } finally {
            scenarioToClose?.close()
            kotlinx.coroutines.runBlocking { WidgetController(context).delete(id) }
            host.deleteAppWidgetId(id)
            photo?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun cancelReturnsCanceledAndDoesNotPersistWidgetState() {
        val (context, host, id) = bindWidget()
        val dao = WidgetDatabase.get(context).widgets()
        var scenarioToClose: ActivityScenario<PhotoWidgetConfigurationActivity>? = null
        try {
            val scenario = launchConfiguration(context, id)
            scenarioToClose = scenario
            composeRule.onNodeWithText("Cancel").performClick()

            val result = scenario.result
            assertEquals(Activity.RESULT_CANCELED, result.resultCode)
            assertNull("Cancel must not persist an orphan widget row", kotlinx.coroutines.runBlocking { dao.get(id) })
        } finally {
            scenarioToClose?.close()
            kotlinx.coroutines.runBlocking { WidgetController(context).delete(id) }
            host.deleteAppWidgetId(id)
        }
    }

    @Test
    fun albumPickerReturnsToSetupAndFitColorIsSavedWithTheWidget() {
        val (context, host, id) = bindWidget()
        val photo = addPhoto(context)
        try {
            val album = runBlocking { MediaRepository(context).albums() }.single { it.name == "ConfigurationFlowTest" }
            launchConfiguration(context, id).use { scenario ->
                awaitSaveEnabled()
                composeRule.onNodeWithTag("source-selected").performClick()
                composeRule.onNodeWithTag("configuration-form").performScrollToNode(hasTestTag("choose-albums"))
                composeRule.onNodeWithTag("choose-albums").performClick()
                composeRule.onNodeWithTag("album-search").performTextInput("ConfigurationFlowTest")
                composeRule.onNodeWithTag("album-picker-list").performScrollToNode(hasTestTag("album-toggle-${album.key}"))
                composeRule.onNodeWithTag("album-toggle-${album.key}").performClick()
                composeRule.onNodeWithTag("album-picker-done").performClick()
                composeRule.onNodeWithTag("save-widget").assertIsDisplayed()
                composeRule.onNodeWithTag("configuration-form").performScrollToNode(hasText("Fit"))
                composeRule.onNodeWithText("Fit").performClick()
                composeRule.onNodeWithTag("configuration-form").performScrollToNode(hasTestTag("fit-background-cream"))
                composeRule.onNodeWithTag("fit-background-cream").performClick()
                composeRule.onNodeWithTag("save-widget").performClick()
                assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
                val saved = requireNotNull(runBlocking { WidgetDatabase.get(context).widgets().get(id) })
                assertEquals(listOf(album.key), saved.selectedAlbums)
                assertEquals("FIT", saved.displayMode)
                assertEquals(FitBackgroundColors.CREAM.argb, saved.fitBackgroundColor)
            }
        } finally {
            runBlocking { WidgetController(context).delete(id) }
            host.deleteAppWidgetId(id)
            context.contentResolver.delete(photo, null, null)
        }
    }

    private fun launchConfiguration(context: android.content.Context, id: Int) =
        ActivityScenario.launchActivityForResult<PhotoWidgetConfigurationActivity>(
            Intent(context, PhotoWidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        )

    private fun bindWidget(): BoundWidget {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

        val host = AppWidgetHost(context, HOST_ID)
        val id = host.allocateAppWidgetId()
        val allowed = AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(
            id,
            ComponentName(context, PhotoWidgetReceiver::class.java),
        )
        if (!allowed) {
            host.deleteAppWidgetId(id)
            throw AssertionError("The test host must be allowed to bind the photo widget")
        }
        return BoundWidget(context, host, id)
    }

    private fun addPhoto(context: android.content.Context): android.net.Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "configuration-${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ConfigurationFlowTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        try {
            resolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xFF526E61.toInt())
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                } finally {
                    bitmap.recycle()
                }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (failure: Exception) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun awaitSaveEnabled() {
        composeRule.waitUntil(timeoutMillis = SAVE_BUTTON_TIMEOUT_MS) {
            runCatching { composeRule.onNodeWithText("Save widget").assertIsEnabled() }.isSuccess
        }
    }

    private data class BoundWidget(
        val context: android.content.Context,
        val host: AppWidgetHost,
        val id: Int,
    )

    private companion object {
        const val HOST_ID = 8726
        const val SAVE_BUTTON_TIMEOUT_MS = 20_000L
    }
}
