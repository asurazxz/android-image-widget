package dev.localgallery.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localgallery.data.WidgetDatabase
import dev.localgallery.data.WidgetRecord
import dev.localgallery.domain.RotationPreference
import dev.localgallery.media.GalleryLauncher
import dev.localgallery.media.MediaAlbum
import dev.localgallery.media.MediaRepository
import dev.localgallery.media.ViewerOption
import dev.localgallery.permissions.PhotoAccess
import dev.localgallery.permissions.PhotoPermissionManager
import dev.localgallery.ui.AlbumRow
import dev.localgallery.ui.GalleryActivityTheme
import dev.localgallery.ui.GalleryAppearanceSettings
import dev.localgallery.ui.PhotoAccessPanel
import dev.localgallery.ui.SourceModeControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    var fitBackgroundColor by rememberSaveable { mutableIntStateOf(FitBackgroundColors.CHARCOAL.argb) }
    var pickerSelection by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val formScroll = rememberLazyListState()
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
        if (staleWidget || access != PhotoAccess.FULL) pickerSelection = null
        if (!staleWidget && record == null) {
            record = WidgetDatabase.get(context).widgets().get(id)
            staleWidget = editMode && record == null
            if (!draftInitialized) {
                record?.let {
                    entire = it.entireGallery; selected = it.selectedAlbums
                    fit = it.displayMode == "FIT"; preferredViewer = it.preferredViewer
                    fitBackgroundColor = it.fitBackgroundColor
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
    val draftRotation = when (rotationMinutes) {
        0L, 15L, 30L, 1_440L -> rotationMinutes
        else -> if (customRotationSelected) RotationPreference.parseCustomHours(customHours)?.times(60L) else null
    }
    val availableKeys = remember(albums) { albums.mapTo(hashSetOf()) { it.key } }
    val selectedNames = remember(albums, selected) {
        val selectedKeys = selected.toHashSet()
        albums.asSequence().filter { it.key in selectedKeys }.take(3).map { it.name }.toList()
    }
    val saveEnabled = initialized && !staleWidget && !loading && !saving &&
        access == PhotoAccess.FULL && draftRotation != null && albums.isNotEmpty() &&
        (entire || selected.isNotEmpty())
    BackHandler(enabled = pickerSelection != null) { pickerSelection = null }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val compactHeight = maxHeight < 400.dp
            val pendingSelection = pickerSelection
            if (pendingSelection != null && !staleWidget && access == PhotoAccess.FULL) {
                AlbumPicker(
                    albums = albums,
                    selected = pendingSelection,
                    loading = loading,
                    compactHeight = compactHeight,
                    onToggle = { key ->
                        pickerSelection = if (key in pendingSelection) pendingSelection - key else pendingSelection + key
                    },
                    onCancel = { pickerSelection = null },
                    onDone = {
                        selected = pendingSelection
                        pickerSelection = null
                    },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().testTag("configuration-form"),
                        state = formScroll,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                "Your photo widget",
                                Modifier.padding(horizontal = 4.dp, vertical = 4.dp).semantics { heading() },
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                        if (staleWidget) item {
                            Text(
                                "This widget is no longer available. Return to the app and choose an existing widget to edit.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!staleWidget && access != PhotoAccess.FULL) item {
                            PhotoAccessPanel(access, { request.launch(permissions.permissions()) }, {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            })
                        }
                        if (!staleWidget && access == PhotoAccess.FULL) {
                            item {
                                ConfigurationSection("Photo source") {
                                    SourceModeControl(entire, enabled = !saving) { entire = it }
                                    if (!entire) {
                                        val missingCount = selected.count { it !in availableKeys }
                                        if (selectedNames.isNotEmpty()) {
                                            val remaining = selected.size - missingCount - selectedNames.size
                                            val names = selectedNames.joinToString(", ")
                                            Text(
                                                if (remaining > 0) "$names +$remaining more" else names,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (!loading && missingCount > 0) {
                                            Text(
                                                if (missingCount == 1) "1 selected album is unavailable."
                                                else "$missingCount selected albums are unavailable.",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        OutlinedButton(
                                            enabled = !saving && !loading && albums.isNotEmpty(),
                                            onClick = { pickerSelection = selected },
                                            modifier = Modifier.testTag("choose-albums"),
                                        ) {
                                            Text(if (selected.isEmpty()) "Choose albums" else "Edit albums (${selected.size})")
                                        }
                                    }
                                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (!loading && albums.isEmpty()) {
                                        Text("No local photos found. Add photos to your device to continue.")
                                    }
                                }
                            }
                            item {
                                ConfigurationSection("Photo display") {
                                    DisplayOptions(fit, fitBackgroundColor, enabled = !saving) { fit = it }
                                    if (fit) {
                                        FitBackgroundOptions(fitBackgroundColor, enabled = !saving) { fitBackgroundColor = it }
                                    }
                                }
                            }
                            item {
                                ConfigurationSection("Automatic rotation") {
                                    RotationOptionsControl(rotationMinutes, enabled = !saving) { rotationMinutes = it }
                                    if (customRotationSelected) {
                                        val invalidHours = draftRotation == null
                                        OutlinedTextField(
                                            value = customHours,
                                            onValueChange = { customHours = it },
                                            enabled = !saving,
                                            modifier = Modifier.fillMaxWidth().testTag("custom-hours"),
                                            label = { Text("Custom hours (1–168)") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                            isError = invalidHours,
                                            supportingText = if (invalidHours) {
                                                { Text("Enter a whole number from 1 to 168.") }
                                            } else null,
                                        )
                                    }
                                    Text(
                                        "Android schedules updates approximately. Manual shuffle keeps the current schedule.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            item {
                                ConfigurationSection("Open original photos") {
                                    val viewerLabel = if (preferredViewer == null) "System default" else
                                        viewers.firstOrNull { it.packageName == preferredViewer }?.label
                                            ?: "Unavailable viewer (uses system default)"
                                    OutlinedButton(enabled = !saving, onClick = { chooseViewer = true }) { Text(viewerLabel) }
                                }
                            }
                        }
                    }
                    ConfigurationActions(onCancel, error, cancelEnabled = !saving) {
                        Button(
                            enabled = saveEnabled,
                            onClick = {
                                val minutes = draftRotation ?: return@Button
                                saving = true
                                error = null
                                scope.launch {
                                    try {
                                        val desired = (record ?: WidgetRecord(id)).copy(
                                            entireGallery = entire,
                                            selectedAlbums = selected,
                                            displayMode = if (fit) "FIT" else "CROP",
                                            fitBackgroundColor = fitBackgroundColor,
                                            preferredViewer = preferredViewer,
                                            rotationMinutes = minutes,
                                        )
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
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("save-widget"),
                        ) {
                            Text(if (saving) "Preparing photo…" else "Save widget")
                        }
                    }
                }
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

@Composable
private fun ConfigurationSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ConfigurationActions(
    onCancel: () -> Unit,
    error: String? = null,
    cancelEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            error?.let {
                Text(
                    it,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = cancelEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
                content()
            }
        }
    }
}

@Composable
private fun AlbumPicker(
    albums: List<MediaAlbum>,
    selected: List<String>,
    loading: Boolean,
    compactHeight: Boolean,
    onToggle: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val search = query.trim()
    val visibleAlbums = remember(albums, search) {
        if (search.isEmpty()) albums else albums.filter { it.name.contains(search, ignoreCase = true) }
    }
    val checked = remember(selected) { selected.toHashSet() }
    Column(Modifier.fillMaxSize().semantics { paneTitle = "Choose albums" }.testTag("album-picker")) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = if (compactHeight) 4.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!compactHeight) {
                Text(
                    "Choose albums",
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().testTag("album-search"),
                label = { Text("Search albums") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            if (!compactHeight) {
                Text(
                    if (selected.size == 1) "1 album selected" else "${selected.size} albums selected",
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("album-picker-list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        ) {
            if (loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            } else if (visibleAlbums.isEmpty()) {
                item {
                    Text(
                        if (search.isEmpty()) "No local albums found." else "No albums match your search.",
                        Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleAlbums, key = { it.key }) { album ->
                AlbumRow(album, album.key in checked, enabled = !loading) { onToggle(album.key) }
            }
        }
        ConfigurationActions(onCancel) {
            Button(
                onClick = onDone,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("album-picker-done"),
            ) {
                Text(if (compactHeight) "Done (${selected.size})" else "Done")
            }
        }
    }
}

@Composable
private fun DisplayOptions(fit: Boolean, backgroundColor: Int, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayOption(
            label = "Fill / Crop",
            description = "Fills the widget",
            contentScale = ContentScale.Crop,
            background = MaterialTheme.colorScheme.surfaceContainer,
            selected = !fit,
            enabled = enabled,
            onClick = { onChange(false) },
            modifier = Modifier.weight(1f),
        )
        DisplayOption(
            label = "Fit",
            description = "Shows the whole photo",
            contentScale = ContentScale.Fit,
            background = Color(backgroundColor),
            selected = fit,
            enabled = enabled,
            onClick = { onChange(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DisplayOption(
    label: String,
    description: String,
    contentScale: ContentScale,
    background: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier.clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.widget_picker_sample_photo),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small).background(background),
            contentScale = contentScale,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FitBackgroundOptions(argb: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Text("Fit background", style = MaterialTheme.typography.labelLarge)
    FlowRow(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FitBackgroundColors.entries.forEach { color ->
            val selected = FitBackgroundColors.fromArgb(argb) == color
            FilterChip(
                selected = selected,
                enabled = enabled,
                onClick = { onChange(color.argb) },
                label = { Text(color.label) },
                modifier = Modifier.testTag("fit-background-${color.name.lowercase()}"),
                leadingIcon = {
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(Color(color.argb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RotationOptionsControl(rotationMinutes: Long, enabled: Boolean, onChange: (Long) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 288.dp * LocalDensity.current.fontScale) 2 else 1
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = columns,
        ) {
            listOf(
                0L to "Manual only", 15L to "Every 15 minutes", 30L to "Every 30 minutes",
                1_440L to "Daily (24 hours)", -1L to "Every N hours",
            ).forEach { (minutes, label) ->
                val suffix = if (minutes == -1L) "custom" else minutes.toString()
                val selected = if (minutes == -1L) rotationMinutes >= 60L && rotationMinutes != 1_440L
                    else rotationMinutes == minutes
                Box(Modifier.weight(1f).testTag("rotation-option-$suffix")) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = selected, enabled = enabled, role = Role.RadioButton,
                            onClick = { onChange(if (minutes == -1L) 60L else minutes) },
                        ).testTag("rotation-radio-$suffix"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(enabled = enabled, selected = selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

internal fun isAttachedWidget(context: android.content.Context, id: Int): Boolean =
    AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ==
        ComponentName(context, PhotoWidgetReceiver::class.java)
