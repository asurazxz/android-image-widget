package dev.localgallery.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
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
import java.util.UUID
import kotlin.math.floor
import kotlin.math.sqrt

/** One bounded private preview per widget. Originals are never copied or modified. */
class WidgetImageRenderer(private val context: Context) {
    private val directory = File(context.cacheDir, "widget-previews")

    suspend fun prepare(
        id: Int, uri: String, revision: Long, fit: Boolean = false, size: IntSize? = null,
    ): Bitmap? = lock(id).withLock { withContext(Dispatchers.IO) {
        if (PhotoPermissionManager(context).status() != PhotoAccess.FULL) {
            clearFiles(id)
            return@withContext null
        }
        val (width, height) = size ?: widgetPreviewSize(context, AppWidgetManager.getInstance(context).getAppWidgetOptions(id))
        directory.mkdirs()
        val sourceKey = UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8))
        val mode = if (fit) "fit" else "crop"
        val target = File(directory, "$id-$revision-$mode-$width-$height-$sourceKey.png")
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
        val bitmap = ImagePreviewDecoder(context).decode(Uri.parse(uri), width, height, crop = !fit)
        if (PhotoPermissionManager(context).status() != PhotoAccess.FULL) {
            bitmap?.recycle()
            clearFiles(id)
            return@withContext null
        }
        if (bitmap == null) return@withContext null
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
        val locks = ConcurrentHashMap<Int, Mutex>()
        fun lock(id: Int) = locks.getOrPut(id) { Mutex() }
    }
}

private const val MAX_PREVIEW_PIXELS = 1_000_000L
private const val MAX_PREVIEW_EDGE = 1440

@Suppress("DEPRECATION")
internal fun widgetPreviewSize(context: Context, options: Bundle, layoutSize: DpSize? = null): IntSize {
    fun option(key: String): Int = options.getInt(key).coerceAtLeast(0)
    val portrait = SizeF(option(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(),
        option(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat())
    val landscape = SizeF(option(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat(),
        option(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat())
    val orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        landscape
    } else portrait
    val supportedSizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES).orEmpty()
    } else emptyList()
    val variants = supportedSizes.ifEmpty { listOf(portrait, landscape) }
        .filter { it.width > 0f && it.height > 0f }.distinct()
    val selected = when {
        layoutSize != null && layoutSize.width.value > 0f && layoutSize.height.value > 0f ->
            SizeF(layoutSize.width.value, layoutSize.height.value)
        orientation in variants -> orientation
        variants.isNotEmpty() -> variants.first()
        else -> SizeF(240f, 240f)
    }

    val metrics = context.resources.displayMetrics
    val width = selected.width.toDouble() * metrics.density
    val height = selected.height.toDouble() * metrics.density
    // One RemoteViews update can contain all advertised sizes. Leave headroom below the platform's
    // combined 1.5-screen budget and keep a separate hard cap for large or high-density displays.
    val screenPixels = metrics.widthPixels.toLong() * metrics.heightPixels
    val pixelBudget = minOf(MAX_PREVIEW_PIXELS, (screenPixels * 5 / 4 / variants.size.coerceAtLeast(1)).coerceAtLeast(1))
    val scale = minOf(1.0, MAX_PREVIEW_EDGE / maxOf(width, height), sqrt(pixelBudget / (width * height)))
    return IntSize(floor(width * scale).toInt().coerceAtLeast(1), floor(height * scale).toInt().coerceAtLeast(1))
}
