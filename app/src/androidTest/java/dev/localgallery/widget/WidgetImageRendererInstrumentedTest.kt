package dev.localgallery.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
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
    fun oversizedAndCorruptCacheEntriesAreDeletedBeforeMissingSourceReturnsNull() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val widgetId = 987_654
        val revision = 4L
        val (width, height) = previewSize(context, widgetId)
        val cacheDirectory = File(context.cacheDir, "widget-previews").apply { mkdirs() }
        val target = File(cacheDirectory, "$widgetId-$revision-$width-$height.png")
        val renderer = WidgetImageRenderer(context)
        val missingUri = Uri.withAppendedPath(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Long.MAX_VALUE.toString(),
        ).toString()

        try {
            Bitmap.createBitmap(width + 1, height + 1, Bitmap.Config.ARGB_8888).useBitmap { bitmap ->
                target.outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            }

            assertNull(renderer.prepare(widgetId, missingUri, revision))
            assertFalse("Oversized cache should be deleted", target.exists())

            target.writeBytes(byteArrayOf(0x13, 0x37, 0x00, 0x55))
            assertNull(renderer.prepare(widgetId, missingUri, revision))
            assertFalse("Corrupt cache should be deleted", target.exists())
        } finally {
            renderer.clear(widgetId)
        }
    }

    private fun previewSize(context: Context, widgetId: Int): Pair<Int, Int> {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 240) * density)
            .toInt().coerceIn(128, 640)
        val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 240) * density)
            .toInt().coerceIn(128, 640)
        return width to height
    }

    private inline fun <R> Bitmap.useBitmap(block: (Bitmap) -> R): R = try {
        block(this)
    } finally {
        recycle()
    }
}
