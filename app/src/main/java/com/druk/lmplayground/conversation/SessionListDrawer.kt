package com.druk.lmplayground.conversation

import com.druk.lmplayground.theme.menuOutline
import com.druk.lmplayground.theme.menuBorder
import com.druk.lmplayground.theme.MenuCardShape
import com.druk.lmplayground.theme.PopupMenuShape
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.ChatFolderEntity
import com.druk.lmplayground.data.ChatSessionEntity
import kotlinx.coroutines.delay
import java.text.Collator

/** Folder callbacks for the chat list. The defaults do nothing (previews, screenshots). */
class ChatFolderActions(
    val onCreateFolder: (name: String, moveSessionId: String?) -> Unit = { _, _ -> },
    val onRenameFolder: (folderId: String, name: String) -> Unit = { _, _ -> },
    val onDeleteFolder: (folderId: String) -> Unit = {},
    val onMoveToFolder: (sessionId: String, folderId: String?) -> Unit = { _, _ -> },
    /** The next chat to be saved goes into this folder (its new-chat button). */
    val onNewChatInFolder: (folderId: String) -> Unit = {},
)

@Composable
fun SessionListDrawer(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onSettingsClicked: () -> Unit,
    onNewChat: () -> Unit = {},
    onSearch: suspend (String) -> Set<String> = { emptySet() },
    folders: List<ChatFolderEntity> = emptyList(),
    folderActions: ChatFolderActions = ChatFolderActions()
) {
    // Floating card: the sheet is transparent and the list sits in a rounded,
    // outlined card inset from the screen edges.
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = Color.Transparent,
        drawerTonalElevation = 0.dp,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
            shape = MenuCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = menuBorder,
        ) {
        SessionListContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            onSessionSelected = onSessionSelected,
            onDeleteSession = onDeleteSession,
            onRenameSession = onRenameSession,
            onPinSession = onPinSession,
            onNewChat = onNewChat,
            onSearch = onSearch,
            folders = folders,
            folderActions = folderActions,
            onSettingsClicked = onSettingsClicked
        )
        }
    }
}

@Composable
fun PermanentSessionList(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onSettingsClicked: () -> Unit,
    onNewChat: () -> Unit = {},
    onSearch: suspend (String) -> Set<String> = { emptySet() },
    folders: List<ChatFolderEntity> = emptyList(),
    folderActions: ChatFolderActions = ChatFolderActions(),
    width: Dp = 320.dp
) {
    // "Floating card" sidebar (Apple Maps / Liquid Glass style): the drawer
    // sheet itself is transparent so the chat surface bleeds through around
    // the card, and the actual sessions list sits inside an inset Surface
    // with rounded corners and a small tonal step. The card is padded from
    // every edge (status bar at top picked up by safeDrawing insets), so it
    // visibly floats rather than meeting any window edge.
    PermanentDrawerSheet(
        modifier = Modifier.width(width),
        drawerContainerColor = Color.Transparent,
        drawerTonalElevation = 0.dp,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                // Only inset from the left edge — the status bar above and
                // navigation bar below already provide vertical breathing
                // room, and the chat pane handles the right side itself.
                // Small 4dp top breathing room so the card doesn't kiss the
                // status bar. The inner header spacer is dropped to 0 to
                // compensate so "Conversations" / gear stay at the same
                // absolute Y as the chat title.
                .padding(start = 12.dp, top = 4.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            SessionListContent(
                sessions = sessions,
                currentSessionId = currentSessionId,
                onSessionSelected = onSessionSelected,
                onDeleteSession = onDeleteSession,
                onRenameSession = onRenameSession,
                onPinSession = onPinSession,
                onNewChat = onNewChat,
                onSearch = onSearch,
                folders = folders,
                folderActions = folderActions,
                onSettingsClicked = onSettingsClicked,
                // Tablet path runs alongside the compact (40dp) top bar, so
                // the sidebar header shrinks to match — "Conversations" lines
                // up with "Select Model".
                compact = true,
            )
        }
    }
}

/** How the chat list is ordered. Pinned chats always stay on top. */
private enum class ChatSort(val label: Int) {
    RECENT(R.string.sort_recent),
    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    NAME(R.string.sort_name);

    fun comparator(): Comparator<ChatSessionEntity> = when (this) {
        RECENT -> compareByDescending { it.updatedAt }
        NEWEST -> compareByDescending { it.createdAt }
        OLDEST -> compareBy { it.createdAt }
        NAME -> compareBy(Collator.getInstance()) { it.title }
    }

