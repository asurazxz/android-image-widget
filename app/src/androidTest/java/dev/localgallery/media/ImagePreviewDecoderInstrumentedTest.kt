package dev.localgallery.media

// Decode fixtures are synthetic and owned by the test app.

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePreviewDecoderInstrumentedTest {
    @get:Rule
    val permissions = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        },
    )

    @Test
    fun landscapeAndPortraitKeepAspectRatioWithinRequestedBounds() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val landscape = addImage(context, fixtures, "landscape.jpg", "image/jpeg", 800, 400, Bitmap.CompressFormat.JPEG)
            val portrait = addImage(context, fixtures, "portrait.jpg", "image/jpeg", 400, 800, Bitmap.CompressFormat.JPEG)

            val landscapeBitmap = decoder.decode(landscape, 400, 400)
            val portraitBitmap = decoder.decode(portrait, 400, 400)

            assertNotNull(landscapeBitmap)
            assertNotNull(portraitBitmap)
            assertEquals(400, landscapeBitmap!!.width)
            assertEquals(200, landscapeBitmap.height)
            assertEquals(200, portraitBitmap!!.width)
            assertEquals(400, portraitBitmap.height)
            landscapeBitmap.recycle()
            portraitBitmap.recycle()
        }
    }

    @Test
    fun exifQuarterTurnIsAppliedToDecodedDimensions() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val uri = addImage(context, fixtures, "rotated.jpg", "image/jpeg", 800, 400, Bitmap.CompressFormat.JPEG)
            context.contentResolver.openFileDescriptor(uri, "rw")!!.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                    saveAttributes()
                }
            }

            val bitmap = decoder.decode(uri, 400, 400)

            assertNotNull(bitmap)
            assertEquals(200, bitmap!!.width)
            assertEquals(400, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test
    fun highResolutionDecodeRespectsSizeAndPixelBudgets() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val uri = addImage(context, fixtures, "large.jpg", "image/jpeg", 4096, 2048, Bitmap.CompressFormat.JPEG)

            val bitmap = decoder.decode(uri, 640, 640)

            assertNotNull(bitmap)
            assertTrue(bitmap!!.width <= 640)
            assertTrue(bitmap.height <= 640)
            assertTrue(bitmap.width.toLong() * bitmap.height.toLong() <= 1_000_000L)
            bitmap.recycle()
        }
    }

    @Test
    fun cropKeepsEnoughSourcePixelsToFillASquareWidget() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val uri = addImage(context, fixtures, "wide.jpg", "image/jpeg", 1600, 800, Bitmap.CompressFormat.JPEG)

            val cropped = requireNotNull(decoder.decode(uri, 700, 700, crop = true))
            try {
                assertEquals(700, cropped.width)
                assertEquals(700, cropped.height)
            } finally {
                cropped.recycle()
            }
        }
    }

    @Test
    fun screenPreviewKeepsMoreThanOneMegapixelWhenItFits() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val uri = addImage(context, fixtures, "screen.jpg", "image/jpeg", 1400, 1800, Bitmap.CompressFormat.JPEG)

            val preview = requireNotNull(decoder.decodeForScreen(uri, 1400, 1800))
            try {
                assertEquals(1400, preview.width)
                assertEquals(1800, preview.height)
            } finally {
                preview.recycle()
            }
        }
    }

    @Test
    fun extremeAspectRatiosStayInsideBoundsWithoutStretching() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val panorama = addImage(context, fixtures, "panorama.png", "image/png", 4096, 32, Bitmap.CompressFormat.PNG)
            val tall = addImage(context, fixtures, "tall.png", "image/png", 32, 4096, Bitmap.CompressFormat.PNG)

            val panoramaBitmap = decoder.decode(panorama, 640, 640)
            val tallBitmap = decoder.decode(tall, 640, 640)

            assertNotNull(panoramaBitmap)
            assertNotNull(tallBitmap)
            assertTrue(panoramaBitmap!!.width <= 640 && panoramaBitmap.height <= 640)
            assertTrue(tallBitmap!!.width <= 640 && tallBitmap.height <= 640)
            assertTrue(kotlin.math.abs(panoramaBitmap.width.toDouble() / panoramaBitmap.height - 128.0) < 0.1)
            assertTrue(kotlin.math.abs(tallBitmap.height.toDouble() / tallBitmap.width - 128.0) < 0.1)
            panoramaBitmap.recycle()
            tallBitmap.recycle()
        }
    }

    @Test
    fun missingMediaReturnsNullInsteadOfCrashing() = runBlocking {
        withFixtures { context, _, decoder ->
            val missing = android.net.Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${Long.MAX_VALUE}",
            )
            assertNull(decoder.decode(missing, 320, 320))
        }
    }

    @Test
    fun transparentPngKeepsItsAlphaChannel() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val uri = addImage(
                context,
                fixtures,
                "transparent.png",
                "image/png",
                64,
                64,
                Bitmap.CompressFormat.PNG,
                color = 0x00000000,
            )

            val bitmap = decoder.decode(uri, 64, 64)

            assertNotNull(bitmap)
            assertTrue(bitmap!!.hasAlpha())
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            bitmap.recycle()
        }
    }

    @Test
    fun corruptImageReturnsNullInsteadOfCrashing() = runBlocking {
        withFixtures { context, fixtures, decoder ->
            val uri = addCorruptImage(context, fixtures)

            assertNull(decoder.decode(uri, 320, 320))
        }
    }

    private suspend fun withFixtures(
        block: suspend (android.content.Context, MutableList<android.net.Uri>, ImagePreviewDecoder) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = mutableListOf<android.net.Uri>()
        try {
            block(context, fixtures, ImagePreviewDecoder(context))
        } finally {
            fixtures.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun addImage(
        context: android.content.Context,
        fixtures: MutableList<android.net.Uri>,
        name: String,
        mimeType: String,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        color: Int = 0xFF587766.toInt(),
    ): android.net.Uri {
        val uri = insertPending(context, name, mimeType)
        var addedToCleanup = false
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { stream ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    check(bitmap.compress(format, 100, stream))
                } finally {
                    bitmap.recycle()
                }
            }
            publish(context, uri)
            fixtures += uri
            addedToCleanup = true
            return uri
        } finally {
            if (!addedToCleanup) context.contentResolver.delete(uri, null, null)
        }
    }

    private fun addCorruptImage(
        context: android.content.Context,
        fixtures: MutableList<android.net.Uri>,
    ): android.net.Uri {
        val uri = insertPending(context, "corrupt.jpg", "image/jpeg")
        var addedToCleanup = false
        try {
            context.contentResolver.openOutputStream(uri, "w")!!.use { stream ->
                stream.write(byteArrayOf(0x13, 0x37, 0x00, 0x55, 0x21))
            }
            publish(context, uri)
            fixtures += uri
            addedToCleanup = true
            return uri
        } finally {
            if (!addedToCleanup) context.contentResolver.delete(uri, null, null)
        }
    }

    private fun insertPending(
        context: android.content.Context,
        name: String,
        mimeType: String,
    ): android.net.Uri = context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "decoder-${System.currentTimeMillis()}-$name")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LocalGalleryDecoderTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        },
    )!!

    private fun publish(context: android.content.Context, uri: android.net.Uri) {
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    }
}
