package dev.localgallery.media

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localgallery.data.WidgetRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class GalleryLauncherTest {
    @get:Rule
    val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
    )

    @Test
    fun defaultViewerIntentOpensOriginalUriWithReadGrantAndClipData() {
        val uri = Uri.parse("content://media/primary/images/media/73")
        val record = WidgetRecord(
            appWidgetId = 8,
            preferredViewer = "viewer.preferred",
            currentUri = uri.toString(),
            currentMime = "image/jpeg",
        )

        val intent = GalleryLauncher(androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext)
            .intentFor(record, preferred = false)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("image/jpeg", intent.type)
        assertNull("Fallback resolution must allow Android to select a compatible viewer", intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun configuredViewerIntentIsScopedToPreferredPackage() {
        val record = WidgetRecord(
            appWidgetId = 9,
            preferredViewer = "viewer.preferred",
            currentUri = "content://media/primary/images/media/91",
            currentMime = null,
        )

        val intent = GalleryLauncher(androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext)
            .intentFor(record, preferred = true)

        assertEquals("viewer.preferred", intent.`package`)
        assertEquals("image/*", intent.type)
    }

    @Test
    fun openDispatchesOriginalPhotoIntentWithUriGrantToAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "gallery-launch-${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GalleryLauncherTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        val capturedIntent = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                capturedIntent.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }

        try {
            resolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xFF806040.toInt())
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                } finally {
                    bitmap.recycle()
                }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)

            instrumentation.addMonitor(monitor)
            val opened = GalleryLauncher(context).open(
                WidgetRecord(
                    appWidgetId = 73,
                    currentUri = uri.toString(),
                    currentMime = "image/jpeg",
                ),
            )

            assertTrue("A readable photo with a compatible view dispatch should open", opened)
            val intent = capturedIntent.get()
            assertNotNull("The ACTION_VIEW launch should be intercepted", intent)
            assertEquals(Intent.ACTION_VIEW, intent!!.action)
            assertEquals(uri, intent.data)
            assertEquals("image/jpeg", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        } finally {
            instrumentation.removeMonitor(monitor)
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun unavailablePreferredViewerFallsBackToSystemResolution() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "gallery-fallback-${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GalleryLauncherTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        val attempts = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                attempts += Intent(intent)
                // Let the deliberately missing preferred package fail normal resolution.
                // Intercept the subsequent unscoped fallback so the test stays device independent.
                return if (attempts.size == 1) null
                else Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }

        try {
            resolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xFF806040.toInt())
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                } finally {
                    bitmap.recycle()
                }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            instrumentation.addMonitor(monitor)

            val opened = GalleryLauncher(context).open(
                WidgetRecord(
                    appWidgetId = 74,
                    preferredViewer = "dev.localgallery.test.no.such.viewer",
                    currentUri = uri.toString(),
                    currentMime = "image/jpeg",
                ),
            )

            assertTrue("An unavailable preferred viewer should fall back to ACTION_VIEW resolution", opened)
            assertEquals("Preferred launch should be attempted, then unscoped fallback", 2, attempts.size)
            assertEquals("dev.localgallery.test.no.such.viewer", attempts[0].`package`)
            assertNull("Fallback must let Android resolve a compatible viewer", attempts[1].`package`)
            assertEquals(uri, attempts[1].data)
        } finally {
            instrumentation.removeMonitor(monitor)
            resolver.delete(uri, null, null)
        }
    }
}
