package com.druk.lmplayground.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

// Ported from the phone-mouse-keyboard header's status line.

private val ReadyGreen = Color(0xFF81C784)
private val GeneratingBlue = Color(0xFF64B5F6)
private val ToolsOrange = Color(0xFFFFB74D)
private val IdleGrey = Color(0xFF5A5A5A)

private val LineHeight = 2.dp

/** The line is drawn as this many short pieces, each with its own brightness. */
private const val SEGMENTS = 120

/** Brightness at the very ends of the line, where it is dimmest. */
private const val EDGE_BRIGHTNESS = 0.12f

/** How sharply the brightness rises towards the centre. Higher = narrower glow. */
private const val GLOW_SHARPNESS = 1.6f

/** While busy: how many ripples fit across the line, and how fast they run. */
private const val RIPPLES_ACROSS_LINE = 5f
private const val RIPPLE_PERIOD_MILLIS = 900

private const val TRANSITION_MILLIS = 300

enum class ChatStatus { NO_MODEL, READY, GENERATING, RUNNING_TOOLS }

/** Animated values for [drawStatusLine]; read only while drawing, so changes just redraw. */
class StatusLineState(
    val color: State<Color>,
    val waviness: State<Float>,
    val ripplePhase: State<Float>,
)

/**
 * The line under the chat's top bar, which doubles as a status light. It glows brightest in
 * the centre:
 *
 *  - grey:   no model loaded
 *  - green:  ready for a message
 *  - blue:   generating, with ripples running along it
 *  - orange: tools running, with ripples running along it
 */
@Composable
fun rememberStatusLine(status: ChatStatus): StatusLineState {
    val busy = status == ChatStatus.GENERATING || status == ChatStatus.RUNNING_TOOLS

    val color = animateColorAsState(
        targetValue = when (status) {
            ChatStatus.NO_MODEL -> IdleGrey
            ChatStatus.READY -> ReadyGreen
            ChatStatus.GENERATING -> GeneratingBlue
            ChatStatus.RUNNING_TOOLS -> ToolsOrange
        },
        animationSpec = tween(TRANSITION_MILLIS),
        label = "status line colour",
    )

    // How much of the ripple effect is mixed in: 0 = still glow, 1 = full ripples.
    val waviness = animateFloatAsState(
        targetValue = if (busy) 1f else 0f,
        animationSpec = tween(TRANSITION_MILLIS),
        label = "status line waviness",
    )

    // The ripples' position along the line, 0 to 1. Only animated while busy,
    // so the line costs nothing when idle.
    val ripplePhase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(busy) {
        while (busy) {
            withFrameMillis { frameMillis ->
                ripplePhase.floatValue =
                    (frameMillis % RIPPLE_PERIOD_MILLIS) / RIPPLE_PERIOD_MILLIS.toFloat()
            }
        }
    }

    return remember { StatusLineState(color, waviness, ripplePhase) }
}

/** Draws the status line along the bottom edge of the current draw area. */
fun DrawScope.drawStatusLine(state: StatusLineState) {
    val height = LineHeight.toPx()
    val top = size.height - height
    val color = state.color.value
    val phase = state.ripplePhase.value
    val waviness = state.waviness.value
    val segmentWidth = size.width / SEGMENTS
    for (index in 0 until SEGMENTS) {
        val position = (index + 0.5f) / SEGMENTS
        drawRect(
            color = color.copy(alpha = brightnessAt(position, phase, waviness)),
            topLeft = Offset(index * segmentWidth, top),
            // A hair wider than needed, so no gaps show between pieces.
            size = Size(segmentWidth + 1f, height),
        )
    }
}

/**
 * Brightness (0–1) at [position] along the line (0 = left end, 1 = right end): a glow peaking
 * in the centre, optionally modulated by ripples travelling left to right.
 */
private fun brightnessAt(position: Float, ripplePhase: Float, waviness: Float): Float {
    val distanceFromCentre = abs(position - 0.5f) * 2f
    val glow = (1f - distanceFromCentre).pow(GLOW_SHARPNESS)

    val ripple = 0.5f + 0.5f * sin(2f * PI.toFloat() * (position * RIPPLES_ACROSS_LINE - ripplePhase))
    val rippleFactor = 1f - waviness + waviness * (0.35f + 0.65f * ripple)

    return EDGE_BRIGHTNESS + (1f - EDGE_BRIGHTNESS) * glow * rippleFactor
}
