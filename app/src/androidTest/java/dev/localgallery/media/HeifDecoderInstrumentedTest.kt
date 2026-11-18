package dev.localgallery.media

import android.Manifest
import android.content.ContentValues
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeifDecoderInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*runtimePermissions())

    @Test
    fun supportedHeifFixtureDecodesWithinPreviewBounds() = runBlocking {
        val supportsHeif = ImageDecoder.isMimeTypeSupported("image/heic") ||
            ImageDecoder.isMimeTypeSupported("image/heif")
        assumeTrue("This device's ImageDecoder does not support HEIF", supportsHeif)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val fixture = instrumentation.context.assets.open("heifwriter_input.heic").use { it.readBytes() }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "decoder-heif-${System.currentTimeMillis()}.heic")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/heic")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LocalGalleryHeifTest/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("MediaStore refused the app-owned HEIF fixture")

        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(fixture) }
            check(resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            ) == 1) { "MediaStore did not publish the HEIF fixture" }

            val bitmap = ImagePreviewDecoder(context).decode(uri, 320, 320)
            assertNotNull("Supported HEIF content should decode", bitmap)
            bitmap!!.useBitmap { preview ->
                assertTrue("Decoded width must be positive", preview.width > 0)
                assertTrue("Decoded height must be positive", preview.height > 0)
                assertTrue("Decoded width must stay within bounds", preview.width <= 320)
                assertTrue("Decoded height must stay within bounds", preview.height <= 320)
                assertTrue("Decoded preview must contain pixels", preview.width.toLong() * preview.height > 0)
            }
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private inline fun <R> android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> R): R = try {
        block(this)
    } finally {
        recycle()
    }

    private companion object {
        fun runtimePermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
