package dev.localgallery.ui

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.localgallery.widget.R
import kotlin.math.roundToInt

@Composable
internal fun AppearanceDialog(
    appearance: GalleryAppearance,
    onApply: (GalleryAppearance) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember(appearance.seed) { colorHsv(appearance.seed) }
    var custom by rememberSaveable { mutableStateOf(appearance.custom) }
    var lastValidSeed by rememberSaveable { mutableIntStateOf(appearance.seed) }
    var hexText by rememberSaveable { mutableStateOf(formatHex(appearance.seed)) }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    val draft = GalleryAppearance(custom, lastValidSeed)
    val hexIsValid = parseHex(hexText) != null

    fun updateFromSeed(seed: Int) {
        val hsv = colorHsv(seed)
        lastValidSeed = seed
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    fun updateFromSliders() {
        // Retain hue and saturation when a slider makes the visible color gray or black.
        lastValidSeed = AndroidColor.HSVToColor(floatArrayOf(hue % 360f, saturation, brightness))
        hexText = formatHex(lastValidSeed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("appearance-content")
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.selectableGroup()) {
                    AppearanceOption(
                        label = stringResource(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                R.string.appearance_dialog_wallpaper
                            } else {
                                R.string.appearance_dialog_gallery_default
                            },
                        ),
                        selected = !custom,
                        tag = "appearance-default",
                        onClick = { custom = false },
                    )
                    AppearanceOption(
                        label = stringResource(R.string.appearance_dialog_custom),
                        selected = custom,
                        tag = "appearance-custom",
                        onClick = { custom = true },
                    )
                }
                AppearancePreview(draft)
                if (custom) {
                    AppearanceSwatches(lastValidSeed) { seed ->
                        updateFromSeed(seed)
                        hexText = formatHex(seed)
                    }
                    Column {
                        AppearanceSlider(
                            label = stringResource(R.string.appearance_dialog_hue),
                            amount = stringResource(R.string.appearance_dialog_degrees, hue.roundToInt()),
                            value = hue,
                            range = 0f..360f,
                            tag = "appearance-hue",
                            onValueChange = {
                                hue = it
                                updateFromSliders()
                            },
                        )
                        AppearanceSlider(
                            label = stringResource(R.string.appearance_dialog_saturation),
                            amount = stringResource(
                                R.string.appearance_dialog_percent,
                                (saturation * 100).roundToInt(),
                            ),
                            value = saturation,
                            tag = "appearance-saturation",
                            onValueChange = {
                                saturation = it
                                updateFromSliders()
                            },
                        )
                        AppearanceSlider(
                            label = stringResource(R.string.appearance_dialog_brightness),
                            amount = stringResource(
                                R.string.appearance_dialog_percent,
                                (brightness * 100).roundToInt(),
                            ),
                            value = brightness,
                            tag = "appearance-brightness",
                            onValueChange = {
                                brightness = it
                                updateFromSliders()
                            },
                        )
                    }
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { value ->
                            hexText = value
                            parseHex(value)?.let(::updateFromSeed)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("appearance-hex"),
                        label = { Text(stringResource(R.string.appearance_dialog_hex)) },
                        isError = !hexIsValid,
                        supportingText = if (!hexIsValid) {
                            { Text(stringResource(R.string.appearance_dialog_invalid_hex)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(draft) },
                enabled = !custom || hexIsValid,
                modifier = Modifier.testTag("appearance-apply"),
            ) { Text(stringResource(R.string.appearance_dialog_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun AppearanceOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSwatches(selectedSeed: Int, onSelect: (Int) -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (swatch in colorSwatches) {
            val selected = swatch.seed == selectedSeed
            val label = stringResource(swatch.label)
            val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected, role = Role.RadioButton) { onSelect(swatch.seed) }
                    .semantics { contentDescription = label }
                    .testTag("appearance-swatch-${swatch.key}"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp).border(2.dp, ring, CircleShape))
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Color(swatch.seed), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun AppearanceSlider(
    label: String,
    amount: String,
    value: Float,
    tag: String,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(amount, style = MaterialTheme.typography.bodyMedium)
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = amount
            }
            .testTag(tag),
    )
}

@Composable
private fun AppearancePreview(appearance: GalleryAppearance) {
    Text(
        stringResource(R.string.appearance_dialog_preview),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
    GalleryTheme(appearance) {
        val colors = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = ButtonDefaults.shape,
                color = colors.primary,
                contentColor = colors.onPrimary,
            ) {
                Text(
                    stringResource(R.string.appearance_dialog_preview_button),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ),
            ) {
                Text(
                    stringResource(R.string.appearance_dialog_preview_recovery),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun colorHsv(seed: Int): FloatArray = FloatArray(3).also { AndroidColor.colorToHSV(seed, it) }

private val hexDigits = Regex("[0-9A-Fa-f]{6}")

private fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    return if (hexDigits.matches(digits)) digits.toInt(16) or 0xFF000000.toInt() else null
}

private fun formatHex(seed: Int): String = "#" + (seed and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

private data class ColorSwatch(val key: String, @param:StringRes val label: Int, val seed: Int)

private val colorSwatches = listOf(
    ColorSwatch("forest", R.string.appearance_dialog_forest, 0xFF22644F.toInt()),
    ColorSwatch("ocean", R.string.appearance_dialog_ocean, 0xFF255BA9.toInt()),
    ColorSwatch("plum", R.string.appearance_dialog_plum, 0xFF774C96.toInt()),
    ColorSwatch("rose", R.string.appearance_dialog_rose, 0xFFAB435E.toInt()),
    ColorSwatch("amber", R.string.appearance_dialog_amber, 0xFFAE6D16.toInt()),
)
