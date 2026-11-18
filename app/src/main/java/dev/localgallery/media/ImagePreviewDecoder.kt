package dev.localgallery.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.floor
import kotlin.math.sqrt

class ImagePreviewDecoder(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val permissions = PhotoPermissionManager(appContext)

    suspend fun decode(uri: Uri, maxWidth: Int, maxHeight: Int, crop: Boolean = false): Bitmap? =
        decodeBounded(uri, maxWidth, maxHeight, crop, screen = false)

    suspend fun decodeForScreen(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? =
        decodeBounded(uri, minOf(maxWidth, MAX_SCREEN_EDGE), minOf(maxHeight, MAX_SCREEN_EDGE),
            crop = false, screen = true)

    private suspend fun decodeBounded(
        uri: Uri, maxWidth: Int, maxHeight: Int, crop: Boolean, screen: Boolean,
    ): Bitmap? = withContext(decoderDispatcher) {
        if (maxWidth <= 0 || maxHeight <= 0) return@withContext null
        if (permissions.status() != PhotoAccess.FULL) return@withContext null

        try {
            val source = ImageDecoder.createSource(resolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (!screen) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))

                // ImageDecoder reports and decodes dimensions with the EXIF orientation applied.
                val sourceWidth = info.size.width.coerceAtLeast(1)
                val sourceHeight = info.size.height.coerceAtLeast(1)
                val widthRatio = maxWidth.toDouble() / sourceWidth
                val heightRatio = maxHeight.toDouble() / sourceHeight
                val outputPixels = if (screen) MAX_SCREEN_PIXELS else MAX_PREVIEW_PIXELS
                val workingPixels = if (crop) MAX_CROP_WORKING_PIXELS else outputPixels
                val requestedScale = if (crop) maxOf(widthRatio, heightRatio) else minOf(widthRatio, heightRatio)
                val scale = minOf(1.0, requestedScale, pixelScale(sourceWidth, sourceHeight, workingPixels),
                    MAX_WORKING_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight))
                val width = scaledDimension(sourceWidth, scale)
                val height = scaledDimension(sourceHeight, scale)
                decoder.setTargetSize(width, height)

                if (crop) {
                    val cropScale = minOf(1.0, width.toDouble() / maxWidth, height.toDouble() / maxHeight,
                        pixelScale(maxWidth, maxHeight, outputPixels))
                    val cropWidth = scaledDimension(maxWidth, cropScale).coerceAtMost(width)
                    val cropHeight = scaledDimension(maxHeight, cropScale).coerceAtMost(height)
                    if (cropWidth < width || cropHeight < height) {
                        val left = (width - cropWidth) / 2
                        val top = (height - cropHeight) / 2
                        decoder.setCrop(Rect(left, top, left + cropWidth, top + cropHeight))
                    }
                }
            }
            if (permissions.status() == PhotoAccess.FULL) bitmap else {
                bitmap.recycle()
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val MAX_PREVIEW_PIXELS = 1_000_000L
        const val MAX_CROP_WORKING_PIXELS = 6_000_000L
        const val MAX_WORKING_EDGE = 8192
        const val MAX_SCREEN_PIXELS = 4_000_000L
        const val MAX_SCREEN_EDGE = 3200
        val decoderDispatcher = Dispatchers.IO.limitedParallelism(2)

        fun pixelScale(width: Int, height: Int, maxPixels: Long): Double =
            sqrt(maxPixels.toDouble() / (width.toLong() * height))

        fun scaledDimension(dimension: Int, scale: Double): Int =
            floor(dimension * scale + 1e-8).toInt().coerceAtLeast(1)
    }
}
