package com.druk.lmplayground.conversation

import android.content.ClipData
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HtmlArtifact"

/** Max height of the code view before it scrolls inside the card. */
private val CODE_MAX_HEIGHT = 320.dp

/** Height of the inline live preview. */
private val PREVIEW_HEIGHT = 420.dp

/**
 * An assistant reply: Markdown text, with every ```html code block shown as
 * an [HtmlArtifactCard] instead of plain code. Replies without HTML blocks,
 * or with live previews off (Settings -> General), render exactly as before
 * (one selectable Markdown text).
 */
@Composable
fun AssistantReplyContent(
    text: String,
    isGenerating: Boolean,
    textStyle: TextStyle,
) {
    // Re-read on resume, so the setting applies on returning from Settings.
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    var previews by remember { mutableStateOf(prefs.livePreviews) }
    LifecycleResumeEffect(Unit) {
        previews = prefs.livePreviews
        onPauseOrDispose {}
    }
    val segments = remember(text, previews) {
        if (previews) HtmlArtifacts.split(text) else listOf(HtmlArtifacts.Segment.Text(text))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (segment in segments) {
            when (segment) {
                is HtmlArtifacts.Segment.Text -> {
                    val styled = messageFormatter(text = segment.text, primary = false)
                    SelectionContainer {
                        Text(text = styled, style = textStyle)
                    }
                }
                is HtmlArtifacts.Segment.Artifact -> {
                    // key() by ordinal: the card keeps its own state (code vs
                    // preview) while the reply around it keeps streaming.
                    key(segment.ordinal) {
                        HtmlArtifactCard(
                            code = segment.code,
                            // An unclosed fence is still being written —
                            // unless generation stopped, then preview what
                            // there is.
                            writing = !segment.complete && isGenerating,
                            // The page may pull in sibling ```css / ```js
                            // blocks, so it is keyed on the whole reply.
                            documentKey = text,
                            buildDocument = { HtmlArtifacts.previewDocument(text, segment) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A web page the model wrote, shown as an artifact: the code first (streaming
 * in while the model writes), then a Live preview icon — in the header row
 * next to Download, Copy and Full screen — that runs it in a sandboxed
 * WebView. Open in browser hands it to the user's browser; Download saves it
 * as an .html file where the user chooses; Full screen adds Reload.
 */
@Composable
fun HtmlArtifactCard(
    code: String,
    writing: Boolean,
    documentKey: Any,
    buildDocument: () -> String,
    modifier: Modifier = Modifier,
) {
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageTitle = remember(code) { HtmlArtifacts.title(code) }
    val title = pageTitle.ifBlank { stringResource(R.string.html_artifact_default_title) }
    // Only build the document when it's going to be shown.
    val document = if ((showPreview || fullScreen) && !writing) {
        remember(documentKey) { buildDocument() }
    } else {
        null
    }

    // Download: Android's "save as" picker lets the user choose the folder
    // and file name; we then write the page there. The file is the same
    // document the live preview runs, so it opens identically in a browser.
    val savedMessage = stringResource(R.string.html_artifact_saved)
    val saveFailedMessage = stringResource(R.string.html_artifact_save_failed)
    val currentBuildDocument by rememberUpdatedState(buildDocument)
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // cancelled
        val page = currentBuildDocument()
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                        out.write(page.toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Exception) {
                    Log.w(TAG, "Saving HTML to $uri failed", e)
                    false
                }
            }
            Toast.makeText(
                context,
                if (saved) savedMessage else saveFailedMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Actions shared by the card's header and the full-screen top bar.
    val noBrowserMessage = stringResource(R.string.html_artifact_no_browser)
    val copyCode: () -> Unit = {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("HTML", code))) }
    }
    val download: () -> Unit = { saveLauncher.launch(HtmlArtifacts.fileName(pageTitle)) }
    val openInBrowser: () -> Unit = {
        val page = currentBuildDocument()
        scope.launch {
            if (!openPageInBrowser(context, page, HtmlArtifacts.fileName(pageTitle))) {
                Toast.makeText(context, noBrowserMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            // Header: one row. Title and status on the left; every action on
            // the right — Live preview <-> Code, Copy, Download, Open in
            // browser, Full screen.
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Web,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                // weight(1f): the title takes the space left by the icons and
                // ellipsizes rather than pushing them off the card.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp, end = 4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (writing) stringResource(R.string.html_artifact_writing) else "HTML",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // Live preview <-> Code. Tinted primary as the main action;
                // disabled until the model has finished writing the page.
                val previewing = showPreview && !writing
                CardIconButton(
                    icon = if (previewing) Icons.Outlined.Code else Icons.Outlined.PlayArrow,
                    description = stringResource(
                        if (previewing) R.string.html_artifact_code else R.string.html_artifact_live_preview,
                    ),
                    onClick = { showPreview = !showPreview },
                    enabled = !writing,
                    emphasized = true,
                )
                CardIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    description = stringResource(R.string.copy),
                    onClick = copyCode,
                )
                if (!writing) {
                    CardIconButton(
                        icon = Icons.Outlined.Download,
                        description = stringResource(R.string.html_artifact_download),
                        onClick = download,
                    )
                    CardIconButton(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        description = stringResource(R.string.html_artifact_open_in_browser),
                        onClick = openInBrowser,
                    )
                    CardIconButton(
                        icon = Icons.Outlined.Fullscreen,
                        description = stringResource(R.string.html_artifact_full_screen),
                        onClick = { fullScreen = true },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (showPreview && document != null) {
                // Switching back to Code and to Live preview again restarts
                // the page (and resets the notice); Full screen has Reload.
                var blocked by remember { mutableStateOf(false) }
                if (blocked) OfflineNotice()
                HtmlWebView(
                    html = document,
                    // Inside the scrolling chat the page gets the gesture,
                    // so drags (sliders, canvas games) work.
                    captureTouches = true,
                    onBlockedRequest = { blocked = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PREVIEW_HEIGHT),
                )
            } else {
                CodeView(
                    code = code,
                    followTail = writing,
                    modifier = Modifier.heightIn(max = CODE_MAX_HEIGHT),
                )
            }
        }
    }

    if (fullScreen && document != null) {
        HtmlFullScreenDialog(
            title = title,
            code = code,
            html = document,
            onCopy = copyCode,
            onDownload = download,
            onOpenInBrowser = openInBrowser,
            onDismiss = { fullScreen = false },
        )
    }
}

/**
 * Shown above a preview that tried to load something from the internet while
 * internet access for previews is off, so a half-styled page makes sense.
 */
@Composable
private fun OfflineNotice() {
    Text(
        text = stringResource(R.string.html_artifact_offline_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun CardIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasized: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(if (emphasized) 22.dp else 18.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.outline
                emphasized -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.secondary
            },
        )
    }
}

/**
 * The artifact's source, scrolling within the height [modifier] gives it
 * (capped at [CODE_MAX_HEIGHT] in the card, the whole screen in full
 * screen). While the model is writing it follows the newest line.
 */
@Composable
private fun CodeView(code: String, followTail: Boolean, modifier: Modifier = Modifier) {
    val vertical = rememberScrollState()
    if (followTail) {
        LaunchedEffect(Unit) {
            snapshotFlow { vertical.maxValue }.collect { vertical.scrollTo(it) }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(vertical)
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = LocalContentColor.current,
                ),
                softWrap = false,
            )
        }
    }
}

/**
 * Full-screen view of an artifact. Its top bar, in order: Live preview <->
 * Code, Copy, Download, Open in browser, then Reload (while previewing).
 * Opens on the live preview.
 */
@Composable
private fun HtmlFullScreenDialog(
    title: String,
    code: String,
    html: String,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showPreview by rememberSaveable { mutableStateOf(true) }
    var reloadCount by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                    // The title ellipsizes so the icons always fit.
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp),
                    )
                    CardIconButton(
                        icon = if (showPreview) Icons.Outlined.Code else Icons.Outlined.PlayArrow,
                        description = stringResource(
                            if (showPreview) R.string.html_artifact_code else R.string.html_artifact_live_preview,
                        ),
                        onClick = { showPreview = !showPreview },
                        emphasized = true,
                    )
                    CardIconButton(
                        icon = Icons.Outlined.ContentCopy,
                        description = stringResource(R.string.copy),
                        onClick = onCopy,
                    )
                    CardIconButton(
                        icon = Icons.Outlined.Download,
                        description = stringResource(R.string.html_artifact_download),
                        onClick = onDownload,
                    )
                    CardIconButton(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        description = stringResource(R.string.html_artifact_open_in_browser),
                        onClick = onOpenInBrowser,
                    )
                    // Reload is last; it only appears while previewing.
                    if (showPreview) {
                        CardIconButton(
                            icon = Icons.Outlined.Refresh,
                            description = stringResource(R.string.html_artifact_reload),
                            onClick = { reloadCount++ },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (showPreview) {
                    key(reloadCount) {
                        var blocked by remember { mutableStateOf(false) }
                        if (blocked) OfflineNotice()
                        HtmlWebView(
                            html = html,
                            captureTouches = false,
                            onBlockedRequest = { blocked = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                } else {
                    CodeView(
                        code = code,
                        followTail = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
