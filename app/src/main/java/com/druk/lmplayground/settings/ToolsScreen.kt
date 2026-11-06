@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.druk.lmplayground.R
import com.druk.llamacpp.tools.Tool

/**
 * User-facing copy for a tool, keyed by [Tool.name]. The runtime
 * [Tool.description] is written for the model, not the user, so the settings
 * screen uses its own plain-language strings and a worked example.
 */
private data class ToolUiInfo(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val exampleRes: Int,
    val icon: ImageVector,
)

private val TOOL_UI: Map<String, ToolUiInfo> = mapOf(
    "run_javascript" to ToolUiInfo(
        R.string.tool_run_javascript_title,
        R.string.tool_run_javascript_desc,
        R.string.tool_run_javascript_example,
        Icons.Outlined.Code,
    ),
    "get_date_time" to ToolUiInfo(
        R.string.tool_get_date_time_title,
        R.string.tool_get_date_time_desc,
        R.string.tool_get_date_time_example,
        Icons.Outlined.Schedule,
    ),
    "web_search" to ToolUiInfo(
        R.string.tool_web_search_title,
        R.string.tool_web_search_desc,
        R.string.tool_web_search_example,
        Icons.Outlined.Search,
    ),
    "web_fetch" to ToolUiInfo(
        R.string.tool_web_fetch_title,
        R.string.tool_web_fetch_desc,
        R.string.tool_web_fetch_example,
        Icons.Outlined.Public,
    ),
    "set_alarm" to ToolUiInfo(
        R.string.tool_set_alarm_title,
        R.string.tool_set_alarm_desc,
        R.string.tool_set_alarm_example,
        Icons.Outlined.Alarm,
    ),
    "calculator" to ToolUiInfo(
        R.string.tool_calculator_title,
        R.string.tool_calculator_desc,
        R.string.tool_calculator_example,
        Icons.Outlined.Calculate,
    ),
    "convert_units" to ToolUiInfo(
        R.string.tool_convert_units_title,
        R.string.tool_convert_units_desc,
        R.string.tool_convert_units_example,
        Icons.Outlined.SwapHoriz,
    ),
    "device_info" to ToolUiInfo(
        R.string.tool_device_info_title,
        R.string.tool_device_info_desc,
        R.string.tool_device_info_example,
        Icons.Outlined.Smartphone,
    ),
    "wikipedia" to ToolUiInfo(
        R.string.tool_wikipedia_title,
        R.string.tool_wikipedia_desc,
        R.string.tool_wikipedia_example,
        Icons.AutoMirrored.Outlined.MenuBook,
    ),
    "location" to ToolUiInfo(
        R.string.tool_location_title,
        R.string.tool_location_desc,
        R.string.tool_location_example,
        Icons.Outlined.LocationOn,
    ),
    "memory" to ToolUiInfo(
        R.string.tool_memory_title,
        R.string.tool_memory_desc,
        R.string.tool_memory_example,
        Icons.Outlined.Bookmark,
    ),
)

@Composable
fun ToolsScreen(
    tools: List<Tool>,
    enabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    onBackClick: () -> Unit,
    /** Opens the tool test (see ToolTest.kt); no button when null. */
    onRunToolTest: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (onRunToolTest != null) {
                        IconButton(onClick = onRunToolTest) {
                            Icon(Icons.Outlined.Science, contentDescription = "Test tools")
                        }
                    }
                }
            )
        }
    ) { padding ->
        ToolsContent(
            tools = tools,
            enabledStates = enabledStates,
            onToolEnabledChanged = onToolEnabledChanged,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/** The tool that needs a runtime permission before it can switch on. */
internal const val LOCATION_TOOL = "location"

/** A tool's user-facing name, falling back to its runtime name. */
@Composable
internal fun toolTitle(tool: Tool): String = TOOL_UI[tool.name]?.let { stringResource(it.titleRes) } ?: tool.name

/** A tool's icon, with a generic one for a tool without its own. */
internal fun toolIcon(tool: Tool): ImageVector = TOOL_UI[tool.name]?.icon ?: Icons.Outlined.Build

/**
 * Whether the app holds the location permission, re-checked whenever the
 * screen resumes (e.g. back from Android's settings), and a request for it;
 * [onResult] gets the answer. Once Android no longer shows its dialog (denied
 * twice, or "Don't ask again") the request opens the app's settings page
 * instead. The request is null where there is nothing to ask with
 * (previews, the store-screenshot tests).
 */
@Composable
internal fun rememberLocationPermission(onResult: (Boolean) -> Unit): Pair<Boolean, (() -> Unit)?> {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose {}
    }
    val granted = remember(checks) {
        runCatching {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }
    val result by rememberUpdatedState(onResult)
    var askedAt by remember { mutableLongStateOf(0L) }
    val launcher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            checks++
            // An answer within a moment means Android refused without asking.
            if (!ok && System.currentTimeMillis() - askedAt < 500) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            result(ok)
        }
    } else null
    return granted to launcher?.let { l ->
        {
            askedAt = System.currentTimeMillis()
            l.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

/**
 * "Default tools": [enabledStates], used by every chat without its own
 * setting from the chat's ⋮ menu.
 */
@Composable
fun ToolsContent(
    tools: List<Tool>,
    enabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        SettingsSection(R.string.default_tools) {
            ToolSwitches(tools, enabledStates, onToolEnabledChanged)
        }
    }
}

/**
 * "Enable all" and one row per tool with [states]. Location needs a runtime
 * permission: turning it on asks for ACCESS_COARSE_LOCATION, and it only
 * switches on if the user grants it.
 */
@Composable
internal fun ToolSwitches(
    tools: List<Tool>,
    states: Map<String, Boolean>,
    onChange: (String, Boolean) -> Unit,
) {
    val (locationGranted, askLocation) = rememberLocationPermission { granted ->
        if (granted) onChange(LOCATION_TOOL, true)
    }
    val onToggle: (String, Boolean) -> Unit = { name, on ->
        if (name == LOCATION_TOOL && on && !locationGranted) {
            askLocation?.invoke()
        } else {
            onChange(name, on)
        }
    }
    // "Enable all" leaves Location alone until its permission is granted,
    // so it never pops a permission dialog; switching off touches every tool.
    val bulkTools = tools.filter { it.name != LOCATION_TOOL || locationGranted }
    val allOn = bulkTools.isNotEmpty() && bulkTools.all { states[it.name] == true }
    val setAll: (Boolean) -> Unit = { on -> (if (on) bulkTools else tools).forEach { onChange(it.name, on) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { setAll(!allOn) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.tools_enable_all),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        CompactSwitch(checked = allOn, onCheckedChange = setAll)
    }
    tools.forEach { tool ->
        ToolRow(
            tool = tool,
            // Location reads as on only while Android still grants it (an
            // "Only this time" grant lapses), so a tap can ask again.
            checked = (states[tool.name] ?: false) && (tool.name != LOCATION_TOOL || locationGranted),
            onToggle = { onToggle(tool.name, it) },
        )
    }
}

/** A tool's icon, name and switch; tapping the row shows what it does. */
@Composable
private fun ToolRow(
    tool: Tool,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val ui = TOOL_UI[tool.name]
    CompactRow(
        icon = ui?.icon,
        title = toolTitle(tool),
        description = if (ui != null) stringResource(ui.descRes) else tool.description,
        example = ui?.let { stringResource(R.string.tools_example_label, stringResource(it.exampleRes)) },
        checked = checked,
        onToggle = onToggle,
    )
}
