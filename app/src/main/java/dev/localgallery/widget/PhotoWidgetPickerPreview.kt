package dev.localgallery.widget

import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.collection.intSetOf
import androidx.compose.runtime.Composable
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetManager.Companion.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val previewPublication = Mutex()
private const val PREVIEW_PREFERENCES = "widget_picker_preview"
private const val PUBLISHED_VERSION = "published_version"

@Composable
@GlanceComposable
internal fun PhotoWidgetPickerPreview() {
    val fullSize = GlanceModifier.fillMaxSize()
    val photoFrame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        fullSize.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    } else {
        fullSize
    }
    Image(
        provider = ImageProvider(R.drawable.widget_picker_sample_photo),
        contentDescription = LocalContext.current.getString(R.string.widget_picker_sample_photo),
        modifier = photoFrame,
        contentScale = ContentScale.Crop,
    )
}

internal suspend fun publishPhotoWidgetPickerPreview(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

    withContext(Dispatchers.IO) {
        previewPublication.withLock {
            try {
                val version = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                ).longVersionCode
                val preferences = context.getSharedPreferences(PREVIEW_PREFERENCES, Context.MODE_PRIVATE)
                if (preferences.getLong(PUBLISHED_VERSION, -1L) == version) return@withLock

                val result = GlanceAppWidgetManager(context).setWidgetPreviews(
                    receiver = PhotoWidgetReceiver::class,
                    widgetCategories = intSetOf(WIDGET_CATEGORY_HOME_SCREEN),
                )
                if (result == SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
                    preferences.edit().putLong(PUBLISHED_VERSION, version).apply()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w("PhotoWidgetPreview", "Could not publish the widget picker preview", exception)
            }
        }
    }
}
