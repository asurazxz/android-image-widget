package dev.localgallery.widget

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetImageRendererInstrumentedTest {
    @get:Rule
    val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
    )

    @Test
    fun cacheTracksRenderModeAndRejectsInvalidEntriesWhenSourceDisappears() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val widgetId = 987_654
        val revision = 4L
        val size = IntSize(300, 300)
        val renderer = WidgetImageRenderer(context)
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "widget-cache-${System.nanoTime()}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LocalGalleryDecoderTest")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ))

        renderer.clear(widgetId)
        var sourceDeleted = false
        try {
            Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
                requireNotNull(resolver.openOutputStream(uri)).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val source = uri.toString()
            requireNotNull(renderer.prepare(widgetId, source, revision, fit = true, size = size)).useBitmap { fit ->
                assertEquals(300, fit.width)
                assertEquals(150, fit.height)
            }
            requireNotNull(renderer.prepare(widgetId, source, revision, size = size)).useBitmap { crop ->
                assertEquals(300, crop.width)
                assertEquals(300, crop.height)
            }
            val target = File(context.cacheDir, "widget-previews").listFiles().orEmpty()
                .single { it.name.startsWith("$widgetId-") }

            assertEquals(1, resolver.delete(uri, null, null))
            sourceDeleted = true
            Bitmap.createBitmap(size.width + 1, size.height + 1, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
                target.outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            }

            assertNull(renderer.prepare(widgetId, source, revision, size = size))
            assertFalse("Oversized cache should be deleted", target.exists())

            target.writeBytes(byteArrayOf(0x13, 0x37, 0x00, 0x55))
            assertNull(renderer.prepare(widgetId, source, revision, size = size))
            assertFalse("Corrupt cache should be deleted", target.exists())
        } finally {
            renderer.clear(widgetId)
            if (!sourceDeleted) resolver.delete(uri, null, null)
        }
    }

    private inline fun <R> Bitmap.useBitmap(block: (Bitmap) -> R): R = try {
        block(this)
    } finally {
        recycle()
    }
}
