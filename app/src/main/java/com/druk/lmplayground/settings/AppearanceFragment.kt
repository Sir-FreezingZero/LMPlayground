@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.ChatStatus
import com.druk.lmplayground.conversation.StatusLineColors
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.PlaygroundTheme

/**
 * Settings -> Appearance: the theme, and the status line's color for each
 * state. Same style as General (headed sections of compact rows).
 */
class AppearanceFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                AppearanceScreen(onBackClick = { findNavController().popBackStack() })
            }
        }
    }
}

/** Apply a saved [StoragePreferences.themeMode]; recreates open screens when it changes. */
fun applyThemeMode(mode: Int) = AppCompatDelegate.setDefaultNightMode(
    when (mode) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
)

/**
 * Swatches for each status line state: the standard 12-color wheel in order
 * (primary red, yellow and blue; secondary orange, green and violet; and the
 * six tertiaries between them), then brown, white, gray and black. The last
 * swatch (a rainbow) opens [CustomColorDialog] for any other color.
 */
private val SWATCHES = listOf(
    0xFFFE2712, // red
    0xFFFC600A, // red-orange
    0xFFFB9902, // orange
    0xFFFCCC1A, // yellow-orange
    0xFFFEFE33, // yellow
    0xFFB2D732, // yellow-green
    0xFF66B032, // green
    0xFF347C98, // blue-green
    0xFF0247FE, // blue
    0xFF4424D6, // blue-violet
    0xFF8601AF, // violet
    0xFFC21460, // red-violet
    0xFF8B4513, // brown
    0xFFFFFFFF, // white
    0xFF808080, // gray
    0xFF000000, // black
).map { Color(it) }

/** The full hue circle, for the rainbow swatch and the hue bar. */
private val RAINBOW = (0..6).map { Color.hsv(it * 59.99f, 1f, 1f) }

@Composable
private fun AppearanceScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(R.string.appearance_theme) {
                var mode by remember { mutableStateOf(prefs.themeMode) }
                listOf(
                    Triple(0, Icons.Outlined.BrightnessAuto, R.string.appearance_theme_system),
                    Triple(1, Icons.Outlined.LightMode, R.string.appearance_theme_light),
                    Triple(2, Icons.Outlined.DarkMode, R.string.appearance_theme_dark),
                ).forEach { (value, icon, label) ->
                    ChoiceRow(icon, stringResource(label), selected = mode == value) {
                        if (mode != value) {
                            mode = value
                            prefs.themeMode = value
                            applyThemeMode(value)
                        }
                    }
                }
            }
            SettingsSection(R.string.appearance_status_line) {
                // Brightness: one slider for the whole line, applied live.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.BrightnessMedium,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.status_brightness),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Slider(
                        value = StatusLineColors.brightness,
                        onValueChange = { StatusLineColors.setBrightness(prefs, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
                listOf(
                    ChatStatus.NO_MODEL to R.string.status_no_model,
                    ChatStatus.READY to R.string.status_ready,
                    ChatStatus.PROCESSING to R.string.processing,
                    ChatStatus.THINKING to R.string.thinking,
                    ChatStatus.GENERATING to R.string.status_writing,
                    ChatStatus.RUNNING_TOOLS to R.string.status_tools,
                    ChatStatus.ERROR to R.string.status_error,
                ).forEach { (status, label) ->
                    ColorRow(stringResource(label), StatusLineColors[status], status.defaultColor) {
                        StatusLineColors.set(prefs, status, it)
                    }
                }
                if (StatusLineColors.isCustomized) {
                    TextButton(
                        onClick = { ChatStatus.entries.forEach { StatusLineColors.set(prefs, it, null) } },
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Text(stringResource(R.string.status_reset)) }
                }
            }
        }
    }
}

/** An icon, a name and a radio button, like [CompactRow] with a radio instead of a switch. */
@Composable
private fun ChoiceRow(icon: ImageVector, title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

/** A color dot and a name; tapping it opens the swatches in a card below. */
@Composable
private fun ColorRow(title: String, color: Color, default: Color, onPick: (Color) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }
    if (showCustom) {
        CustomColorDialog(title, color, onDismiss = { showCustom = false }) {
            onPick(it)
            showCustom = false
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            // Its own raised card, so the picker reads as separate from the rows.
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            ) {
                FlowRow(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SWATCHES.forEach { swatch ->
                        Swatch(Modifier.background(swatch), selected = swatch == color) { onPick(swatch) }
                    }
                    // Any other color: a rainbow circle, ringed when the color is custom.
                    Swatch(
                        Modifier.background(Brush.sweepGradient(RAINBOW)),
                        selected = color !in SWATCHES && color != default,
                    ) { showCustom = true }
                }
            }
        }
    }
}

/** One round swatch; [fill] paints it, and [selected] adds a ring. */
@Composable
private fun Swatch(fill: Modifier, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(fill)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            )
            .clickable(onClick = onClick)
    )
}

/**
 * Any color, in a pop-up: a large preview and three bars for hue, vividness
 * (saturation) and brightness, each drawn with the range it covers. Nothing
 * changes until Save.
 */
@Composable
private fun CustomColorDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onSave: (Color) -> Unit,
) {
    val start = remember { hsvOf(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val picked = Color.hsv(hue, sat, value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(picked)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Spacer(Modifier.height(16.dp))
                GradientSlider(hue / 360f, RAINBOW) { hue = it * 360f }
                GradientSlider(sat, listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value))) { sat = it }
                GradientSlider(value, listOf(Color.Black, Color.hsv(hue, sat, 1f))) { value = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun hsvOf(color: Color) = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }

/** A slider whose track is a gradient of [colors]; [onChange] gets 0..1. */
@Composable
private fun GradientSlider(fraction: Float, colors: List<Color>, onChange: (Float) -> Unit) {
    Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.horizontalGradient(colors))
        )
        Slider(
            value = fraction,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
        )
    }
}

