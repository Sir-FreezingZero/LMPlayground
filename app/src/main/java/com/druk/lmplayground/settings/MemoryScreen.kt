@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.MemoryNoteEntity
import com.druk.lmplayground.theme.menuOutline

/**
 * Settings -> Memory: whether new chats start with memory on (the memory
 * tool's default; a chat's ⋮ menu changes it for that chat), then the notes
 * the memory tool saved. Add, edit or delete them by hand, or clear them all.
 */
@Composable
fun MemoryScreen(
    notes: List<MemoryNoteEntity>,
    memoryDefault: Boolean,
    onMemoryDefaultChange: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (id: Long, text: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onClearAll: () -> Unit,
) {
    // null: editor closed; a note with id 0: a new note.
    var editing by remember { mutableStateOf<MemoryNoteEntity?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memory)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (notes.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.memory_clear_all))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = MemoryNoteEntity(content = "", createdAt = 0L) }) {
                Icon(Icons.Default.Add, stringResource(R.string.add))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsSection(R.string.default_memory) {
                CompactRow(
                    icon = Icons.Outlined.Bookmark,
                    title = stringResource(R.string.memory_default_title),
                    description = stringResource(R.string.memory_default_desc),
                    checked = memoryDefault,
                    onToggle = onMemoryDefaultChange,
                )
            }
            SettingsSection(R.string.saved_memories) {}
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.memory_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notes, key = { it.id }) { note -> MemoryCard(note) { editing = note } }
                }
            }
        }
    }

    editing?.let { note ->
        MemoryEditorSheet(
            note = note,
            onSave = { text -> if (note.id == 0L) onAdd(text) else onUpdate(note.id, text) },
            onDelete = if (note.id == 0L) null else ({ onDelete(note.id) }),
            onDismiss = { editing = null }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            modifier = Modifier.menuOutline(),
            title = { Text(stringResource(R.string.memory_clear_all)) },
            text = { Text(stringResource(R.string.memory_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = { onClearAll(); showClearConfirm = false }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

/** A note: its text, when it was last changed, and the tag the model gave it, if any. */
@Composable
private fun MemoryCard(note: MemoryNoteEntity, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            val changed = DateUtils.getRelativeTimeSpanString(
                maxOf(note.createdAt, note.updatedAt), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            Text(
                text = listOfNotNull(changed, note.category?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Bottom-sheet editor: a text box, with Delete for an existing note. */
@Composable
private fun MemoryEditorSheet(
    note: MemoryNoteEntity,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by remember(note.id) { mutableStateOf(note.content) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(if (onDelete == null) R.string.memory_new else R.string.memory_edit),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                placeholder = { Text(stringResource(R.string.memory_hint)) }
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    OutlinedButton(onClick = { onDelete(); onDismiss() }) { Text(stringResource(R.string.delete)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = text.isNotBlank(),
                    onClick = { onSave(text.trim()); onDismiss() }
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}
