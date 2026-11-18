package dev.localgallery.widget

import androidx.test.platform.app.InstrumentationRegistry
import dev.localgallery.media.MediaRepository
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import androidx.test.core.app.ActivityScenario
import dev.localgallery.MainActivity
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.glance.appwidget.GlanceAppWidgetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Run separately after configuring permissions through adb, avoiding self-revocation. */
class PermissionObservationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test fun observesActualOsPermissionAndGatesGallery() = runBlocking {
        val expected = InstrumentationRegistry.getArguments().getString("expectedPhotoAccess")
        assumeNotNull(expected)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val access = PhotoAccess.valueOf(expected!!)
        assertEquals(access, PhotoPermissionManager(context).status())
        if (access != PhotoAccess.FULL) {
            try {
                MediaRepository(context).images()
                throw AssertionError("Gallery must reject non-full access")
            } catch (_: SecurityException) { /* expected */ }
            // A stale widget row must not keep a private photo preview after app resume.
            // No host is bound, so this cannot pass because of an unrelated Glance update.
            val staleId = 998877
            val dao = WidgetDatabase.get(context).widgets()
            val cached = context.cacheDir.resolve("widget-previews/$staleId-regression.png")
            dao.put(WidgetRecord(staleId, currentUri = "content://media/external_primary/images/media/0"))
            cached.parentFile!!.mkdirs()
            cached.writeBytes(byteArrayOf(1, 2, 3))
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    withTimeout(30_000) {
                        while (cached.exists() || dao.get(staleId) != null) delay(100)
                    }
                }
            } finally { dao.delete(staleId); cached.delete() }
        }
    }

    @Test fun configurationResumeClearsBoundWidgetPreviewWhenAccessIsNotFull() = runBlocking {
        val expected = InstrumentationRegistry.getArguments().getString("expectedPhotoAccess")
        assumeNotNull(expected)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val access = PhotoAccess.valueOf(expected!!)
        assumeTrue(access != PhotoAccess.FULL)
        assertEquals("Test permission must be set externally before launch", access,
            PhotoPermissionManager(context).status())

        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val host = AppWidgetHost(context, HOST_ID)
        val id = host.allocateAppWidgetId()
        val bound = AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(
            id, ComponentName(context, PhotoWidgetReceiver::class.java),
        )
        if (!bound) {
            host.deleteAppWidgetId(id)
            throw AssertionError("The test host must be allowed to bind the photo widget")
        }

        val dao = WidgetDatabase.get(context).widgets()
        val cached = context.cacheDir.resolve("widget-previews/$id-regression.png")
        var hostScenario: ActivityScenario<MainActivity>? = null
        var scenario: ActivityScenario<PhotoWidgetConfigurationActivity>? = null
        try {
            host.startListening()
            hostScenario = ActivityScenario.launch(MainActivity::class.java)
            var hostView: AppWidgetHostView? = null
            hostScenario.onActivity { activity ->
                val info = AppWidgetManager.getInstance(activity).getAppWidgetInfo(id)
                    ?: throw AssertionError("Bound test widget must have provider metadata")
                hostView = host.createView(activity, id, info)
            }
            PhotoWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(id))
            val compact = context.resources.configuration.fontScale >= 1.5f
            val visibleLabel = context.getString(
                if (compact) R.string.widget_allow_access_short else R.string.widget_allow_full_access,
            )
            composeRule.waitUntil(timeoutMillis = 15_000) {
                hasHostText(hostView, visibleLabel) && (!compact || hasHostText(
                    hostView, context.getString(R.string.widget_allow_full_access),
                    View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION,
                ))
            }
            if (compact) assertRecoveryFitsMinimumSize(requireNotNull(hostView), visibleLabel)

            // The main-app resume and initial bound-widget rendering both completed before
            // seeding. Neither can accidentally delete the sentinel used by the config case.
            dao.put(WidgetRecord(id, currentUri = "content://media/external_primary/images/media/0"))
            cached.parentFile!!.mkdirs()
            cached.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue("Fixture preview must exist before configuration resumes", cached.exists())

            scenario = ActivityScenario.launch(
                Intent(context, PhotoWidgetConfigurationActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
            )
            // This UI state proves the configuration screen has resumed and observed the
            // externally configured permission before checking the preview cleanup.
            composeRule.onNode(hasScrollAction()).performScrollToNode(
                androidx.compose.ui.test.hasText("Grant full photo access"),
            )
            composeRule.onNodeWithText("Grant full photo access").assertExists()
            composeRule.waitUntil(timeoutMillis = 5_000) { !cached.exists() }
            assertFalse("Configuration resume must clear the cached photo preview", cached.exists())
        } finally {
            scenario?.close()
            hostScenario?.close()
            host.stopListening()
            dao.delete(id)
            cached.delete()
            host.deleteAppWidgetId(id)
        }
    }

    private companion object {
        const val HOST_ID = 8727
    }

    private fun assertRecoveryFitsMinimumSize(hostView: AppWidgetHostView, label: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val size = (110 * hostView.resources.displayMetrics.density).roundToInt()
            val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
            hostView.measure(spec, spec)
            hostView.layout(0, 0, size, size)
            val matches = ArrayList<View>()
            hostView.findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT)
            val textView = matches.filterIsInstance<TextView>().single()
            val layout = requireNotNull(textView.layout)
            val lastLine = layout.lineCount - 1
            var bottom = textView.compoundPaddingTop + layout.getLineBottom(lastLine)
            var view: View = textView
            while (view !== hostView) {
                bottom += view.top
                view = view.parent as View
            }
            assertEquals("Recovery action fits without truncation", 0, layout.getEllipsisCount(lastLine))
            assertTrue("Recovery text fits inside 110 dp ($bottom px through ${layout.lineCount} lines, limit $size px)",
                bottom <= size)
        }
    }

    private fun hasHostText(
        hostView: AppWidgetHostView?, text: String, match: Int = View.FIND_VIEWS_WITH_TEXT,
    ): Boolean {
        val found = java.util.ArrayList<View>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            hostView?.findViewsWithText(found, text, match)
        }
        return found.isNotEmpty()
    }
}
