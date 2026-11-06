package com.druk.lmplayground.conversation

import androidx.compose.foundation.background
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Messages(
    messages: List<Message>,
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // How far above the bottom edge the floating buttons (jump-to-bottom and
    // the model hint) should sit, so they clear the input dock that
    // now overlaps the bottom of the message list.
    bottomInset: Dp = 0.dp,
    chromeColor: Color? = null,
    isGenerating: Boolean = false,
    // Auto-scroll "follow the latest" flag, hoisted to the caller so the
    // jump-to-bottom button (now rendered above this list) can re-arm it.
    piningBottom: Boolean = false,
    onPiningBottomChange: (Boolean) -> Unit = {},
    sessionModelHint: Pair<String, String>? = null,
    onSessionModelHintClick: ((String) -> Unit)? = null,
    onSessionModelHintDismiss: (() -> Unit)? = null,
    onTokenCountClicked: (() -> Unit)? = null,
    // Non-null (the model name) when a vision model is loaded without its image
    // module — surfaces a one-time "Add image support" download pill.
    visionModuleHint: String? = null,
    onVisionModuleHintClick: (() -> Unit)? = null,
    onImageClick: (Uri) -> Unit = {},
    // Edit & re-send a user message (text = the new content).
    onEditMessage: ((Message, String) -> Unit)? = null,
    // Regenerate the latest assistant reply.
    onRegenerate: ((Message) -> Unit)? = null,
    // Continue up to an assistant reply in a new chat.
    onBranch: ((Message) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            onPiningBottomChange(false)
        })
    }) {
        LazyColumn(
            state = scrollState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
        ) {
            itemsIndexed(
                items = messages,
                key = { _, msg -> msg.id }
            ) { index, content ->
                val isLast = index == messages.lastIndex
                val isUser = content.author == "User"
                Message(
                    msg = content,
                    isUserMe = isUser,
                    showActions = !(isLast && isGenerating),
                    onTokenCountClicked = onTokenCountClicked,
                    onImageClick = onImageClick,
                    // History can't change under a running turn.
                    onEdit = if (isUser && !isGenerating && onEditMessage != null) {
                        { text -> onEditMessage(content, text) }
                    } else null,
                    onRegenerate = if (!isUser && isLast && !isGenerating && onRegenerate != null) {
                        { onRegenerate(content) }
                    } else null,
                    onBranch = if (!isUser && !isGenerating && onBranch != null) {
                        { onBranch(content) }
                    } else null,
                )
            }
            item {
                // Each Message already adds bottom = 8.dp padding, so this
                // trailing spacer only needs to be a small visual breathing
                // room (was 16.dp — doubled the gap to the input dock).
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // Auto scrolling to the latest item
        LaunchedEffect(piningBottom, scrollState.canScrollForward) {
            if (scrollState.canScrollForward && piningBottom) {
                scope.launch {
                    val totalItemsCount = scrollState.layoutInfo.totalItemsCount
                    scrollState.scrollToItem(totalItemsCount - 1)
                }
            }
        }

        // The jump-to-bottom button is rendered by the caller above all chrome.
        // This list still owns the transient "previously used model" hint pill,
        // which sits just above the input dock (chrome-colored, like the bars).
        if (sessionModelHint != null) {
            LaunchedEffect(sessionModelHint) {
                delay(3000)
                onSessionModelHintDismiss?.invoke()
            }
            val useChrome = chromeColor != null
            ExtendedFloatingActionButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        modifier = Modifier.height(18.dp),
                        contentDescription = null
                    )
                },
                text = {
                    Text(text = stringResource(R.string.previously_used_model, sessionModelHint.first))
                },
                onClick = { onSessionModelHintClick?.invoke(sessionModelHint.second) },
                shape = CircleShape,
                containerColor = if (useChrome) Color.Transparent else MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                elevation = if (useChrome) {
                    FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                } else {
                    FloatingActionButtonDefaults.elevation()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset)
                    .offset(x = 0.dp, y = (-32).dp)
                    .height(36.dp)
                    .then(
                        if (chromeColor != null) {
                            Modifier.clip(CircleShape).background(chromeColor!!)
                        } else Modifier
                    )
            )
        }

        // One-time offer to download a vision model's missing image module.
        // Mirrors the model-hint pill above, but is a CTA (no auto-dismiss) and
        // only shows when the model-hint pill isn't occupying the same slot.
        if (visionModuleHint != null && sessionModelHint == null) {
            val useChrome = chromeColor != null
            ExtendedFloatingActionButton(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        modifier = Modifier.height(18.dp),
                        contentDescription = null
                    )
                },
                text = {
                    Text(text = stringResource(R.string.vision_module_available))
                },
                onClick = { onVisionModuleHintClick?.invoke() },
                shape = CircleShape,
                containerColor = if (useChrome) Color.Transparent else MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                elevation = if (useChrome) {
                    FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                } else {
                    FloatingActionButtonDefaults.elevation()
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset)
                    .offset(x = 0.dp, y = (-32).dp)
                    .height(36.dp)
                    .then(
                        if (chromeColor != null) {
                            Modifier.clip(CircleShape).background(chromeColor!!)
                        } else Modifier
                    )
            )
        }
    }
}
