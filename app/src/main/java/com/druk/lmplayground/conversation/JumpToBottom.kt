package com.druk.lmplayground.conversation

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/** Gap between the button and the chat's right edge, and above the input. */
val JUMP_TO_BOTTOM_MARGIN = 12.dp

private enum class Visibility {
    VISIBLE,
    GONE
}

/**
 * Shows a button that lets the user scroll to the bottom.
 */
@Composable
fun JumpToBottom(
    enabled: Boolean,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
    chromeColor: Color? = null
) {
    // Show Jump to Bottom button
    val transition = updateTransition(
        if (enabled) Visibility.VISIBLE else Visibility.GONE,
        label = "JumpToBottom visibility animation"
    )
    val bottomOffset by transition.animateDp(label = "JumpToBottom offset animation") {
        if (it == Visibility.GONE) {
            (-32).dp
        } else {
            // Same gap as from the right edge (see the caller's end padding).
            JUMP_TO_BOTTOM_MARGIN
        }
    }
    if (bottomOffset > 0.dp) {
        if (chromeColor != null) {
            // Chrome-colored fill with elevation. The fill is drawn inside the
            // Surface so its shadowElevation still casts a shadow.
            Surface(
                onClick = onClicked,
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface),
                modifier = modifier
                    .offset(x = 0.dp, y = -bottomOffset)
                    .size(40.dp)
            ) {
                // Just a down arrow.
                Box(
                    modifier = Modifier.background(chromeColor!!),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        modifier = Modifier.size(20.dp),
                        contentDescription = stringResource(id = R.string.jumpBottom)
                    )
                }
            }
        } else {
            SmallFloatingActionButton(
                onClick = onClicked,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = modifier
                    .offset(x = 0.dp, y = -bottomOffset)
                    .size(40.dp)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    modifier = Modifier.size(20.dp),
                    contentDescription = stringResource(id = R.string.jumpBottom)
                )
            }
        }
    }
}

@Preview
@Composable
fun JumpToBottomPreview() {
    JumpToBottom(enabled = true, onClicked = {})
}
