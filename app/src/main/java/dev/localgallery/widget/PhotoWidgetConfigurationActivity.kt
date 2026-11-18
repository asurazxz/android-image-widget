package dev.localgallery.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.media.MediaAlbum
import dev.localgallery.media.MediaRepository
import dev.localgallery.media.GalleryLauncher
import dev.localgallery.media.ViewerOption
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import dev.localgallery.domain.RotationPreference
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import dev.localgallery.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoWidgetConfigurationActivity : ComponentActivity() {
    private var refreshEpoch by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val editMode = intent.getBooleanExtra(EXTRA_MANAGE_EDIT, false)
        val appearances = GalleryAppearanceSettings.observe(applicationContext)
        setContent {
            val appearance by appearances.collectAsState(GalleryAppearanceSettings.latest)
            GalleryActivityTheme(appearance) {
                ConfigurationScreen(id, editMode, refreshEpoch, onCancel = { finish() }, onSaved = {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                })
            }
        }
    }
    override fun onResume() { super.onResume(); refreshEpoch++ }
    companion object { const val EXTRA_MANAGE_EDIT = "dev.localgallery.extra.MANAGE_EDIT" }
}

@Composable private fun ConfigurationScreen(id: Int, editMode: Boolean, refreshEpoch: Int, onCancel: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val permissions = remember { PhotoPermissionManager(context) }
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(permissions.status()) }
    var requestEpoch by remember { mutableIntStateOf(0) }
    var record by remember { mutableStateOf<WidgetRecord?>(null) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var draftInitialized by rememberSaveable { mutableStateOf(false) }
    var albums by remember { mutableStateOf<List<MediaAlbum>>(emptyList()) }
    var entire by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var fit by rememberSaveable { mutableStateOf(false) }
    var preferredViewer by rememberSaveable { mutableStateOf<String?>(null) }
    var rotationMinutes by rememberSaveable { mutableLongStateOf(1_440L) }
    var customHours by rememberSaveable { mutableStateOf("24") }
    var chooseViewer by remember { mutableStateOf(false) }
    val viewers by produceState<List<ViewerOption>>(emptyList()) {
        value = withContext(Dispatchers.IO) { GalleryLauncher(context).viewers() }
    }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var staleWidget by remember { mutableStateOf(false) }
    val customRotationSelected = rotationMinutes >= 60L && rotationMinutes != 1_440L
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { requestEpoch++ }
    BackHandler(enabled = saving) { /* Complete the atomic save before leaving. */ }
    LaunchedEffect(refreshEpoch, requestEpoch) {
        loading = true
        access = permissions.status()
        staleWidget = !isAttachedWidget(context, id)
        if (!staleWidget && record == null) {
            record = WidgetDatabase.get(context).widgets().get(id)
            staleWidget = editMode && record == null
            if (!draftInitialized) {
                record?.let {
                    entire = it.entireGallery; selected = it.selectedAlbums
                    fit = it.displayMode == "FIT"; preferredViewer = it.preferredViewer
                    rotationMinutes = it.rotationMinutes
                    RotationPreference.fromMinutes(it.rotationMinutes)?.let { choice ->
                        if (choice is RotationPreference.CustomHours) customHours = choice.hours.toString()
                    }
                }
                draftInitialized = true
            }
            initialized = true
        }
        albums = emptyList()
        if (access == PhotoAccess.FULL) {
            try { albums = MediaRepository(context).albums() }
            catch (_: SecurityException) { access = permissions.status() }
        }
        // Configuration can be opened directly from a widget after Settings revoked access.
        // Treat this permission observation like MainActivity resume so bound widgets replace
        // stale previews and their private cache is cleared before the user continues.
        WidgetController(context).reconcileAll()
        loading = false
    }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Your photo widget", style = MaterialTheme.typography.headlineLarge)
                Text("Choose the photos you want to see.")
            }
            if (staleWidget) item {
                Text("This widget is no longer available. Return to the app and choose an existing widget to edit.",
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onCancel) { Text("Close") }
            }
            if (access != PhotoAccess.FULL) item {
                PhotoAccessPanel(access, { request.launch(permissions.permissions()) }, {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                })
            }
            if (access == PhotoAccess.FULL) {
                item { SourceModeControl(entire, enabled = !saving) { entire = it } }
                if (!entire) {
                    item { Text("Select one or more local photo folders.") }
                    items(albums, key = { it.key }) { album ->
                        AlbumRow(album, album.key in selected, !saving) {
                            selected = if (album.key in selected) selected - album.key else selected + album.key
                        }
                    }
                    val missing = selected.filter { key -> albums.none { it.key == key } }
                    if (missing.isNotEmpty()) item { Text("${missing.size} selected folders are unavailable. They will not be replaced with other folders.") }
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loading && albums.isEmpty()) item { Text("No local photos found. Add photos to your device to continue.") }
                item {
                    Text("Photo display", style = MaterialTheme.typography.titleLarge)
                    Row {
                        FilterChip(selected = !fit, enabled = !saving, onClick = { fit = false }, label = { Text("Fill / Crop") })
                        Spacer(Modifier.width(12.dp))
                        FilterChip(selected = fit, enabled = !saving, onClick = { fit = true }, label = { Text("Fit") })
                    }
                    Text(if (fit) "Show the whole image with a neutral background." else "Fill the widget and crop from the centre.")
                }
                item {
                    Text("Open original photos", style = MaterialTheme.typography.titleMedium)
                    val viewerLabel = if (preferredViewer == null) "System default" else
                        viewers.firstOrNull { it.packageName == preferredViewer }?.label ?: "Unavailable viewer (uses system default)"
                    OutlinedButton(enabled = !saving, onClick = { chooseViewer = true }) { Text(viewerLabel) }
                }
                item {
                    Text("Automatic rotation", style = MaterialTheme.typography.titleLarge)
                    Text("Android runs background updates approximately; timing can vary with battery and device settings.",
                        style = MaterialTheme.typography.bodySmall)
                    RotationOptionsControl(rotationMinutes, enabled = !saving) { rotationMinutes = it }
                    OutlinedTextField(value = customHours, onValueChange = { customHours = it },
                        enabled = !saving && customRotationSelected, modifier = Modifier.testTag("custom-hours"), label = { Text("Custom hours (1–168)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = customRotationSelected && RotationPreference.parseCustomHours(customHours) == null)
                    if (customRotationSelected && RotationPreference.parseCustomHours(customHours) == null)
                        Text("Enter a whole number from 1 to 168.", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    Text("Manual shuffle does not change the next automatic update. Daily means about every 24 hours, not a fixed time of day.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                val validRotation = rotationMinutes == 0L || rotationMinutes == 15L || rotationMinutes == 30L ||
                    rotationMinutes == 1_440L || (customRotationSelected && RotationPreference.parseCustomHours(customHours) != null)
                Button(enabled = initialized && !staleWidget && !loading && !saving && access == PhotoAccess.FULL && validRotation &&
                    albums.isNotEmpty() && (entire || selected.isNotEmpty()), onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            val desired = (record ?: WidgetRecord(id)).copy(entireGallery = entire,
                                selectedAlbums = selected, displayMode = if (fit) "FIT" else "CROP",
                                preferredViewer = preferredViewer,
                                rotationMinutes = when (rotationMinutes) {
                                    0L, 15L, 30L, 1_440L -> rotationMinutes
                                    else -> RotationPreference.parseCustomHours(customHours)?.times(60L) ?: return@launch
                                })
                            if (WidgetController(context).configure(desired)) onSaved()
                            else if (!isAttachedWidget(context, id)) {
                                staleWidget = true
                                error = "This widget is no longer available."
                            } else error = "Couldn't prepare a photo. Check full access, try again, or choose another folder."
                        } catch (_: SecurityException) {
                            error = "Full photo access is required. Grant access and try again."
                            access = permissions.status()
                        } finally { saving = false }
                    }
                }, modifier = Modifier.fillMaxWidth().testTag("save-widget")) { Text(if (saving) "Preparing photo…" else "Save widget") }
                TextButton(enabled = !saving, onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
    if (chooseViewer) AlertDialog(onDismissRequest = { chooseViewer = false }, title = { Text("Photo viewer") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { TextButton(onClick = { preferredViewer = null; chooseViewer = false }) { Text("System default") } }
                items(viewers, key = { it.packageName }) { viewer ->
                    TextButton(onClick = { preferredViewer = viewer.packageName; chooseViewer = false }) { Text(viewer.label) }
                }
                item { Text("If a selected viewer becomes unavailable, Android's default viewer will be used.") }
            }
        }, confirmButton = { TextButton(onClick = { chooseViewer = false }) { Text("Close") } })
}

@Composable internal fun RotationOptionsControl(rotationMinutes: Long, enabled: Boolean, onChange: (Long) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(0L to "Manual only", 15L to "Every 15 minutes", 30L to "Every 30 minutes",
            1_440L to "Daily (24 hours)", -1L to "Every N hours").forEach { (minutes, label) ->
            val suffix = if (minutes == -1L) "custom" else minutes.toString()
            val selected = if (minutes == -1L) rotationMinutes >= 60L && rotationMinutes != 1_440L
                else rotationMinutes == minutes
            Box(Modifier.testTag("rotation-option-$suffix")) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = selected, enabled = enabled, role = Role.RadioButton,
                    onClick = { onChange(if (minutes == -1L) 60L else minutes) }
                ).testTag("rotation-radio-$suffix"), horizontalArrangement = Arrangement.Start) {
                    RadioButton(enabled = enabled, selected = selected, onClick = null)
                    Text(label, Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

internal fun isAttachedWidget(context: android.content.Context, id: Int): Boolean =
    AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ==
        ComponentName(context, PhotoWidgetReceiver::class.java)
