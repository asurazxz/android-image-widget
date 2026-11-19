package dev.localgallery.widget

import android.appwidget.AppWidgetManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import dev.localgallery.data.DEFAULT_FIT_BACKGROUND_COLOR
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import dev.localgallery.ui.GalleryAppearanceSettings
import dev.localgallery.ui.galleryColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.glance.LocalSize
import android.graphics.Bitmap

internal val WidgetRenderGenerationKey = longPreferencesKey("render_generation")

private data class RenderKey(val generation: Long, val size: IntSize, val revision: Long?)
internal data class WidgetFrame(val record: WidgetRecord?, val bitmap: Bitmap?)
private data class KeyedFrame(val key: RenderKey, val frame: WidgetFrame)

class PhotoWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_loading) {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { PhotoWidgetPickerPreview() }
    }

    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        val recoveryIntent = Intent(context, PhotoWidgetConfigurationActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .setData(Uri.parse("localgallery://widget-recovery/$appWidgetId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val recoveryAction = PendingIntent.getActivity(
            context,
            appWidgetId,
            recoveryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val appearance = GalleryAppearanceSettings.latest
        val light = galleryColorScheme(context, appearance, dark = false)
        val dark = galleryColorScheme(context, appearance, dark = true)
        val manager = AppWidgetManager.getInstance(context)
        val errorViews = RemoteViews(context.packageName, R.layout.widget_loading).apply {
            val label = if (context.resources.configuration.fontScale >= 1.5f) R.string.photo_preview_settings
                else R.string.widget_temporarily_unavailable
            setTextViewText(R.id.widget_loading_root, context.getString(label))
            setOnClickPendingIntent(R.id.widget_loading_root, recoveryAction)
            setContentDescription(R.id.widget_loading_root, context.getString(R.string.widget_open_recovery))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setColorStateList(R.id.widget_loading_root, "setBackgroundTintList",
                    ColorStateList.valueOf(light.primaryContainer.toArgb()), ColorStateList.valueOf(dark.primaryContainer.toArgb()))
                setColorInt(R.id.widget_loading_root, "setTextColor",
                    light.onPrimaryContainer.toArgb(), dark.onPrimaryContainer.toArgb())
            } else {
                val configuration = context.resources.configuration
                val colors = if (configuration.isDark()) dark else light
                val options = manager.getAppWidgetOptions(appWidgetId)
                val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110).coerceAtLeast(110)
                val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).coerceAtLeast(110)
                val size = DpSize(width.dp, height.dp)
                val tile = legacyWidgetTile(size, widgetPreviewSize(context, options, size), colors.primaryContainer)
                setImageViewBitmap(R.id.widget_loading_backdrop, tile)
                setViewVisibility(R.id.widget_loading_backdrop, View.VISIBLE)
                setInt(R.id.widget_loading_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
                setTextColor(R.id.widget_loading_root, colors.onPrimaryContainer.toArgb())
            }
        }
        manager.updateAppWidget(appWidgetId, errorViews)
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetController(context).delete(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val appearances = GalleryAppearanceSettings.observe(context)
        val initialAppearance = appearances.first()
        WidgetController(context).reconcileForRender(widgetId)
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val initialSize = widgetPreviewSize(context, options)
        val initialPreferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        // Observe saved photo changes even when an active session's refresh event is delayed.
        val revisions = WidgetDatabase.get(context).widgets().observeRevision(widgetId)
        val initialRevision = revisions.first()
        val initialKey = RenderKey(initialPreferences[WidgetRenderGenerationKey] ?: 0L, initialSize, initialRevision)
        val initialFrame = loadWidgetFrame(context, widgetId, initialSize)
        provideContent {
            val generation = currentState<Preferences>()[WidgetRenderGenerationKey] ?: 0L
            val revision by revisions.collectAsState(initialRevision)
            val size = LocalSize.current
            val pixels = widgetPreviewSize(context, LocalAppWidgetOptions.current, size)
            val key = RenderKey(generation, pixels, revision)
            val loaded by produceState(
                initialValue = KeyedFrame(initialKey, initialFrame),
                key1 = key,
            ) {
                if (value.key != key) value = KeyedFrame(key, loadWidgetFrame(context, widgetId, pixels))
            }
            val frame = loaded.frame.takeIf { loaded.key == key }
            val full = PhotoPermissionManager(context).status() == PhotoAccess.FULL
            val appearance by appearances.collectAsState(initialAppearance)
            val legacyHost = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            val (recoverySurface, recoveryText) = if (!appearance.custom && !legacyHost) {
                GlanceTheme.colors.primaryContainer to GlanceTheme.colors.onPrimaryContainer
            } else {
                remember(appearance) {
                    val light = galleryColorScheme(context, appearance, dark = false)
                    val dark = galleryColorScheme(context, appearance, dark = true)
                    ColorProvider(day = light.primaryContainer, night = dark.primaryContainer) to
                        ColorProvider(day = light.onPrimaryContainer, night = dark.onPrimaryContainer)
                }
            }
            val configure = actionStartActivity(Intent(context, PhotoWidgetConfigurationActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val bitmap = frame?.bitmap
            val record = frame?.record
            val hasPhoto = full && bitmap != null && record?.currentUri != null
            val fit = record?.displayMode == "FIT"
            val backdrop = if (record?.displayMode == "FIT") Color(record.fitBackgroundColor) else PhotoBackdrop
            val surface = if (hasPhoto) ColorProvider(day = backdrop, night = backdrop)
                else recoverySurface
            val background = GlanceModifier.fillMaxSize().appWidgetBackground()
            val widgetShape = if (legacyHost) background else background.background(surface)
                .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            Box(widgetShape) {
                if (full && bitmap != null && record?.currentUri != null) {
                    val tile = if (legacyHost) remember(bitmap, size, pixels, backdrop, fit) {
                        legacyWidgetTile(size, pixels, backdrop, bitmap, fit)
                    } else bitmap
                    Image(ImageProvider(tile), context.getString(R.string.widget_preview_photo),
                        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(
                            Intent(context, OpenPhotoActivity::class.java)
                                .putExtra(OpenPhotoActivity.EXTRA_APP_WIDGET_ID, widgetId)
                                .putExtra(OpenPhotoActivity.EXTRA_PHOTO_URI, record.currentUri)
                                .putExtra(OpenPhotoActivity.EXTRA_PHOTO_MIME, record.currentMime)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))),
                        contentScale = when {
                            legacyHost -> ContentScale.FillBounds
                            fit -> ContentScale.Fit
                            else -> ContentScale.Crop
                        })
                } else {
                    if (legacyHost) {
                        val fill = surface.getColor(context)
                        val tile = remember(size, pixels, fill) { legacyWidgetTile(size, pixels, fill) }
                        Image(ImageProvider(tile), null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                    val compactText = context.resources.configuration.fontScale >= 1.5f
                    val permissionLabel = context.getString(R.string.widget_allow_full_access)
                    val recoveryAction = GlanceModifier.fillMaxSize()
                        .padding(if (compactText) 8.dp else 12.dp).clickable(configure)
                    val recoveryTarget = if (!full && compactText) {
                        recoveryAction.semantics { contentDescription = permissionLabel }
                    } else recoveryAction
                    Column(recoveryTarget,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val label = when {
                            !full && compactText -> R.string.widget_allow_access_short
                            !full -> R.string.widget_allow_full_access
                            frame?.record == null && compactText -> R.string.widget_choose_photos_short
                            frame?.record == null -> R.string.widget_setup_photos
                            else -> R.string.photo_preview_settings
                        }
                        val style = if (compactText) {
                            TextStyle(color = recoveryText, fontSize = 12.sp, textAlign = TextAlign.Center)
                        } else TextStyle(color = recoveryText)
                        Text(context.getString(label), style = style)
                    }
                }
            }
        }
    }
}

private val PhotoBackdrop = Color(DEFAULT_FIT_BACKGROUND_COLOR)
private fun Configuration.isDark() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

// Before Android 12, Glance cannot clip RemoteViews. Draw the legacy outline into the bounded tile.
private fun legacyWidgetTile(
    size: DpSize, pixels: IntSize, backdrop: Color, bitmap: Bitmap? = null, fit: Boolean = false,
): Bitmap {
    val (width, height) = pixels
    val tile = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(tile)
    val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val pixelsPerDp = width / size.width.value.coerceAtLeast(1f)
    val radius = minOf(20f * pixelsPerDp, minOf(width, height) / 2f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = backdrop.toArgb() }
    canvas.drawRoundRect(bounds, radius, radius, paint)
    if (bitmap != null) {
        val outline = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
        canvas.clipPath(outline)
        val widthRatio = width.toFloat() / bitmap.width
        val heightRatio = height.toFloat() / bitmap.height
        val photoScale = if (fit) minOf(widthRatio, heightRatio) else maxOf(widthRatio, heightRatio)
        val photoWidth = bitmap.width * photoScale
        val photoHeight = bitmap.height * photoScale
        val left = (width - photoWidth) / 2f
        val top = (height - photoHeight) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + photoWidth, top + photoHeight), paint)
    }
    return tile
}

