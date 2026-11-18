package dev.localgallery.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Acceptance coverage for the app's widget list and per-widget configuration draft. */
class ManagementUiTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun mainEditRoutesToMatchingWidgetAndLoadsPreferencesThenSaves168Hours() {
        val fixture = createConfiguredWidget(rotationMinutes = 30, displayMode = "FIT")
        var main: ActivityScenario<MainActivity>? = null
        try {
            main = ActivityScenario.launch(MainActivity::class.java)
            openWidgetEditor(fixture.id)
            composeRule.onNodeWithTag("source-entire").assertIsSelected()
            scrollToText("Photo display")
            composeRule.onNodeWithText("Fit").assertIsSelected()
            scrollToText("Automatic rotation")
            composeRule.onNodeWithTag("rotation-radio-30").assertIsSelected()
            scrollToText("Every N hours")
            composeRule.onNodeWithTag("rotation-radio-custom").performClick()
            composeRule.onNodeWithTag("custom-hours").performTextClearance()
            composeRule.onNodeWithTag("custom-hours").performTextInput("168")
            scrollToTag("save-widget")
            composeRule.onNodeWithTag("save-widget").performClick()
            composeRule.waitUntil(20_000) {
                runBlocking { fixture.dao.get(fixture.id)?.rotationMinutes == 10_080L }
            }
            assertEquals("FIT", runBlocking { fixture.dao.get(fixture.id) }?.displayMode)
            openWidgetEditor(fixture.id)
            scrollToText("Automatic rotation")
            composeRule.onNodeWithTag("rotation-radio-0").performClick()
            scrollToTag("save-widget")
            composeRule.onNodeWithTag("save-widget").performClick()
            composeRule.waitUntil(20_000) {
                runBlocking { fixture.dao.get(fixture.id)?.rotationMinutes == 0L }
            }
        } finally {
            main?.close()
            finishConfigurationIfOpen()
            fixture.close()
        }
    }

    @Test fun invalidCustomInputDisablesSaveAndRecreationPreservesDraft() {
        val fixture = createConfiguredWidget(rotationMinutes = 1_440)
        var configuration: ActivityScenario<PhotoWidgetConfigurationActivity>? = null
        try {
            configuration = launchEdit(fixture.id)
            scrollToText("Every N hours")
            composeRule.onNodeWithTag("rotation-radio-custom").performClick()
            composeRule.onNodeWithTag("custom-hours").performTextClearance()
            composeRule.onNodeWithTag("custom-hours").performTextInput("1.5")
            scrollToTag("save-widget")
            composeRule.onNodeWithTag("save-widget").assertIsNotEnabled()
            configuration.recreate()
            scrollToText("Every N hours")
            composeRule.onNodeWithTag("custom-hours").assertTextEquals("Custom hours (1–168)", "1.5")
            scrollToTag("save-widget")
            composeRule.onNodeWithTag("save-widget").assertIsNotEnabled()
            composeRule.onNodeWithText("Cancel").performClick()
            assertEquals(1_440L, runBlocking { fixture.dao.get(fixture.id) }?.rotationMinutes)
        } finally {
            configuration?.close()
            fixture.close()
        }
    }

    @Test fun staleEditShowsUnavailableStateAndCannotSave() {
        val fixture = createConfiguredWidget()
        // Keep the launcher binding but remove the record through the same lock as rendering.
        runBlocking { WidgetController(context()).delete(fixture.id) }
        var configuration: ActivityScenario<PhotoWidgetConfigurationActivity>? = null
        try {
            configuration = launchEdit(fixture.id)
            val unavailable = composeRule.onNodeWithText(
                "This widget is no longer available. Return to the app and choose an existing widget to edit.",
            )
            composeRule.waitUntil(10_000) { runCatching { unavailable.assertExists() }.isSuccess }
            scrollToTag("save-widget")
            composeRule.onNodeWithTag("save-widget").assertIsNotEnabled()
        } finally {
            configuration?.close()
            fixture.close()
        }
    }

    private fun openWidgetEditor(id: Int) {
        val tag = "edit-widget-$id"
        val edit = composeRule.onNodeWithTag(tag)
        // Saving persists before configuration finishes and the management screen is resumed.
        composeRule.waitUntil(10_000) { runCatching { edit.assertIsEnabled() }.isSuccess }
        scrollToTag(tag)
        edit.performClick()
    }

    private fun launchEdit(id: Int) = ActivityScenario.launch<PhotoWidgetConfigurationActivity>(
        Intent(context(), PhotoWidgetConfigurationActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(PhotoWidgetConfigurationActivity.EXTRA_MANAGE_EDIT, true),
    )

    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun scrollToTag(tag: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))
    }

    private fun createConfiguredWidget(rotationMinutes: Long = 1_440, displayMode: String = "CROP"): Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val host = AppWidgetHost(context, HOST_ID)
        val id = host.allocateAppWidgetId()
        assertTrue(AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(
            id, ComponentName(context, PhotoWidgetReceiver::class.java)))
        val uri = addPhoto(context)
        val dao = WidgetDatabase.get(context).widgets()
        val configured = runBlocking { WidgetController(context).configure(WidgetRecord(
            appWidgetId = id, displayMode = displayMode, rotationMinutes = rotationMinutes,
            currentUri = null,
        )) }
        assertTrue("Fixture widget must configure with its photo", configured)
        return Fixture(context, host, id, uri, dao)
    }

    private fun addPhoto(context: android.content.Context): android.net.Uri {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "management-ui-${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ManagementUiTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        resolver.openOutputStream(uri, "w")!!.use { output ->
            val bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(0xFF526E61.toInt()); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
            finally { bitmap.recycle() }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun finishConfigurationIfOpen() {
        runCatching {
            composeRule.onNodeWithText("Cancel").performClick()
        }
    }

    private data class Fixture(
        val context: android.content.Context, val host: AppWidgetHost, val id: Int,
        val photo: android.net.Uri, val dao: dev.localgallery.data.WidgetDao,
    ) {
        fun close() {
            runCatching { runBlocking { WidgetController(context).delete(id) } }
            runCatching { host.deleteAppWidgetId(id) }
            runCatching { context.contentResolver.delete(photo, null, null) }
        }
    }

    private companion object { const val HOST_ID = 8671 }
}
