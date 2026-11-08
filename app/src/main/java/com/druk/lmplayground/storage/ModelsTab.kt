package com.druk.lmplayground.storage

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.ui.GroupCard
import com.druk.lmplayground.ui.GroupDivider
import com.druk.lmplayground.ui.PillButton
import com.druk.lmplayground.ui.RowIcon
import com.druk.lmplayground.ui.SectionLabel
import com.druk.lmplayground.ui.StatusBadge
import com.druk.lmplayground.ui.TabBottomSpace
import com.druk.lmplayground.ui.TabColors

/** What the Models tab shows about the model the chat has loaded. */
data class LoadedModelState(
    val model: ModelInfo,
    val ready: Boolean,
    val onGpu: Boolean,
)

/**
 * Models tab → My models: the loaded model (with Unload) and the models on
 * this phone, grouped Chat / Vision, each with Load. Long-press a model to
 * delete it. Laid out like the cross-platform-llm-client's Models screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MyModelsSection(
    models: List<ModelWithStatus>,
    fileSizes: Map<String, Long>,
    loaded: LoadedModelState?,
    busy: Boolean,
    onLoad: (ModelInfo) -> Unit,
    onUnload: () -> Unit,
    onDelete: (ModelInfo) -> Unit,
    onBrowse: () -> Unit,
) {
    val context = LocalContext.current
    var bySize by remember { mutableStateOf(true) }
    var toDelete by remember { mutableStateOf<ModelInfo?>(null) }
    val onPhone = models.filter { it.isDownloaded }
        .let { list ->
            if (bySize) list.sortedByDescending { fileSizes[it.model.filename] ?: 0L }
            else list.sortedBy { it.model.name.lowercase() }
        }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (loaded != null) {
            SectionLabel(stringResource(R.string.models_tab_loaded))
            GroupCard {
                Row(
                    Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowIcon(Icons.Outlined.Memory)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            loaded.model.filename.substringAfterLast('|').removeSuffix(".gguf"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (loaded.ready) TabColors.Green else Color(0xFFFFCC00))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (!loaded.ready) stringResource(R.string.models_tab_loading)
                                else stringResource(
                                    R.string.models_tab_loaded_on,
                                    if (loaded.onGpu) "GPU" else "CPU",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    PillButton(stringResource(R.string.models_tab_unload), primary = false, enabled = !busy, onClick = onUnload)
                }
            }
        }

        SectionLabel(stringResource(R.string.models_tab_on_phone, onPhone.size)) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { bySize = !bySize })
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(if (bySize) R.string.models_tab_by_size else R.string.models_tab_by_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (onPhone.isEmpty()) {
            GroupCard {
                Column(Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 10.dp)) {
                    Text(stringResource(R.string.models_tab_empty), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    PillButton(stringResource(R.string.models_tab_browse), onClick = onBrowse)
                }
            }
        }

        listOf(
            R.string.models_tab_group_chat to onPhone.filter { !it.model.isVision },
            R.string.models_tab_group_vision to onPhone.filter { it.model.isVision },
        ).filter { it.second.isNotEmpty() }.forEach { (label, group) ->
            SectionLabel(stringResource(label))
            GroupCard {
                group.forEachIndexed { i, item ->
                    if (i > 0) GroupDivider(indent = true)
                    val isLoaded = loaded?.model?.filename == item.model.filename
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = {}, onLongClick = { toDelete = item.model })
                            .padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Monogram(item.model.name, showDot = isLoaded)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.model.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    fileSizes[item.model.filename]?.let { Formatter.formatFileSize(context, it) },
                                    quantOf(item.model.filename),
                                    if (item.model.isCustom) stringResource(R.string.models_tab_imported) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (isLoaded) {
                            StatusBadge(stringResource(R.string.models_tab_loaded))
                        } else {
                            PillButton(stringResource(R.string.models_tab_load), enabled = !busy) { onLoad(item.model) }
                        }
                    }
                }
            }
        }
        TabBottomSpace()
    }

    toDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.models_tab_delete_title)) },
            text = { Text(stringResource(R.string.models_tab_delete_message, model.name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(model); toDelete = null }) {
                    Text(stringResource(R.string.models_tab_delete), color = TabColors.Red)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Models tab → Cloud: a placeholder until cloud models are added. */
@Composable
fun CloudModelsPlaceholder() {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(10.dp))
        GroupCard {
            Row(Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Outlined.Cloud)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.models_tab_cloud_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.coming_soon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The quantization in a GGUF file name ("Q4_K_M", "IQ4_XS"), if any. */
fun quantOf(filename: String): String? =
    Regex("""(?i)(IQ\d_[A-Z]+|Q\d_K_[SML]|Q\d_K|Q\d_\d|Q\d|BF16|F16|F32)""")
        .findAll(filename.substringAfterLast('/')).lastOrNull()?.value?.uppercase()

/** A rounded square with the model name's first letters, coloured by name. */
@Composable
private fun Monogram(name: String, showDot: Boolean) {
    val palette = listOf(
        Color(0xFF123B36) to Color(0xFF5ED6C4),
        Color(0xFF2E2552) to Color(0xFFB39DFF),
        Color(0xFF3D2A12) to Color(0xFFFFB960),
        Color(0xFF14304D) to Color(0xFF6CB8FF),
        Color(0xFF3D1A22) to Color(0xFFFF8A9A),
    )
    val (bg, fg) = palette[Math.floorMod(name.hashCode(), palette.size)]
    val letters = name.filter { it.isLetterOrDigit() }.take(2).replaceFirstChar { it.uppercase() }
    Box {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(letters, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        if (showDot) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(TabColors.Green)
            )
        }
    }
}

/** The "+ Import" icon, shared with the fragment. */
val ImportIcon = Icons.Filled.Add
