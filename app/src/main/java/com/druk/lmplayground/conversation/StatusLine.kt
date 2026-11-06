package com.druk.lmplayground.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

enum class ChatStatus { NO_MODEL, READY, PROCESSING, GENERATING, RUNNING_TOOLS }

/**
 * The line under the top bar, ported from phone-mouse-keyboard's StatusLine. It glows
 * brightest in the centre: grey = no model, green = ready, purple = processing (reading
 * or thinking), blue = writing the reply, orange = tools. Ripples run along it while busy.
 * Returns its draw function.
 */
@Composable
fun rememberStatusLine(status: ChatStatus): DrawScope.() -> Unit {
    val busy = status != ChatStatus.NO_MODEL && status != ChatStatus.READY
    val color by animateColorAsState(
        when (status) {
            ChatStatus.NO_MODEL -> Color(0xFF5A5A5A)
            ChatStatus.READY -> Color(0xFF81C784)
            ChatStatus.PROCESSING -> Color(0xFFBA68C8)
            ChatStatus.GENERATING -> Color(0xFF64B5F6)
            ChatStatus.RUNNING_TOOLS -> Color(0xFFFFB74D)
        },
        tween(300), label = "status line colour",
    )
    // How much ripple is mixed in: 0 = still glow, 1 = full ripples.
    val waviness by animateFloatAsState(if (busy) 1f else 0f, tween(300), label = "status line waviness")
    // Ripple position along the line, 0 to 1; only animated while busy, and
    // at about 30 fps rather than every frame, to leave the CPU to the model.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(busy) {
        while (busy) {
            withFrameMillis { phase = (it % 900) / 900f }
            delay(25)
        }
    }

    return {
        // One smooth gradient: brightness at 61 points, glowing towards the centre.
        val stops = Array(61) { i ->
            val x = i / 60f
            val glow = (1f - abs(x - 0.5f) * 2f).pow(1.6f)
            val ripple = 0.5f + 0.5f * sin(2f * PI.toFloat() * (x * 5f - phase))
            val alpha = 0.12f + 0.88f * glow * (1f - waviness + waviness * (0.35f + 0.65f * ripple))
            x to color.copy(alpha = alpha)
        }
        val height = 2.dp.toPx()
        drawRect(
            Brush.horizontalGradient(*stops),
            topLeft = Offset(0f, size.height - height),
            size = Size(size.width, height),
        )
    }
}
