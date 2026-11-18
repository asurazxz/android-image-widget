package dev.localgallery.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.localgallery.media.ImagePreviewDecoder
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.IOException

/** One bounded private preview per widget. Originals are never copied or modified. */
class WidgetImageRenderer(private val context: Context) {
    private val directory = File(context.cacheDir, "widget-previews")

    suspend fun prepare(id: Int, uri: String, revision: Long): Bitmap? = lock(id).withLock { withContext(Dispatchers.IO) {
        if (PhotoPermissionManager(context).status() != PhotoAccess.FULL) {
            clearFiles(id)
            return@withContext null
        }
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val density = context.resources.displayMetrics.density
        val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 240) * density).toInt().coerceIn(128, 640)
        val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 240) * density).toInt().coerceIn(128, 640)
        directory.mkdirs()
        val target = File(directory, "$id-$revision-$width-$height.png")
        if (target.exists()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            val cachedPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            if (bounds.outWidth in 1..width && bounds.outHeight in 1..height &&
                cachedPixels <= MAX_PREVIEW_PIXELS
            ) {
                BitmapFactory.decodeFile(target.absolutePath)?.let { cached ->
                    if (PhotoPermissionManager(context).status() != PhotoAccess.FULL) {
                        cached.recycle()
                        clearFiles(id)
                        return@withContext null
                    }
                    if (cached.width <= width && cached.height <= height &&
                        cached.width.toLong() * cached.height <= MAX_PREVIEW_PIXELS
                    ) return@withContext cached
                    cached.recycle()
                }
            }
            // A truncated or out-of-policy cache entry must not prevent regeneration.
            target.delete()
        }
        val bitmap = ImagePreviewDecoder(context).decode(Uri.parse(uri), width, height) ?: return@withContext null
        if (PhotoPermissionManager(context).status() != PhotoAccess.FULL) {
            bitmap.recycle()
            clearFiles(id)
            return@withContext null
        }
        val temporary = File(directory, "$id-preview.tmp")
        try {
            temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (!temporary.renameTo(target)) throw IOException("Preview replacement failed")
            directory.listFiles().orEmpty().filter { it.name.startsWith("$id-") && it != target }.forEach { it.delete() }
        } catch (_: IOException) {
            temporary.delete()
            // The in-memory preview is still usable if the cache is unavailable.
        } catch (_: IllegalStateException) {
            temporary.delete()
            // A failed compression must not make a valid in-memory preview unusable.
        }
        bitmap
    } }

    suspend fun clear(id: Int) = lock(id).withLock { withContext(Dispatchers.IO) { clearFiles(id) } }
    private fun clearFiles(id: Int) { directory.listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() } }
    private companion object {
        const val MAX_PREVIEW_PIXELS = 1_000_000L
        val locks = ConcurrentHashMap<Int, Mutex>()
        fun lock(id: Int) = locks.getOrPut(id) { Mutex() }
    }
}
