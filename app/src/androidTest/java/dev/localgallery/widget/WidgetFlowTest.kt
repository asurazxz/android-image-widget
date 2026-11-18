package dev.localgallery.widget

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localgallery.MainActivity
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.media.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WidgetFlowTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        },
    )

    @Test
    fun boundWidgetOpensPreviewShufflesAndResizesWithoutAdvancingOnResize() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "appwidget grantbind --package ${context.packageName} --user 0",
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }

        val host = AppWidgetHost(context, 8615)
        val hostId = host.allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(context)
        val hostScenario = ActivityScenario.launch(MainActivity::class.java)
        val resolver = context.contentResolver
        val fixtureUris = mutableListOf<android.net.Uri>()
        val dao = WidgetDatabase.get(context).widgets()
        var hostStarted = false
        var controller: WidgetController? = null
        var hostedView: AppWidgetHostView? = null

        try {
            val bound = manager.bindAppWidgetIdIfAllowed(
                hostId,
                ComponentName(context, PhotoWidgetReceiver::class.java),
                Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
                },
            )
            assertTrue("The test host must bind the provider", bound)

            hostScenario.onActivity { activity ->
                host.startListening()
                hostStarted = true
                val info = manager.getAppWidgetInfo(hostId)
                assertNotNull("Bound widget should have provider info", info)
                hostedView = host.createView(activity, hostId, info!!)
                activity.setContentView(hostedView, FrameLayout.LayoutParams(dp(context, 200), dp(context, 200)))
            }
            awaitHostBounds(hostScenario, hostedView!!, dp(context, 200), dp(context, 200))

            val path = "Pictures/WidgetFlow${System.currentTimeMillis()}"
            val fixtureColorsByMediaId = mutableMapOf<String, Int>()
            val fixtureColors = mutableMapOf<String, Int>()
            (1..3).forEach { index ->
                val uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "fixture-$index.png")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, path)
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                )!!
                resolver.openOutputStream(uri)!!.use { stream ->
                    val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                    try {
                        val color = intArrayOf(0xFF224433.toInt(), 0xFF336644.toInt(), 0xFF447755.toInt())[index - 1]
                        bitmap.eraseColor(color)
                        fixtureColorsByMediaId[uri.lastPathSegment!!] = color
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    } finally {
                        bitmap.recycle()
                    }
                }
                resolver.update(uri, ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                fixtureUris += uri
            }

            val widgetController = WidgetController(context)
            controller = widgetController
            val mediaImages = MediaRepository(context).images()
            mediaImages.filter { it.uri.lastPathSegment in fixtureColorsByMediaId }.forEach { media ->
                fixtureColors[media.key] = fixtureColorsByMediaId.getValue(media.uri.lastPathSegment!!)
            }
            val image = mediaImages.first {
                it.uri.lastPathSegment == fixtureUris.first().lastPathSegment
            }
            assertTrue(
                widgetController.configure(
                    WidgetRecord(
                        appWidgetId = hostId,
                        entireGallery = false,
                        selectedAlbums = listOf("${image.volume}:${image.bucketId}"),
                    ),
                ),
            )

            // Confirm that Glance rendered a real photo ImageView, not only placeholder text.
            var renderedRecord = awaitRecord(dao, hostId)
            awaitRenderedPhoto(hostScenario, renderedRecord.currentUri!!, fixtureColors, ImageView.ScaleType.CENTER_CROP,
                dao, hostId, "initial configure")
            assertNoImageView(hostScenario, "Next random photo")
            // The photo itself opens the Compose preview. Shuffle must update the widget
            // selection while leaving that preview activity on screen.
            val beforePreview = awaitRecord(dao, hostId)
            val previewImage = awaitImageView(hostScenario, "Open photo preview")
            val previewTap = tapWidgetView(instrumentation, hostScenario, previewImage)
            try {
                composeRule.waitUntil(WAIT_TIMEOUT_MS) {
                    previewReady()
                }
            } catch (timeout: Throwable) {
                throw AssertionError("Photo tap did not open the preview; $previewTap", timeout)
            }
            composeRule.onNodeWithContentDescription("Photo preview").assertExists()
            composeRule.onNodeWithText("Shuffle").assertIsEnabled()
            composeRule.onNodeWithText("Open original").assertIsEnabled()
            awaitPreviewColor(fixtureColors.getValue(beforePreview.currentUri!!), "initial preview")
            composeRule.onNodeWithText("Shuffle").performClick()
            val shuffledInPreview = awaitRecordChange(dao, hostId, beforePreview.currentUri!!, beforePreview.revision)
            composeRule.waitUntil(WAIT_TIMEOUT_MS) { previewReady() }
            composeRule.onNodeWithText("Shuffle").assertIsEnabled()
            composeRule.onNodeWithText("Open original").assertIsEnabled()
            assertNotNull(shuffledInPreview.currentUri)
            awaitPreviewColor(fixtureColors.getValue(shuffledInPreview.currentUri!!), "after preview shuffle")
            renderedRecord = shuffledInPreview
            awaitRenderedPhoto(hostScenario, renderedRecord.currentUri!!, fixtureColors, ImageView.ScaleType.CENTER_CROP,
                dao, hostId, "after preview shuffle")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            awaitImageView(hostScenario, "Open photo preview")

            val beforeRefresh = awaitRecord(dao, hostId)
            widgetController.refresh(hostId)
            val refreshed = awaitRecord(dao, hostId)
            awaitRenderedPhoto(hostScenario, refreshed.currentUri!!, fixtureColors, ImageView.ScaleType.CENTER_CROP,
                dao, hostId, "after refresh")
            assertEquals(beforeRefresh.currentUri, refreshed.currentUri)

            coroutineScope { repeat(6) { launch { widgetController.advance(hostId) } } }
            val afterRapid = awaitRecord(dao, hostId)
            assertEquals(3, afterRapid.seen.size + afterRapid.remaining.size)
            assertNotNull(afterRapid.currentUri)

            val fitBackground = FitBackgroundColors.CREAM.argb
            assertTrue(widgetController.configure(afterRapid.copy(displayMode = "FIT", fitBackgroundColor = fitBackground)))
            val fitRecord = awaitRecord(dao, hostId)
            assertEquals(afterRapid.currentUri, fitRecord.currentUri)
            awaitRenderedPhoto(hostScenario, fitRecord.currentUri!!, fixtureColors, ImageView.ScaleType.FIT_CENTER,
                dao, hostId, "after Fit configuration")
            assertEquals(fitBackground, fitRecord.fitBackgroundColor)
            hostScenario.onActivity {
                val view = requireNotNull(hostedView)
                val rendered = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(android.graphics.Canvas(rendered))
                    assertEquals("Fit displays the configured background above a wide photo",
                        fitBackground, rendered.getPixel(rendered.width / 2, rendered.height / 16))
                } finally {
                    rendered.recycle()
                }
            }
            val cache = context.cacheDir.resolve("widget-previews").listFiles().orEmpty()
                .filter { it.name.startsWith("$hostId-") }
            assertTrue("Per-widget preview cache should be bounded", cache.size <= 1)

            val beforeResize = awaitRecord(dao, hostId)
            manager.updateAppWidgetOptions(
                hostId,
                Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 460)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 340)
                },
            )
            val oldBounds = awaitHostBounds(hostScenario, hostedView!!, dp(context, 200), dp(context, 200))
            hostScenario.onActivity {
                hostedView!!.layoutParams = FrameLayout.LayoutParams(dp(context, 260), dp(context, 180))
                hostedView!!.requestLayout()
            }
            val resizedBounds = awaitHostBounds(hostScenario, hostedView!!, dp(context, 260), dp(context, 180))
            assertTrue("The host view must physically resize", oldBounds != resizedBounds)
            widgetController.refresh(hostId)
            val afterResize = awaitRecord(dao, hostId)
            awaitRenderedPhoto(hostScenario, afterResize.currentUri!!, fixtureColors, ImageView.ScaleType.FIT_CENTER,
                dao, hostId, "after resize refresh")
            assertEquals(beforeResize.currentUri, afterResize.currentUri)
            assertEquals(beforeResize.remaining, afterResize.remaining)
            assertEquals(beforeResize.seen, afterResize.seen)
            assertEquals(beforeResize.cycle, afterResize.cycle)

            suspend fun verifyResize(widthDp: Int, heightDp: Int, label: String) {
                manager.updateAppWidgetOptions(hostId, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                })
                hostScenario.onActivity {
                    hostedView!!.layoutParams = FrameLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp))
                    hostedView!!.requestLayout()
                }
                awaitHostBounds(hostScenario, hostedView!!, dp(context, widthDp), dp(context, heightDp))
                widgetController.refresh(hostId)
                val resized = awaitRecord(dao, hostId)
                assertEquals("$label resize does not advance the current photo", afterResize.currentUri, resized.currentUri)
                assertEquals("$label resize preserves pending order", afterResize.remaining, resized.remaining)
                assertEquals("$label resize preserves seen history", afterResize.seen, resized.seen)
                assertEquals("$label resize preserves shuffle cycle", afterResize.cycle, resized.cycle)

                val photo = awaitRenderedPhoto(hostScenario, resized.currentUri!!, fixtureColors,
                    ImageView.ScaleType.FIT_CENTER, dao, hostId, "after $label resize")
                val bitmap = (photo.drawable as? BitmapDrawable)?.bitmap
                assertNotNull("$label resize should use a decoded photo bitmap", bitmap)
                val actualBitmap = requireNotNull(bitmap)
                assertTrue("$label photo bitmap width should be bounded", actualBitmap.width in 1..1440)
                assertTrue("$label photo bitmap height should be bounded", actualBitmap.height in 1..1440)
                assertTrue("$label photo bitmap should stay within the one-megapixel preview budget",
                    actualBitmap.width.toLong() * actualBitmap.height <= 1_000_000L)

                val previewTarget = awaitImageView(hostScenario, "Open photo preview")
                hostScenario.onActivity {
                    assertTrue("$label photo preview target should remain usable", previewTarget.width >= dp(context, 48) && previewTarget.height >= dp(context, 48))
                }
            }

            verifyResize(110, 110, "minimum-size")
            verifyResize(320, 420, "large portrait")

            // Restore the original bounds and options before screenshot and viewer interaction.
            manager.updateAppWidgetOptions(hostId, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 460)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 340)
            })
            hostScenario.onActivity {
                hostedView!!.layoutParams = FrameLayout.LayoutParams(dp(context, 260), dp(context, 180))
                hostedView!!.requestLayout()
            }
            awaitHostBounds(hostScenario, hostedView!!, dp(context, 260), dp(context, 180))
            widgetController.refresh(hostId)
            val restoredBoundsRecord = awaitRecord(dao, hostId)
            assertEquals(afterResize.currentUri, restoredBoundsRecord.currentUri)
            assertEquals(afterResize.remaining, restoredBoundsRecord.remaining)
            assertEquals(afterResize.seen, restoredBoundsRecord.seen)
            assertEquals(afterResize.cycle, restoredBoundsRecord.cycle)
            awaitRenderedPhoto(hostScenario, restoredBoundsRecord.currentUri!!, fixtureColors, ImageView.ScaleType.FIT_CENTER,
                dao, hostId, "after restoring original bounds")
            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    try {
                        context.filesDir.resolve("m3-widget-fit.png").outputStream().use { stream ->
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                    } finally {
                        screenshot.recycle()
                    }
                }
            }

            // The home-screen photo action must open the URI from the displayed frame,
            // even if the persisted selection changes before OpenPhotoActivity runs.
            val displayedUri = afterResize.currentUri!!
            val outgoing = java.util.concurrent.atomic.AtomicReference<Intent?>()
            val viewerMonitor = object : android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                    if (intent.action == Intent.ACTION_VIEW) {
                        outgoing.set(Intent(intent))
                        return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null)
                    }
                    return null
                }
            }
            instrumentation.addMonitor(viewerMonitor)
            try {
                awaitRenderedPhoto(hostScenario, displayedUri, fixtureColors, ImageView.ScaleType.FIT_CENTER,
                    dao, hostId, "before photo tap")
                val alternateUri = fixtureColors.keys.first { it != displayedUri }
                // Simulate selection changing after Glance produced its PendingIntent but before
                // OpenPhotoActivity reads the database. The captured frame must still win.
                dao.put(afterResize.copy(
                    currentUri = alternateUri,
                    currentMime = "image/png",
                    revision = afterResize.revision + 1,
                ))
                val photoView = awaitImageView(hostScenario, "Open photo preview")
                val tapDetails = tapWidgetView(instrumentation, hostScenario, photoView)
                try {
                    composeRule.waitUntil(WAIT_TIMEOUT_MS) {
                        previewReady()
                    }
                } catch (timeout: Throwable) {
                    throw AssertionError("Physical photo tap did not open the preview; $tapDetails", timeout)
                }
                composeRule.onNodeWithContentDescription("Photo preview").assertExists()
                composeRule.onNodeWithText("Open original").assertIsEnabled()
                awaitPreviewColor(fixtureColors.getValue(displayedUri), "captured preview before open")
                composeRule.onNodeWithText("Open original").performClick()
                try {
                    withTimeout(WAIT_TIMEOUT_MS) {
                        while (outgoing.get() == null) delay(POLL_INTERVAL_MS)
                    }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    throw AssertionError("Open original did not dispatch ACTION_VIEW; $tapDetails", timeout)
                }
                val opened = outgoing.get()!!
                assertEquals(Intent.ACTION_VIEW, opened.action)
                assertEquals(android.net.Uri.parse(displayedUri), opened.data)
                assertTrue(opened.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(android.net.Uri.parse(displayedUri), opened.clipData?.getItemAt(0)?.uri)
            } finally {
                instrumentation.removeMonitor(viewerMonitor)
                dao.put(afterResize)
            }
        } finally {
            controller?.delete(hostId)
            host.deleteAppWidgetId(hostId)
            fixtureUris.forEach { resolver.delete(it, null, null) }
            try {
                if (hostStarted) hostScenario.onActivity { host.stopListening() }
            } finally {
                hostScenario.close()
            }
        }
    }

    private suspend fun awaitRecord(
        dao: dev.localgallery.data.WidgetDao,
        id: Int,
    ): WidgetRecord = withTimeout(WAIT_TIMEOUT_MS) {
        while (true) {
            val record = withContext(Dispatchers.IO) { dao.get(id) }
            if (record != null) return@withTimeout record
            delay(POLL_INTERVAL_MS)
        }
        error("Unreachable")
    }

    private suspend fun awaitRecordChange(
        dao: dev.localgallery.data.WidgetDao,
        id: Int,
        previousUri: String,
        previousRevision: Long,
    ): WidgetRecord = withTimeout(WAIT_TIMEOUT_MS) {
        while (true) {
            val record = withContext(Dispatchers.IO) { dao.get(id) }
            if (record != null && record.revision != previousRevision && record.currentUri != previousUri) {
                return@withTimeout record
            }
            delay(POLL_INTERVAL_MS)
        }
        error("Unreachable")
    }

    private suspend fun awaitImageView(
        scenario: ActivityScenario<MainActivity>,
        description: String,
    ): ImageView = withTimeout(WAIT_TIMEOUT_MS) {
        while (true) {
            var found: ImageView? = null
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                found = findImageView(root, description)
            }
            if (found != null) return@withTimeout found!!
            delay(POLL_INTERVAL_MS)
        }
        error("Unreachable")
    }

    private fun previewReady(): Boolean =
        composeRule.onAllNodes(hasContentDescription("Photo preview")).fetchSemanticsNodes().isNotEmpty() &&
            composeRule.onAllNodes(hasText("Shuffle") and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
            composeRule.onAllNodes(hasText("Open original") and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun awaitPreviewColor(expectedColor: Int, stage: String) {
        try {
            composeRule.waitUntil(WAIT_TIMEOUT_MS) {
                runCatching {
                    val image = composeRule.onNodeWithContentDescription("Photo preview").captureToImage()
                    image.toPixelMap()[image.width / 2, image.height / 2].toArgb() == expectedColor
                }.getOrDefault(false)
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Preview image did not show the expected fixture color $stage", timeout)
        }
    }

    private fun assertNoImageView(scenario: ActivityScenario<MainActivity>, description: String) {
        scenario.onActivity { activity ->
            assertNull(
                "Widget must not expose an old shuffle image overlay ($description)",
                findImageView(activity.findViewById(android.R.id.content), description),
            )
        }
    }

    private suspend fun awaitRenderedPhoto(
        scenario: ActivityScenario<MainActivity>,
        uri: String,
        fixtureColors: Map<String, Int>,
        scaleType: ImageView.ScaleType,
        dao: dev.localgallery.data.WidgetDao,
        widgetId: Int,
        stage: String = "render",
    ): ImageView {
        val expected = fixtureColors[uri] ?: error("Expected URI is not a test fixture")
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val expectedScale = if (legacy) ImageView.ScaleType.FIT_XY else scaleType
        var diagnostics = "No matching ImageView observed"
        return try {
            withTimeout<ImageView>(WAIT_TIMEOUT_MS) {
                while (true) {
                    var found: ImageView? = null
                    scenario.onActivity { activity ->
                        val candidate = findImageView(activity.findViewById(android.R.id.content), "Open photo preview")
                        val drawable = candidate?.drawable as? BitmapDrawable
                        val bitmap = drawable?.bitmap
                        diagnostics = if (candidate == null) "photo ImageView absent"
                        else "scaleType=${candidate.scaleType}, expectedScaleType=$expectedScale, drawable=${candidate.drawable?.javaClass?.simpleName}, " +
                            "bitmap=${bitmap?.width}x${bitmap?.height}, centerPixel=${bitmap?.let { "#%08X".format(it.getPixel(it.width / 2, it.height / 2)) }}, " +
                            "expectedUri=$uri, expectedPixel=#%08X".format(expected)
                        if (candidate != null && candidate.scaleType == expectedScale && bitmap != null && bitmap.width > 0 &&
                            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == expected &&
                            (!legacy || android.graphics.Color.alpha(bitmap.getPixel(0, 0)) == 0)) found = candidate
                    }
                    if (found != null) return@withTimeout found!!
                    delay(POLL_INTERVAL_MS)
                }
                error("Unreachable")
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val stored = withContext(Dispatchers.IO) { dao.get(widgetId) }
            throw AssertionError(
                "Timed out at stage '$stage' waiting for rendered photo uri=$uri scaleType=$scaleType; " +
                    "stored uri=${stored?.currentUri} revision=${stored?.revision}; last observed: $diagnostics",
                timeout,
            )
        }
    }

    private suspend fun awaitHostBounds(
        scenario: ActivityScenario<MainActivity>,
        view: AppWidgetHostView,
        widthPx: Int,
        heightPx: Int,
    ): Pair<Int, Int> = withTimeout(WAIT_TIMEOUT_MS) {
        while (true) {
            var bounds: Pair<Int, Int>? = null
            scenario.onActivity {
                bounds = view.width to view.height
            }
            if (bounds == (widthPx to heightPx)) return@withTimeout bounds!!
            delay(POLL_INTERVAL_MS)
        }
        error("Unreachable")
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun tapWidgetView(
        instrumentation: Instrumentation,
        scenario: ActivityScenario<MainActivity>,
        target: View,
    ): String {
        var x = 0f
        var y = 0f
        var details = ""
        scenario.onActivity {
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            x = location[0] + target.width / 2f
            y = location[1] + target.height / 2f
            val ancestors = generateSequence(target as View?) { view -> view.parent as? View }
                .joinToString(" -> ") { "${it.javaClass.simpleName}(clickable=${it.isClickable},bounds=${it.width}x${it.height})" }
            details = "target=${target.javaClass.simpleName}, shown=${target.isShown}, enabled=${target.isEnabled}, " +
                "bounds=${target.width}x${target.height} at ($x,$y), ancestors=[$ancestors]"
            assertTrue("Tap target must be shown and laid out: $details", target.isShown && target.width > 0 && target.height > 0)
        }

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, x, y, 0)
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN)
        try {
            assertTrue("Touch DOWN injection rejected: $details", instrumentation.uiAutomation.injectInputEvent(down, true))
            assertTrue("Touch UP injection rejected: $details", instrumentation.uiAutomation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
        return details
    }

    private fun findImageView(root: View, description: String): ImageView? {
        if (root is ImageView && root.contentDescription == description) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findImageView(root.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 150L
    }
}
