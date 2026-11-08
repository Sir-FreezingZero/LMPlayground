package com.druk.lmplayground.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.GenerationParams
import kotlin.math.roundToInt

/**
 * The model settings every model uses, as in PrivateLM: temperature,
 * longest reply and context size, each with a warning past what's safe for
 * this phone. Thinking models also get the thinking limit.
 *
 * Used by Settings → Model and by the chat's settings sheet.
 */
@Composable
fun ModelParamsControls(
    params: GenerationParams,
    onParamsChange: (GenerationParams) -> Unit,
    /** Largest context the loaded model accepts (its training context). */
    maxContextSize: Int = GenerationParams.MAX_CONTEXT_SIZE,
    supportsThinking: Boolean = false,
    /** Context above this may run this phone out of memory. */
    safeContextSize: Int = Int.MAX_VALUE,
    /** Replies longer than this may run this phone out of memory. */
    safeMaxTokens: Int = Int.MAX_VALUE,
    /** Shown under the context slider when it differs from this (a change starts the chat over). */
    contextChangeResets: Int? = null,
    /** What Reset goes back to for the reply length. */
    recommendedMaxTokens: Int = GenerationParams.DEFAULT_MAX_TOKENS,
) {
    Column(Modifier.fillMaxWidth()) {
        ParamSlider(
            label = stringResource(R.string.temperature),
            value = params.temperature,
            valueRange = 0f..2f,
            steps = 19,
            valueDisplay = "%.1f".format(params.temperature),
            warning = if (params.temperature > 1f) stringResource(R.string.temperature_high_warning) else null,
            onValueChange = { onParamsChange(params.copy(temperature = (it * 10).roundToInt() / 10f)) },
        )

        ParamSlider(
            label = stringResource(R.string.max_tokens),
            value = params.maxTokens.toFloat(),
            valueRange = GenerationParams.MIN_MAX_TOKENS.toFloat()..GenerationParams.MAX_MAX_TOKENS.toFloat(),
            steps = (GenerationParams.MAX_MAX_TOKENS - GenerationParams.MIN_MAX_TOKENS) / 64 - 1,
            valueDisplay = "${params.maxTokens}",
            warning = if (params.maxTokens > safeMaxTokens) stringResource(R.string.max_tokens_warning) else null,
            onValueChange = {
                val snapped = ((it / 64).roundToInt() * 64)
                    .coerceIn(GenerationParams.MIN_MAX_TOKENS, GenerationParams.MAX_MAX_TOKENS)
                onParamsChange(params.copy(maxTokens = snapped))
            },
        )

        val contextMin = GenerationParams.MIN_CONTEXT_SIZE
        val contextMax = maxContextSize.coerceIn(contextMin, GenerationParams.MAX_CONTEXT_SIZE)
        val contextWarning = when {
            params.contextSize > safeContextSize -> stringResource(R.string.context_size_warning)
            contextChangeResets != null && params.contextSize != contextChangeResets ->
                stringResource(R.string.will_reset_conversation)
            else -> null
        }
        ParamSlider(
            label = stringResource(R.string.context_size),
            value = params.contextSize.coerceIn(contextMin, contextMax).toFloat(),
            valueRange = contextMin.toFloat()..contextMax.toFloat(),
            steps = ((contextMax - contextMin) / 512 - 1).coerceAtLeast(0),
            valueDisplay = "${params.contextSize}",
            warning = contextWarning,
            onValueChange = {
                val newContext = ((it / 512).roundToInt() * 512).coerceIn(contextMin, contextMax)
                // Keep the thinking limit the same share of the context.
                val budget = if (params.contextSize > 0) {
                    (params.thinkingBudget.toLong() * newContext / params.contextSize).toInt().coerceIn(64, newContext)
                } else newContext / 4
                onParamsChange(params.copy(contextSize = newContext, thinkingBudget = budget))
            },
        )

        if (supportsThinking) {
            ParamSlider(
                label = stringResource(R.string.thinking_budget),
                value = params.thinkingBudget.toFloat(),
                valueRange = 64f..params.contextSize.toFloat().coerceAtLeast(64f),
                steps = 0,
                valueDisplay = stringResource(R.string.tokens_value, params.thinkingBudget),
                onValueChange = {
                    val snapped = ((it / 64).roundToInt() * 64).coerceIn(64, params.contextSize)
                    onParamsChange(params.copy(thinkingBudget = snapped))
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            // The context stays, so Reset doesn't start the chat over.
            onClick = {
                onParamsChange(
                    GenerationParams(
                        contextSize = params.contextSize,
                        thinkingBudget = params.thinkingBudget,
                        maxTokens = recommendedMaxTokens,
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.reset)) }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueDisplay: String,
    warning: String? = null,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
