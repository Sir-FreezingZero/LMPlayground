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
import com.druk.lmplayground.settings.ModelParamsControls
import com.druk.lmplayground.settings.PromptField
import com.druk.lmplayground.settings.PromptSection
import com.druk.lmplayground.settings.PromptStartSection
import com.druk.lmplayground.settings.SettingsSection
import com.druk.lmplayground.settings.ToolSwitches
import com.druk.llamacpp.tools.Tool
import kotlin.math.roundToInt

/**
 * Bottom sheet for this chat's settings, in three tabs:
 *   - Params: the model settings, shared by every model (see ModelParamsControls).
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
    /** Context above this may run the phone out of memory (see DeviceTier). */
    safeContextSize: Int = Int.MAX_VALUE,
    /** Replies longer than this may run the phone out of memory. */
    safeMaxTokens: Int = Int.MAX_VALUE,
    /** What Reset sets the reply length to. */
    recommendedMaxTokens: Int = GenerationParams.DEFAULT_MAX_TOKENS,
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
                    onParamsChange = { editedParams = it },
                    safeContextSize = safeContextSize,
                    safeMaxTokens = safeMaxTokens,
                    recommendedMaxTokens = recommendedMaxTokens,
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
    onParamsChange: (GenerationParams) -> Unit,
    safeContextSize: Int,
    safeMaxTokens: Int,
    recommendedMaxTokens: Int,
    maxHeight: Dp
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.model_params_all_models),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ModelParamsControls(
            params = params,
            onParamsChange = onParamsChange,
            maxContextSize = maxContextSize,
            supportsThinking = supportsThinking,
            safeContextSize = safeContextSize,
            safeMaxTokens = safeMaxTokens,
            contextChangeResets = baselineParams.contextSize,
            recommendedMaxTokens = recommendedMaxTokens,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
