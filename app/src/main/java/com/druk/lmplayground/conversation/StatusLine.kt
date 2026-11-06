package com.druk.lmplayground.conversation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

enum class ChatStatus(val defaultColor: Color) {
    NO_MODEL(Color(0xFF5A5A5A)),
    READY(Color(0xFF81C784)),
    PROCESSING(Color(0xFFBA68C8)),
    THINKING(Color(0xFFF06292)),
    GENERATING(Color(0xFF64B5F6)),
    RUNNING_TOOLS(Color(0xFFFFB74D)),
    ERROR(Color(0xFFE57373)),
}

/**
 * The status line's colors. Settings → Appearance changes them, and the line
 * follows at once since they're Compose state. Loaded once at app start.
 */
object StatusLineColors {
    private val custom = mutableStateMapOf<ChatStatus, Color>()
    private val brightnessState = mutableFloatStateOf(0.75f)

    /**
     * 0 (dim) to 1 (full). Each point's glow is raised to a power from 2 down
     * to 0.3 by this, so the default 0.75 lights the line more widely than
     * the original look (about 0.59), while the centre stays the brightest.
     */
    var brightness: Float
        get() = brightnessState.floatValue
        private set(value) { brightnessState.floatValue = value }

    fun setBrightness(prefs: StoragePreferences, value: Float) {
        brightness = value.coerceIn(0f, 1f)
        prefs.statusLineBrightness = brightness
    }

    operator fun get(status: ChatStatus): Color = custom[status] ?: status.defaultColor

    val isCustomized: Boolean get() = custom.isNotEmpty()

    fun load(prefs: StoragePreferences) {
        ChatStatus.entries.forEach { status ->
            prefs.statusLineColor(status.name)?.let { custom[status] = Color(it) }
        }
        brightness = prefs.statusLineBrightness.coerceIn(0f, 1f)
    }

    /** Set [status]'s color, or reset it to the default when [color] is null. */
    fun set(prefs: StoragePreferences, status: ChatStatus, color: Color?) {
        if (color == null || color == status.defaultColor) custom.remove(status) else custom[status] = color
        prefs.setStatusLineColor(status.name, custom[status]?.toArgb())
    }
}

/**
 * The line under the top bar, ported from phone-mouse-keyboard's StatusLine. It glows
 * brightest in the centre. By default: grey = no model, green = ready, purple = processing
 * (reading the prompt or a tool result), pink = thinking, blue = writing the reply,
 * orange = tools, red = an error; see [StatusLineColors].
 * Ripples run along it while busy.
 * Returns its draw function.
 */
@Composable
fun rememberStatusLine(status: ChatStatus): DrawScope.() -> Unit {
    val busy = status != ChatStatus.NO_MODEL && status != ChatStatus.READY && status != ChatStatus.ERROR
    val color by animateColorAsState(StatusLineColors[status], tween(300), label = "status line colour")
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
        val gamma = 2f - 1.7f * StatusLineColors.brightness
        val stops = Array(61) { i ->
            val x = i / 60f
            val glow = (1f - abs(x - 0.5f) * 2f).pow(1.6f)
            val ripple = 0.5f + 0.5f * sin(2f * PI.toFloat() * (x * 5f - phase))
            val alpha = 0.12f + 0.88f * glow * (1f - waviness + waviness * (0.35f + 0.65f * ripple))
            x to color.copy(alpha = alpha.pow(gamma))
        }
        val height = 2.dp.toPx()
        drawRect(
            Brush.horizontalGradient(*stops),
            topLeft = Offset(0f, size.height - height),
            size = Size(size.width, height),
        )
    }
}