    /**
     * Folders follow the same order among themselves: by name, by when they
     * were made, or by their most recent chat ([lastActivity], falling back
     * to when the folder was made).
     */
    fun folderComparator(lastActivity: Map<String, Long>): Comparator<ChatFolderEntity> = when (this) {
        RECENT -> compareByDescending { lastActivity[it.id] ?: it.createdAt }
        NEWEST -> compareByDescending { it.createdAt }
        OLDEST -> compareBy { it.createdAt }
        NAME -> compareBy(Collator.getInstance()) { it.name }
    }
}

private const val CHAT_LIST_PREFS = "chat_list"
private const val KEY_SORT = "sort"

/** Start padding of top-level rows (chats and folders), a step in from the "Chats" header. */
private val ROW_INDENT = 16.dp

/** Start padding of chats inside an open folder: the same step in from the folder. */
private val FOLDER_CHAT_INDENT = 24.dp

/** One row of the chat list. */
private sealed interface ChatListRow {
    val key: String
}

private class FolderRow(val folder: ChatFolderEntity, val expanded: Boolean) : ChatListRow {
    override val key = "folder:" + folder.id
}

private class ChatRow(val session: ChatSessionEntity, val inFolder: Boolean) : ChatListRow {
    override val key = session.id
}

private class EmptyFolderRow(folderId: String) : ChatListRow {
    override val key = "empty:$folderId"
}

