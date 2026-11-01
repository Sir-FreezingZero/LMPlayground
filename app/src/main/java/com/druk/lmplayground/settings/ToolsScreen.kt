@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
)

@Composable
fun ToolsScreen(
    tools: List<Tool>,
    enabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    onBackClick: () -> Unit,
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

/** One row per tool with [states]. */
@Composable
internal fun ToolSwitches(
    tools: List<Tool>,
    states: Map<String, Boolean>,
    onChange: (String, Boolean) -> Unit,
) {
    tools.forEach { tool ->
        ToolRow(
            tool = tool,
            checked = states[tool.name] ?: false,
            onToggle = { onChange(tool.name, it) },
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
        title = if (ui != null) stringResource(ui.titleRes) else tool.name,
        description = if (ui != null) stringResource(ui.descRes) else tool.description,
        example = ui?.let { stringResource(R.string.tools_example_label, stringResource(it.exampleRes)) },
        checked = checked,
        onToggle = onToggle,
    )
}
