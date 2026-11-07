package com.druk.lmplayground.conversation

import androidx.compose.material3.Surface
import androidx.compose.material3.BottomSheetDefaults
import com.druk.lmplayground.theme.MenuCardShape
import com.druk.lmplayground.theme.menuBorder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.settings.PromptField
import com.druk.lmplayground.settings.PromptSection
import com.druk.lmplayground.settings.PromptStartSection
import com.druk.lmplayground.settings.SettingsSection
import com.druk.lmplayground.settings.ToolSwitches
import com.druk.llamacpp.tools.Tool
import kotlin.math.roundToInt

/**
 * Bottom sheet for this chat's settings, in three tabs:
 *   - Params: generation parameters (context size, sampling, advanced).
 *   - Prompt: the chat's own system prompt (empty: the default from
 *     Settings) and the exact text the chat starts with.
 *   - Tools: the chat's own tool switches, over the defaults from Settings.
 *
 * Edited params and the prompt accumulate across tab switches and commit
 * once when the sheet is dismissed; tool switches apply right away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerationParamsSheet(
    params: GenerationParams,
    maxContextSize: Int,
    supportsThinking: Boolean = false,
    onParamsChanged: (GenerationParams) -> Unit,
    /** What Reset goes back to: the model's recommended sampling. */
    recommendedParams: GenerationParams = GenerationParams(),
    systemPrompt: String = "",
    onSystemPromptChanged: (String) -> Unit = {},
    renderPromptStart: suspend () -> String? = { null },
    tools: List<Tool> = emptyList(),
    toolStates: Map<String, Boolean> = emptyMap(),
    onToolChanged: (String, Boolean) -> Unit = { _, _ -> },
    initialTab: Int = 0,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editedParams by remember(params) { mutableStateOf(params) }
    var editedPrompt by remember(systemPrompt) { mutableStateOf(systemPrompt) }
    // Hoisted so the expanded state survives a tab round-trip (the tab body is
    // disposed when another tab is selected).
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }

    // The sheet wraps its content and grows naturally — e.g. expanding the
    // Advanced params pushes the sheet up like a default bottom sheet.
    // [maxTabHeight] only caps very tall content so it scrolls instead of
    // clipping (rare — small screens / large fonts).
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxTabHeight = (screenHeightDp * 0.82f).dp

    ModalBottomSheet(
        onDismissRequest = {
            if (editedParams != params) {
                onParamsChanged(editedParams)
            }
            if (editedPrompt != systemPrompt) {
                onSystemPromptChanged(editedPrompt)
            }
            onDismiss()
        },
        sheetState = sheetState,
        // Floating card like the chat menu. The sheet itself is invisible and
        // the card (with its outline) is drawn inside it, so the outline moves
        // with the sheet as it slides and is dragged.
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shape = MenuCardShape,
        dragHandle = null,
    ) {
        Surface(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
            shape = MenuCardShape,
            color = BottomSheetDefaults.ContainerColor,
            border = menuBorder,
        ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
            // Transparent so the sheet's (lighter) container colour shows
            // through the tab row instead of the tab row painting its own
            // darker surface.
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent
            ) {
                listOf(R.string.tab_params, R.string.tab_prompt, R.string.tools).forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(stringResource(label)) }
                    )
                }
            }
            val tabModifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxTabHeight)
                .verticalScroll(rememberScrollState())
            when (selectedTab) {
                0 -> ParamsTab(
                    params = editedParams,
                    baselineParams = params,
                    maxContextSize = maxContextSize,
                    supportsThinking = supportsThinking,
                    showAdvanced = showAdvanced,
                    onShowAdvancedChange = { showAdvanced = it },
                    onParamsChange = { editedParams = it },
                    recommendedParams = recommendedParams,
                    maxHeight = maxTabHeight
                )
                1 -> Column(tabModifier) {
                    PromptSection(R.string.chat_system_prompt) {
                        PromptField(editedPrompt, stringResource(R.string.system_prompt_uses_default)) { editedPrompt = it }
                    }
                    PromptStartSection(renderPromptStart)
                }
                else -> Column(tabModifier) {
                    SettingsSection(R.string.chat_tools) {
                        ToolSwitches(tools, toolStates, onToolChanged)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ParamsTab(
    params: GenerationParams,
    baselineParams: GenerationParams,
    maxContextSize: Int,
    supportsThinking: Boolean,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    onParamsChange: (GenerationParams) -> Unit,
    recommendedParams: GenerationParams,
    maxHeight: Dp
) {
    val contextMin = 512
    val contextMax = maxContextSize.coerceAtLeast(512)
    val contextStep = 512

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Context Size
        val contextWarning = params.contextSize != baselineParams.contextSize
        ParamSlider(
            label = stringResource(R.string.context_size),
            value = params.contextSize.toFloat(),
            valueRange = contextMin.toFloat()..contextMax.toFloat(),
            steps = (((contextMax - contextMin) / contextStep) - 1).coerceAtLeast(0),
            valueDisplay = "${params.contextSize}",
            warning = if (contextWarning) stringResource(R.string.will_reset_conversation) else null,
            onValueChange = {
                val snapped = (it / contextStep).roundToInt() * contextStep
                val newContextSize = snapped.coerceIn(contextMin, contextMax)
                val oldContextSize = params.contextSize
                // Auto-scale thinking budget proportionally when context size changes
                val newBudget = if (params.thinkingBudget < 0) -1 else if (oldContextSize > 0) {
                    (params.thinkingBudget.toLong() * newContextSize / oldContextSize).toInt()
                        .coerceIn(64, newContextSize)
                } else {
                    newContextSize / 4
                }
                onParamsChange(params.copy(contextSize = newContextSize, thinkingBudget = newBudget))
            }
        )

        // Thinking Budget (only for thinking-capable models)
        if (supportsThinking) {
            // The far right of the slider is "no limit" (-1).
            val budgetMin = 64
            val budgetMax = params.contextSize
            ParamSlider(
                label = stringResource(R.string.thinking_budget),
                value = if (params.thinkingBudget < 0) budgetMax.toFloat() else params.thinkingBudget.toFloat(),
                valueRange = budgetMin.toFloat()..budgetMax.toFloat(),
                steps = 0,
                valueDisplay = if (params.thinkingBudget < 0) stringResource(R.string.thinking_no_limit)
                    else stringResource(R.string.tokens_value, params.thinkingBudget),
                onValueChange = {
                    val snapped = (it / 64).roundToInt() * 64
                    onParamsChange(
                        params.copy(thinkingBudget = if (snapped >= budgetMax) -1 else snapped.coerceIn(budgetMin, budgetMax))
                    )
                }
            )
        }

        // Temperature
        ParamSlider(
            label = stringResource(R.string.temperature),
            value = params.temperature,
            valueRange = 0f..2f,
            steps = 0,
            valueDisplay = "%.2f".format(params.temperature),
            onValueChange = {
                onParamsChange(params.copy(temperature = (it * 100).roundToInt() / 100f))
            }
        )

        // Top-P
        ParamSlider(
            label = stringResource(R.string.top_p),
            value = params.topP,
            valueRange = 0f..1f,
            steps = 0,
            valueDisplay = "%.2f".format(params.topP),
            subtitle = if (params.topP >= 1f) stringResource(R.string.disabled_label) else null,
            onValueChange = {
                onParamsChange(params.copy(topP = (it * 100).roundToInt() / 100f))
            }
        )

        // Repetition Penalty
        ParamSlider(
            label = stringResource(R.string.repetition_penalty),
            value = params.repetitionPenalty,
            valueRange = 1f..2f,
            steps = 0,
            valueDisplay = "%.2f".format(params.repetitionPenalty),
            subtitle = if (params.repetitionPenalty <= 1f) stringResource(R.string.disabled_label) else null,
            onValueChange = {
                onParamsChange(params.copy(repetitionPenalty = (it * 100).roundToInt() / 100f))
            }
        )

        // Advanced section
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowAdvancedChange(!showAdvanced) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.advanced),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (showAdvanced) stringResource(R.string.collapse) else stringResource(R.string.expand)
            )
        }

        AnimatedVisibility(visible = showAdvanced) {
            Column {
                // Top-K
                ParamSlider(
                    label = stringResource(R.string.top_k),
                    value = params.topK.toFloat(),
                    valueRange = 0f..200f,
                    steps = 0,
                    valueDisplay = "${params.topK}",
                    subtitle = if (params.topK == 0) stringResource(R.string.disabled_label) else null,
                    onValueChange = {
                        onParamsChange(params.copy(topK = it.roundToInt()))
                    }
                )

                // Min-P
                ParamSlider(
                    label = stringResource(R.string.min_p),
                    value = params.minP,
                    valueRange = 0f..0.5f,
                    steps = 0,
                    valueDisplay = "%.3f".format(params.minP),
                    subtitle = if (params.minP <= 0f) stringResource(R.string.disabled_label) else null,
                    onValueChange = {
                        onParamsChange(params.copy(minP = (it * 1000).roundToInt() / 1000f))
                    }
                )

                // Seed
                var seedText by remember(params.seed) {
                    mutableStateOf(if (params.seed < 0) "" else params.seed.toString())
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.seed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = seedText,
                        onValueChange = { text ->
                            seedText = text
                            val value = text.toIntOrNull() ?: -1
                            onParamsChange(params.copy(seed = value))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        placeholder = { Text(stringResource(R.string.random)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            // Sampling only: keeping the context size doesn't wipe the chat.
            onClick = { onParamsChange(recommendedParams.copy(contextSize = params.contextSize)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.reset))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: String,
    subtitle: String? = null,
    warning: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        text = " ($subtitle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