internal suspend fun loadWidgetFrame(context: Context, widgetId: Int, size: IntSize? = null): WidgetFrame = withContext(Dispatchers.IO) {
    val dao = WidgetDatabase.get(context).widgets()
    val record = dao.get(widgetId)
    val renderer = WidgetImageRenderer(context)
    if (PhotoPermissionManager(context).status() != PhotoAccess.FULL) {
        WidgetController(context).reconcileForRender(widgetId)
        return@withContext WidgetFrame(dao.get(widgetId), null)
    }
    val uri = record?.currentUri ?: return@withContext WidgetFrame(record, null)
    val bitmap = renderer.prepare(widgetId, uri, record.revision, fit = record.displayMode == "FIT", size = size)
    val latest = dao.get(widgetId)
    if (PhotoPermissionManager(context).status() != PhotoAccess.FULL) {
        bitmap?.recycle()
        WidgetController(context).reconcileForRender(widgetId)
        return@withContext WidgetFrame(dao.get(widgetId), null)
    }
    if (latest?.revision != record.revision || latest.currentUri != uri || latest.displayMode != record.displayMode) {
        bitmap?.recycle()
        return@withContext WidgetFrame(latest, null)
    }
    WidgetFrame(latest, bitmap)
}

// Already-bound launchers may still hold the previous RemoteViews shuffle action.
class ShuffleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        WidgetController(context).advance(id)
    }
}

class PhotoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PhotoWidget()
}