/** A "type a folder name" dialog: new folder or rename folder. */
private class FolderNameDialog(
    val title: Int,
    val initial: String,
    val confirm: Int,
    val onConfirm: (String) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListContent(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onNewChat: () -> Unit,
    onSearch: suspend (String) -> Set<String>,
    folders: List<ChatFolderEntity>,
    folderActions: ChatFolderActions,
    onSettingsClicked: () -> Unit,
    /**
     * When true, the header row matches the 40dp compact top bar height so
     * the "Conversations" title aligns horizontally with the chat title.
     * Defaults to the 56dp
     * phone layout used by the modal drawer.
     */
    compact: Boolean = false
) {
    var contextMenuSessionId by remember { mutableStateOf<String?>(null) }
    var renameDialogSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    var folderNameDialog by remember { mutableStateOf<FolderNameDialog?>(null) }
    var moveDialogSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    val listState = rememberLazyListState()

    // Search: titles and message text, run in the database. Null = no search,
    // show every chat. A short pause after typing avoids a query per key.
    var query by rememberSaveable { mutableStateOf("") }
    val matches by produceState<Set<String>?>(null, query, sessions) {
        val q = query.trim()
        val result = if (q.isEmpty()) null else {
            delay(150)
            onSearch(q)
        }
        if (result != value) {
            // New results start at the top, applied in the same frame.
            listState.requestScrollToItem(0)
            value = result
        }
    }

    // Sort order, remembered across launches.
    val prefs = LocalContext.current.getSharedPreferences(CHAT_LIST_PREFS, Context.MODE_PRIVATE)
    var sort by remember {
        mutableStateOf(ChatSort.entries.find { it.name == prefs.getString(KEY_SORT, null) } ?: ChatSort.RECENT)
    }
    var showSortMenu by remember { mutableStateOf(false) }

    // Open folders. The folder holding the current chat opens when that chat does.
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    val currentFolderId = sessions.find { it.id == currentSessionId }?.folderId
    LaunchedEffect(currentSessionId, currentFolderId) {
        if (currentFolderId != null && currentFolderId !in expanded) expanded = expanded + currentFolderId
    }
    var folderMenuId by remember { mutableStateOf<String?>(null) }

    // Rows: while searching, a flat list of matching chats; otherwise the
    // chats that aren't in a folder, then the folders in their own section
    // at the bottom (each followed by its chats when open). Both parts use
    // the chosen sort.
    val order = compareByDescending<ChatSessionEntity> { it.pinned }
        .then(sort.comparator())
        .thenByDescending { it.updatedAt }
        .thenBy { it.id }
    val searchMatches = matches
    val rows: List<ChatListRow> = if (searchMatches != null) {
        sessions.filter { it.id in searchMatches }.sortedWith(order).map { ChatRow(it, inFolder = false) }
    } else {
        val folderIds = folders.map { it.id }.toSet()
        val byFolder = sessions.filter { it.folderId in folderIds }.groupBy { it.folderId }
        val lastActivity = byFolder.mapNotNull { (id, chats) ->
            id?.let { it to chats.maxOf { chat -> chat.updatedAt } }
        }.toMap()
        buildList {
            sessions.filter { it.folderId !in folderIds }.sortedWith(order)
                .forEach { add(ChatRow(it, inFolder = false)) }
            for (folder in folders.sortedWith(sort.folderComparator(lastActivity).thenBy { it.id })) {
                val open = folder.id in expanded
                add(FolderRow(folder, open))
                if (open) {
                    val chats = byFolder[folder.id].orEmpty().sortedWith(order)
                    if (chats.isEmpty()) add(EmptyFolderRow(folder.id))
                    chats.forEach { add(ChatRow(it, inFolder = true)) }
                }
            }
        }
    }

    // A new chat (sent, or branched) is inserted at the top, but the list
    // keeps its first visible row anchored by key, so the new row lands just
    // above the viewport. Bring the current chat into view when it changes
    // or the list's head does.
    LaunchedEffect(currentSessionId, sessions.firstOrNull()?.id) {
        val index = rows.indexOfFirst { it is ChatRow && it.session.id == currentSessionId }
        if (index < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = row != null &&
            row.offset >= layout.viewportStartOffset &&
            row.offset + row.size <= layout.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(index)
    }

    Column(
        modifier = Modifier.fillMaxHeight()
    ) {
            // Header alignment with the chat top bar is now handled by the
            // sidebar Surface's 4dp top padding (see PermanentSessionList).
            // No inner spacer needed.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 40.dp else 56.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.conversations),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSettingsClicked) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // Full-width line under the "Conversations" header.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(4.dp))

            // New chat
            Surface(
                onClick = {
                    query = ""
                    onNewChat()
                },
                color = Color.Transparent,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.new_conversation),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Search chats: a plain 40dp field.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_chats),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.clear),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // "Chats" header with the new-folder and sort buttons on the right.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.chats),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        folderNameDialog = FolderNameDialog(R.string.new_folder, "", R.string.create) { name ->
                            folderActions.onCreateFolder(name, null)
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = stringResource(R.string.new_folder),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Box {
                    IconButton(onClick = { showSortMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Sort,
                            contentDescription = stringResource(R.string.sort_chats),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(
                        shape = PopupMenuShape,
                        border = menuBorder,
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        ChatSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.label)) },
                                onClick = {
                                    // Start the new order at the top in the same frame
                                    // (a keyed list would otherwise keep the old first
                                    // chat in view wherever it moved).
                                    listState.requestScrollToItem(0)
                                    sort = option
                                    prefs.edit().putString(KEY_SORT, option.name).apply()
                                    showSortMenu = false
                                },
                                trailingIcon = if (option == sort) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                                } else null
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            if (searchMatches != null && rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_matching_chats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }

            LazyColumn(
                state = listState,
                // .imePadding() so the list shrinks when the soft keyboard
                // opens — otherwise the LazyColumn extends behind the IME and
                // its bottom items are unreachable (you can't scroll because
                // it thinks everything already fits).
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
            ) {
                items(rows, key = { it.key }) { row ->
                    if (row is FolderRow) {
                        FolderListItem(
                            folder = row.folder,
                            expanded = row.expanded,
                            showMenu = folderMenuId == row.folder.id,
                            onToggle = {
                                expanded = if (row.expanded) expanded - row.folder.id else expanded + row.folder.id
                            },
                            onLongPress = { folderMenuId = row.folder.id },
                            onDismissMenu = { folderMenuId = null },
                            onNewChat = {
                                // A new chat as usual (which also closes the menu on
                                // phones), then file it in this folder once it's saved.
                                query = ""
                                onNewChat()
                                folderActions.onNewChatInFolder(row.folder.id)
                            },
                            onRename = {
                                folderMenuId = null
                                folderNameDialog = FolderNameDialog(
                                    R.string.rename_folder, row.folder.name, R.string.rename
                                ) { name -> folderActions.onRenameFolder(row.folder.id, name) }
                            },
                            onDelete = {
                                folderMenuId = null
                                folderActions.onDeleteFolder(row.folder.id)
                            },
                        )
                        return@items
                    }
                    if (row is EmptyFolderRow) {
                        Text(
                            text = stringResource(R.string.folder_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = FOLDER_CHAT_INDENT + 12.dp, top = 6.dp, bottom = 6.dp)
                        )
                        return@items
                    }
                    val chatRow = row as ChatRow
                    val session = chatRow.session
                    val isSelected = session.id == currentSessionId
                    val showMenu = contextMenuSessionId == session.id

                    Surface(
                        // Unselected rows are transparent so they inherit the
                        // sidebar's surfaceContainer tint; only the selected
                        // pill draws contrast.
                        color = if (isSelected)
                            MaterialTheme.colorScheme.secondaryContainer
                        else
                            Color.Transparent,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (chatRow.inFolder) FOLDER_CHAT_INDENT else ROW_INDENT,
                                end = 8.dp,
                                top = 1.dp,
                                bottom = 1.dp
                            )
                            // Clip BEFORE combinedClickable so the ripple /
                            // long-press indication is bounded to the pill
                            // shape — otherwise it draws on the full row
                            // rectangle and looks square against the rounded
                            // selected state.
                            .clip(RoundedCornerShape(24.dp))
                            .combinedClickable(
                                onClick = { onSessionSelected(session.id) },
                                onLongClick = { contextMenuSessionId = session.id }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.bodyMedium,
                                // Other chats are slightly grayer than the open one.
                                color = if (isSelected) Color.Unspecified
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (session.pinned) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Filled.PushPin,
                                    contentDescription = stringResource(R.string.pinned_label),
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        DropdownMenu(
                            shape = PopupMenuShape,
                            border = menuBorder,
                            expanded = showMenu,
                            onDismissRequest = { contextMenuSessionId = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (session.pinned) stringResource(R.string.unpin) else stringResource(R.string.pin)) },
                                onClick = {
                                    onPinSession(session.id, !session.pinned)
                                    contextMenuSessionId = null
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.PushPin,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    renameDialogSession = session
                                    contextMenuSessionId = null
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_to_folder)) },
                                onClick = {
                                    moveDialogSession = session
                                    contextMenuSessionId = null
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null
                                    )
                                }
                            )
                            if (session.folderId != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_from_folder)) },
                                    onClick = {
                                        folderActions.onMoveToFolder(session.id, null)
                                        contextMenuSessionId = null
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FolderOff,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    onDeleteSession(session.id)
                                    contextMenuSessionId = null
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    // Rename dialog
    renameDialogSession?.let { session ->
        var text by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            modifier = Modifier.menuOutline(),
            onDismissRequest = { renameDialogSession = null },
            title = { Text(stringResource(R.string.rename_conversation)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(session.id, text.trim())
                        renameDialogSession = null
                    },
                    enabled = text.isNotBlank()
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogSession = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    folderNameDialog?.let { dialog ->
        FolderNameInputDialog(dialog = dialog, onDismiss = { folderNameDialog = null })
    }

    moveDialogSession?.let { session ->
        AddToFolderDialog(
            session = session,
            folders = folders,
            onDismiss = { moveDialogSession = null },
            onFolderChosen = { folderId ->
                folderActions.onMoveToFolder(session.id, folderId)
                expanded = expanded + folderId
                moveDialogSession = null
            },
            onNewFolder = {
                moveDialogSession = null
                folderNameDialog = FolderNameDialog(R.string.new_folder, "", R.string.create) { name ->
                    folderActions.onCreateFolder(name, session.id)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListItem(
    folder: ChatFolderEntity,
    expanded: Boolean,
    showMenu: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onNewChat: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ROW_INDENT, end = 8.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNewChat, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = stringResource(R.string.new_conversation),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        DropdownMenu(shape = PopupMenuShape, border = menuBorder, expanded = showMenu, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename_folder)) },
                onClick = onRename,
                leadingIcon = { Icon(imageVector = Icons.Outlined.Edit, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_folder)) },
                onClick = onDelete,
                leadingIcon = { Icon(imageVector = Icons.Outlined.Delete, contentDescription = null) }
            )
        }
    }
}

@Composable
private fun FolderNameInputDialog(dialog: FolderNameDialog, onDismiss: () -> Unit) {
    var text by remember(dialog) { mutableStateOf(dialog.initial) }
    AlertDialog(
        modifier = Modifier.menuOutline(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(dialog.title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.folder_name)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    dialog.onConfirm(text.trim())
                    onDismiss()
                },
                enabled = text.isNotBlank()
            ) {
                Text(stringResource(dialog.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Pick a folder for [session], or make a new one. */
@Composable
private fun AddToFolderDialog(
    session: ChatSessionEntity,
    folders: List<ChatFolderEntity>,
    onDismiss: () -> Unit,
    onFolderChosen: (String) -> Unit,
    onNewFolder: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.menuOutline(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_folder)) },
        text = {
            // A scrolling Column, not a LazyColumn: dialogs measure their
            // content's intrinsic size, which lazy lists don't support.
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FolderChoice(
                    name = stringResource(R.string.new_folder),
                    isNew = true,
                    selected = false,
                    onClick = onNewFolder
                )
                folders.forEach { folder ->
                    FolderChoice(
                        name = folder.name,
                        isNew = false,
                        selected = folder.id == session.folderId,
                        onClick = { onFolderChosen(folder.id) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun FolderChoice(name: String, isNew: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isNew) Icons.Outlined.CreateNewFolder else Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(imageVector = Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
