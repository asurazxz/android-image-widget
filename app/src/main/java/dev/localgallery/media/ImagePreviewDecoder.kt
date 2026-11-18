package dev.localgallery.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

class ImagePreviewDecoder(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val permissions = PhotoPermissionManager(appContext)

    suspend fun decode(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (maxWidth <= 0 || maxHeight <= 0) return@withContext null
        if (permissions.status() != PhotoAccess.FULL) return@withContext null

        try {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val size = info.size
                val sourceWidth = size.width.coerceAtLeast(1)
                val sourceHeight = size.height.coerceAtLeast(1)
                val pixelScale = sqrt(MAX_PIXELS.toDouble() / (sourceWidth.toLong() * sourceHeight).toDouble())
                    .coerceAtMost(1.0)
                val scale = min(
                    min(maxWidth.toDouble() / sourceWidth, maxHeight.toDouble() / sourceHeight),
                    pixelScale,
                ).coerceAtMost(1.0)
                decoder.setTargetSize(
                    floor(sourceWidth * scale).toInt().coerceAtLeast(1),
                    floor(sourceHeight * scale).toInt().coerceAtLeast(1),
                )
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
        const val MAX_PIXELS = 1_000_000L
    }
}
