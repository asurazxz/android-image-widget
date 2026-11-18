package dev.localgallery.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.media.GalleryLauncher
import dev.localgallery.media.ImagePreviewDecoder
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import dev.localgallery.ui.GalleryActivityTheme
import dev.localgallery.ui.GalleryAppearanceSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenPhotoActivity : ComponentActivity() {
    private data class Preview(val record: WidgetRecord, val bitmap: Bitmap)

    private val controller by lazy { WidgetController(applicationContext) }
    private val dao by lazy { WidgetDatabase.get(applicationContext).widgets() }
    private val permission by lazy { PhotoPermissionManager(applicationContext) }
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var displayedUri: String? = null
    private var displayedMime: String? = null
    private var preview by mutableStateOf<Preview?>(null)
    private var busy by mutableStateOf(false)
    private var canShuffle by mutableStateOf(false)
    private var needsPermission by mutableStateOf(false)
    private var openFailed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (!isAttachedWidget(this, widgetId)) {
            finish()
            return
        }
        val photo = savedInstanceState ?: intent.extras
        displayedUri = photo?.getString(EXTRA_PHOTO_URI)
        displayedMime = photo?.getString(EXTRA_PHOTO_MIME)
        val appearances = GalleryAppearanceSettings.observe(applicationContext)
        setContent {
            val appearance by appearances.collectAsState(GalleryAppearanceSettings.latest)
            GalleryActivityTheme(appearance) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val photoPreview = preview
                            when {
                                photoPreview != null -> Image(
                                    photoPreview.bitmap.asImageBitmap(),
                                    stringResource(R.string.photo_preview),
                                    Modifier.fillMaxSize(),
                                )
                                busy -> CircularProgressIndicator()
                                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(if (needsPermission) R.string.widget_allow_full_access
                                        else R.string.widget_photo_unavailable))
                                    TextButton(onClick = ::openSettings) {
                                        Text(stringResource(R.string.photo_preview_settings))
                                    }
                                }
                            }
                        }
                        if (openFailed) {
                            Text(
                                stringResource(R.string.photo_open_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        PhotoPreviewActions(canShuffle && !busy, preview != null && !busy, ::shuffle, ::openOriginal)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) updatePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_PHOTO_URI, displayedUri)
        outState.putString(EXTRA_PHOTO_MIME, displayedMime)
        super.onSaveInstanceState(outState)
    }

    private fun shuffle() = updatePreview(advance = true)

    private fun updatePreview(advance: Boolean = false) {
        if (busy) return
        busy = true
        if (!advance) preview = null
        lifecycleScope.launch {
            try {
                if (advance) {
                    displayedUri = null
                    displayedMime = null
                    openFailed = false
                    withContext(NonCancellable) { controller.advance(widgetId) }
                }
                loadPhoto()
            } finally {
                busy = false
            }
        }
    }

    private suspend fun loadPhoto() {
        val record = dao.get(widgetId)
        val uri = displayedUri ?: record?.currentUri
        val mime = if (displayedUri == null) record?.currentMime else displayedMime
        val metrics = resources.displayMetrics
        val bitmap = if (record != null && uri != null && isAttachedWidget(this, widgetId)) {
            ImagePreviewDecoder(this).decode(uri.toUri(), metrics.widthPixels, metrics.heightPixels)
        } else null

        needsPermission = permission.status() != PhotoAccess.FULL
        val attached = isAttachedWidget(this, widgetId)
        canShuffle = attached && record != null && !needsPermission
        if (canShuffle && bitmap != null && record != null && uri != null) {
            displayedUri = uri
            displayedMime = mime
            preview = Preview(record.copy(currentUri = uri, currentMime = mime), bitmap)
        } else {
            bitmap?.recycle()
            preview = null
            if (needsPermission && attached) controller.refresh(widgetId)
        }
    }

    private fun openOriginal() {
        if (busy || !isAttachedWidget(this, widgetId)) return
        val record = preview?.record ?: return
        openFailed = !GalleryLauncher(this).open(record)
        if (permission.status() != PhotoAccess.FULL) updatePreview()
    }

    private fun openSettings() {
        startActivity(Intent(this, PhotoWidgetConfigurationActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(PhotoWidgetConfigurationActivity.EXTRA_MANAGE_EDIT, true))
    }

    companion object {
        const val EXTRA_APP_WIDGET_ID = "dev.localgallery.widget.extra.APP_WIDGET_ID"
        const val EXTRA_PHOTO_URI = "dev.localgallery.widget.extra.PHOTO_URI"
        const val EXTRA_PHOTO_MIME = "dev.localgallery.widget.extra.PHOTO_MIME"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PhotoPreviewActions(
    canShuffle: Boolean,
    canOpen: Boolean,
    shuffle: () -> Unit,
    openOriginal: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (stack) 1 else 2,
        ) {
            Button(onClick = shuffle, enabled = canShuffle, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.photo_preview_shuffle))
            }
            OutlinedButton(onClick = openOriginal, enabled = canOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.photo_preview_open_original))
            }
        }
    }
}
