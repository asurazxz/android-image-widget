package dev.localgallery.ui

import android.content.Intent
import android.appwidget.AppWidgetManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.media.ImagePreviewDecoder
import dev.localgallery.media.MediaAlbum
import dev.localgallery.widget.PhotoWidgetConfigurationActivity
import dev.localgallery.widget.PhotoWidget
import dev.localgallery.widget.R
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import androidx.glance.appwidget.updateAll
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun GalleryHome(refreshEpoch: Int, appearance: GalleryAppearance?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var savingAppearance by remember { mutableStateOf(false) }
    var appearanceError by rememberSaveable { mutableStateOf(false) }
    val permissions = remember { PhotoPermissionManager(context) }
    var access by remember { mutableStateOf(permissions.status()) }
    var requestEpoch by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var widgets by remember { mutableStateOf<List<WidgetRecord>>(emptyList()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestEpoch++ }
    LaunchedEffect(refreshEpoch, requestEpoch) {
        access = permissions.status()
        message = null
    }
    LaunchedEffect(requestEpoch) {
        if (requestEpoch > 0) dev.localgallery.widget.WidgetController(context).reconcileAll()
    }
    LaunchedEffect(Unit) { WidgetDatabase.get(context).widgets().observeAll().collect { widgets = it } }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.safeDrawingPadding().fillMaxSize(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("Your photos, a little closer.", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text("Your widgets", style = MaterialTheme.typography.titleLarge)
                if (widgets.isEmpty()) Text("No configured widgets yet. Add one from your home-screen widget picker.")
            }
            items(widgets, key = { it.appWidgetId }) { widget ->
                val albumsText = if (widget.entireGallery) "Entire gallery" else
                    "${widget.selectedAlbums.size} selected folder${if (widget.selectedAlbums.size == 1) "" else "s"}"
                val viewer = if (widget.preferredViewer == null) "System default viewer" else "Preferred viewer set"
                val rotation = dev.localgallery.domain.RotationPreference.fromMinutes(widget.rotationMinutes)
                    ?.let { when (it) {
                        dev.localgallery.domain.RotationPreference.Manual -> "Manual only"
                        dev.localgallery.domain.RotationPreference.FifteenMinutes -> "About every 15 minutes"
                        dev.localgallery.domain.RotationPreference.ThirtyMinutes -> "About every 30 minutes"
                        dev.localgallery.domain.RotationPreference.Daily -> "About every 24 hours"
                        is dev.localgallery.domain.RotationPreference.CustomHours -> "About every ${it.hours} hours"
                    } } ?: "Unavailable interval"
                Card(shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp).testTag("widget-row-${widget.appWidgetId}"), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Widget ${widget.appWidgetId}", style = MaterialTheme.typography.titleMedium)
                            Text("$albumsText · ${if (widget.displayMode == "FIT") "Fit" else "Fill / Crop"}")
                            Text("$viewer · $rotation", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(modifier = Modifier.testTag("edit-widget-${widget.appWidgetId}"), onClick = {
                            context.startActivity(Intent(context, PhotoWidgetConfigurationActivity::class.java)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.appWidgetId)
                                .putExtra(PhotoWidgetConfigurationActivity.EXTRA_MANAGE_EDIT, true))
                        }) { Text("Edit") }
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add a photo widget", style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.widget_picker_instructions, stringResource(R.string.app_name)))
                        Text("Each widget has its own photos and settings. Everything stays on your device.")
                    }
                }
            }
            item {
                val mode = when {
                    appearance?.custom == true -> R.string.appearance_dialog_custom
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> R.string.appearance_dialog_wallpaper
                    else -> R.string.appearance_dialog_gallery_default
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.appearance_title)) },
                    supportingContent = { Text(stringResource(mode)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = appearance != null && !savingAppearance, role = Role.Button) {
                            showAppearance = true
                        },
                )
                if (appearanceError) {
                    Text(stringResource(R.string.appearance_save_error), color = MaterialTheme.colorScheme.error)
                }
            }
            if (access != PhotoAccess.FULL) item {
                PhotoAccessPanel(access, { request.launch(permissions.permissions()) }, {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                })
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text("Offline by design", style = MaterialTheme.typography.titleMedium)
                Text("No accounts, advertisements, analytics or photo uploads. Full photo access is required for gallery-wide shuffle.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showAppearance && appearance != null) {
        AppearanceDialog(appearance, onApply = { choice ->
            showAppearance = false
            appearanceError = false
            savingAppearance = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Apply commits the user's choice even if this activity is closed or recreated.
                withContext(NonCancellable) {
                    try {
                        GalleryAppearanceSettings.save(context, choice)
                        PhotoWidget().updateAll(context.applicationContext)
                    } catch (_: IOException) {
                        appearanceError = true
                    } finally {
                        savingAppearance = false
                    }
                }
            }
        }, onDismiss = { showAppearance = false })
    }
}

@Composable fun SourceModeControl(all: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
            selected = all, enabled = enabled, role = Role.RadioButton, onClick = { onChange(true) }
        ).testTag("source-entire")) {
            RadioButton(selected = all, enabled = enabled, onClick = null)
            Text("Entire gallery", Modifier.padding(top = 12.dp))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
            selected = !all, enabled = enabled, role = Role.RadioButton, onClick = { onChange(false) }
        ).testTag("source-selected")) {
            RadioButton(selected = !all, enabled = enabled, onClick = null)
            Text("Selected albums", Modifier.padding(top = 12.dp))
        }
    }
}

@Composable fun AlbumRow(album: MediaAlbum, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<android.graphics.Bitmap?>(null, album.cover.key) {
        value = ImagePreviewDecoder(context).decode(album.cover.uri, 144, 144)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
        value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() }
    ).semantics(mergeDescendants = true) {}.testTag("album-toggle-${album.key}").padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        preview?.let { Image(it.asImageBitmap(), contentDescription = null,
            modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop) }
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium)
            Text("${album.count} photos · ${album.key.substringBefore(':')}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun PhotoAccessPanel(access: PhotoAccess, onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (access == PhotoAccess.PARTIAL) "Full access is needed" else "Connect your local gallery", style = MaterialTheme.typography.titleLarge)
        Text(if (access == PhotoAccess.PARTIAL)
            "Selected-photo access is enabled. Choose Allow all photos so your widgets can shuffle across your selected folders."
        else "Allow access to all photos to choose local folders and shuffle their images. Your originals stay on your device and are never changed.")
        Button(onClick = onRequest) { Text("Grant full photo access") }
        OutlinedButton(onClick = onSettings) { Text("Open app settings") }
    }
}
