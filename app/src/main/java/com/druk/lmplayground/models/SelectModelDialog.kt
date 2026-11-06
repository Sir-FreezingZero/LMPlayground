package com.druk.lmplayground.models

import com.druk.lmplayground.theme.menuBorder
import com.druk.lmplayground.theme.MenuCardShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.remote.RemoteServers

@Composable
fun SelectModelDialog(
    models: List<ModelWithStatus>,
    /** Filename of the loaded model, shown as selected; null when none is loaded. */
    loadedModelFilename: String? = null,
    /**
     * When > 0, the dialog Card is centered inside the chat pane rather than
     * the full window — used on tablet so the picker doesn't appear to cover
     * the sessions sidebar on its way to the chat area. Pass the sidebar's
     * effective width here (e.g. `320.dp`).
     */
    chatPaneStartOffset: Dp = 0.dp,
    chromeColor: Color? = null,
    onLoadModel: (ModelInfo) -> Unit,
    onUnloadModel: () -> Unit = {},
    onBrowseModels: () -> Unit,
    onDismissRequest: () -> Unit
) {
    // Only show downloaded models
    val downloadedModels = models.filter { it.isDownloaded }
    // Grouped by maker (A-Z), then sideloaded models, then server models.
    val other = stringResource(R.string.other_models)
    val remote = stringResource(R.string.remote_servers)
    val groups = downloadedModels
        .groupBy {
            when {
                RemoteServers.isRemote(it.model.filename) -> remote
                else -> MAKERS[it.model.logoRes] ?: other
            }
        }
        .toList()
        .sortedWith(compareBy({ (group, _) -> if (group == remote) 2 else if (group == other) 1 else 0 }, { it.first }))

    // Rendered as an in-composition overlay (not a platform Dialog), inside
    // the chat pane, so chatPaneStartOffset is no longer needed for centering.
    // Back press and taps on the scrim dismiss; taps on the card are consumed.
    BackHandler(onBack = onDismissRequest)

    // The overlay sits in the chat pane, not above the IME, so an open
    // keyboard covers the lower half of the list. Close it (and drop the
    // input's focus so it doesn't pop back) whenever the picker appears.
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            ),
        contentAlignment = Alignment.Center
    ) {
        val useChrome = chromeColor != null
        Surface(
            // The card caps at a comfortable 560dp on wide windows while
            // expanding to fill narrow phones, and at 80% of the height: a
            // long list scrolls between the fixed title and buttons.
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = maxHeight * 0.8f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = MenuCardShape,
            border = menuBorder,
            color = if (useChrome) Color.Transparent else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = if (useChrome) 0.dp else 6.dp,
            shadowElevation = 12.dp,
        ) {
            Box(
                modifier = if (useChrome) {
                    Modifier.background(chromeColor!!)
                } else Modifier
            ) {
            Column {
                // Header: title and a close button, underlined; the list scrolls between it and the buttons.
                Row(
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.select_model),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.select_model_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    if (downloadedModels.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.no_downloaded_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        groups.forEach { (group, groupModels) ->
                            val expanded = group !in collapsedGroups
                            item(key = "group:$group") {
                                GroupHeader(group, groupModels.size, expanded) {
                                    if (expanded) collapsedGroups += group else collapsedGroups -= group
                                }
                            }
                            if (expanded) items(
                                items = groupModels,
                                key = { it.model.filename }
                            ) { modelWithStatus ->
                                Model(
                                    model = modelWithStatus.model,
                                    // Only show the vision icon when the image module is
                                    // actually present, so the picker reflects whether
                                    // vision will really work once this model is loaded.
                                    vision = modelWithStatus.isMmprojDownloaded,
                                    selected = modelWithStatus.model.filename == loadedModelFilename,
                                ) {
                                    onDismissRequest()
                                    onLoadModel(modelWithStatus.model)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Spacer(modifier = Modifier.height(4.dp))
                FilledTonalButton(
                    colors = ButtonDefaults.filledTonalButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    onClick = {
                        onDismissRequest()
                        onBrowseModels()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(stringResource(R.string.browse_more_models))
                }
                if (loadedModelFilename != null) {
                    OutlinedButton(
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        onClick = {
                            onDismissRequest()
                            onUnloadModel()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Eject,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(R.string.unload_model))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        }
    }
}

/** The maker of each catalog model, by its logo. Names are brands, so not translated. */
private val MAKERS = mapOf(
    R.drawable.logo_deepseek to "DeepSeek",
    R.drawable.logo_google to "Google",
    R.drawable.logo_huggingface to "Hugging Face",
    R.drawable.logo_ibm to "IBM",
    R.drawable.logo_liquid to "Liquid AI",
    R.drawable.logo_meta to "Meta",
    R.drawable.logo_microsoft to "Microsoft",
    R.drawable.logo_mistral to "Mistral AI",
    R.drawable.logo_nvidia to "NVIDIA",
    R.drawable.logo_openai to "OpenAI",
    R.drawable.logo_openbmb to "OpenBMB",
    R.drawable.logo_qwen to "Qwen",
    R.drawable.logo_xhtoken to "XHToken",
)

/** Groups the user collapsed; kept while the app runs so the picker reopens the same way. */
private val collapsedGroups = mutableStateListOf<String>()

/** A group's name and model count; tapping it folds the group away or opens it. */
@Composable
private fun GroupHeader(name: String, count: Int, expanded: Boolean, onClick: () -> Unit) = Column(
    Modifier.clickable(onClick = onClick).padding(top = 8.dp, bottom = 4.dp)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "  $count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun Model(
    model: ModelInfo,
    vision: Boolean = model.isVision,
    /** The loaded model: outlined and tinted. */
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        border = if (selected) BorderStroke(1.dp, primary) else null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) primary.copy(alpha = 0.12f) else Color.Transparent
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (model.logoRes == 0) {
                // Server models have no logo: a computer, so the names still line up.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Image(
                    painter = painterResource(id = model.logoRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    maxLines = 1
                )
                // Description and release date on one line.
                Text(
                    text = listOfNotNull(
                        model.description,
                        model.releaseDate?.let { model.releaseDateLabel() }
                    ).joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        ModelCapabilityIcons(
            model = model,
            vision = vision,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}

@Preview
@Composable
fun SelectModelDialogPreview() {
    SelectModelDialog(
        models = ModelInfoProvider.allModels.take(3).mapIndexed { index, model ->
            ModelWithStatus(model = model, isDownloaded = index < 2)
        },
        onLoadModel = { },
        onBrowseModels = { },
        onDismissRequest = { }
    )
}
