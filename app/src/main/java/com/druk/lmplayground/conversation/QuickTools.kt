package com.druk.lmplayground.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.druk.llamacpp.tools.Tool
import com.druk.lmplayground.R
import com.druk.lmplayground.settings.LOCATION_TOOL
import com.druk.lmplayground.settings.rememberLocationPermission
import com.druk.lmplayground.settings.toolIcon
import com.druk.lmplayground.settings.toolTitle
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.menuBorder

/**
 * Quick select tools, floating above the message box on the left: an arrow
 * bubble that slides out this chat's tools as a row of chips, scrolling
 * sideways. A chip turns its tool on or off for this chat, like the chat
 * sheet's Tools tab. Chips show only the icon unless Settings -> General ->
 * "Display full name of quick select tools" is on.
 */
@Composable
fun QuickTools(
    tools: List<Tool>,
    states: Map<String, Boolean>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Re-read on resume, so the setting applies on returning from Settings.
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    var showNames by remember { mutableStateOf(prefs.quickToolNames) }
    LifecycleResumeEffect(Unit) {
        showNames = prefs.quickToolNames
        onPauseOrDispose {}
    }
    val (locationGranted, askLocation) = rememberLocationPermission { granted ->
        if (granted) onToggle(LOCATION_TOOL, true)
    }
    Row(modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { open = !open },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = menuBorder,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (open) Icons.AutoMirrored.Outlined.KeyboardArrowLeft
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.quick_tools),
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            // Chips without the extra touch padding, so they are no taller than
            // the bubble and opening the row doesn't shift it.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(tools, key = { it.name }) { tool ->
                        // Location reads as on only while Android still grants it.
                        val on = states[tool.name] == true && (tool.name != LOCATION_TOOL || locationGranted)
                        val title = toolTitle(tool)
                        FilterChip(
                            selected = on,
                            onClick = {
                                if (tool.name == LOCATION_TOOL && !on && !locationGranted) askLocation?.invoke()
                                else onToggle(tool.name, !on)
                            },
                            label = {
                                Icon(toolIcon(tool), contentDescription = if (showNames) null else title, Modifier.size(18.dp))
                                if (showNames) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(title)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
