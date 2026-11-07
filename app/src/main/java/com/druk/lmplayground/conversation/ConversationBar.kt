@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.conversation

import com.druk.lmplayground.theme.menuBorder
import com.druk.lmplayground.theme.PopupMenuShape
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.AppBar
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.theme.PlaygroundTheme
import java.time.LocalDate
import kotlin.math.roundToInt

const val ConversationBarTestTag = "ConversationBarTestTag"

@Composable
fun ConversationBar(
    modelInfo: ModelInfo?,
    modelStatus: String? = null,
    modifier: Modifier = Modifier,
    colors: TopAppBarColors? = null,
    showNavIcon: Boolean = true,
    /**
     * Compact mode collapses the model name + status onto a single line so the
     * bar feels less heavy on tablet landscape, where vertical space is the
     * constrained dimension. The bar's own height stays at the Material 3
     * default (64dp), but the title content drops from ~50dp to ~28dp tall.
     */
    compact: Boolean = false,
    onModelNamePressed: () -> Unit = { },
    onNavIconPressed: () -> Unit = { },
    /** Opens the generation parameters; the ⋮ menu shows only when set (a model is loaded). */
    onGenerationParamsPressed: (() -> Unit)? = null,
    /** Open the same sheet on its System Prompt and Tools tabs. */
    onSystemPromptPressed: () -> Unit = {},
    onToolsPressed: () -> Unit = {},
    onExportPressed: () -> Unit = {},
    /** Tokens used and context size; shown as a wheel next to the ⋮ menu. */
    contextUsage: Pair<Int, Int>? = null,
) {
    // The token count pops up under the bar's right edge, so the title stays put.
    var showContextCount by remember { mutableStateOf(false) }
    Box(modifier) {
        AppBar(
            modifier = Modifier.testTag(ConversationBarTestTag),
            colors = colors,
            showNavIcon = showNavIcon,
            // Compact bar: shrink from M3 default 64dp to 40dp on tablet
            // landscape. The title content is a single line of titleMedium so it
            // fits; the trailing IconButton stays at its 40dp minimum tap target.
            expandedHeight = if (compact) 40.dp else Dp.Unspecified,
            onNavIconPressed = onNavIconPressed,
            title = {
                if (modelInfo != null) {
                    Surface(
                        onClick = onModelNamePressed,
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                    ) {
                        if (compact) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = modelInfo.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                val status = modelStatus ?: modelInfo.description
                                if (status.isNotBlank()) {
                                    Text(
                                        text = "  ·  $status",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Outlined.ArrowDropDown,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    contentDescription = null
                                )
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = modelInfo.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.ArrowDropDown,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        contentDescription = null
                                    )
                                }
                                Text(
                                    text = modelStatus ?: modelInfo.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        onClick = onModelNamePressed,
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.select_model),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                imageVector = Icons.Outlined.ArrowDropDown,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = null
                            )
                        }
                    }
                }
            },
            actions = {
                if (onGenerationParamsPressed != null) {
                    contextUsage?.let { ContextUsageMeter(it) { showContextCount = true } }
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(shape = PopupMenuShape, border = menuBorder, expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.generation_parameters)) },
                            onClick = {
                                showMenu = false
                                onGenerationParamsPressed()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.system_prompt)) },
                            onClick = {
                                showMenu = false
                                onSystemPromptPressed()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tools)) },
                            onClick = {
                                showMenu = false
                                onToolsPressed()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_chat)) },
                            onClick = {
                                showMenu = false
                                onExportPressed()
                            }
                        )
                    }
                }
            }
        )
        if (showContextCount && contextUsage != null) {
            val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize
                        ) = IntOffset(anchorBounds.right - popupContentSize.width - gap, anchorBounds.bottom + gap)
                    }
                },
                onDismissRequest = { showContextCount = false }
            ) {
                Surface(
                    shape = PopupMenuShape,
                    border = menuBorder,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 3.dp
                ) {
                    Text(
                        text = stringResource(R.string.context_usage, contextUsage.first, contextUsage.second),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** Context-window meter beside the ⋮ menu: the wheel with "used %" next to it. */
@Composable
private fun ContextUsageMeter(contextUsage: Pair<Int, Int>, onClick: () -> Unit) {
    val (contextUsed, contextTotal) = contextUsage
    val contextPercent = ((contextUsed.toFloat() / contextTotal) * 100f)
        .roundToInt().coerceIn(0, 100)
    ContextWindowIndicator(used = contextUsed, total = contextTotal, onClick = onClick)
    Text(
        text = "$contextPercent%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp)
    )
}

/**
 * A compact circular gauge of how full the model's context window is
 * (used / total KV tokens), shown in the top bar. The arc turns from
 * primary → tertiary → error as the window fills; tapping it calls [onClick].
 */
@Composable
private fun ContextWindowIndicator(
    used: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(targetValue = fraction, label = "contextFraction")
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val arcColor = when {
        fraction < 0.75f -> primary
        fraction < 0.9f -> tertiary
        else -> error
    }
    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val strokeWidth = 2.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

@Preview
@Composable
fun ChannelBarPreview() {
    PlaygroundTheme {
        ConversationBar(modelInfo = null)
    }
}

@Preview("Bar with model info")
@Composable
fun ChannelBarWithModelInfoPreview() {
    PlaygroundTheme {
        ConversationBar(
            modelInfo = ModelInfo(
                name = "Model Name Model Name Model Name Model Name Model Name",
                filename = "model.gguf",
                remoteUri = Uri.parse("https://example.com/model.gguf"),
                releaseDate = LocalDate.parse("2025-01-01"),
                description = "Model Description"
            ),
            modelStatus = "Loading 50%"
        )
    }
}
