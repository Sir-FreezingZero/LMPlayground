package com.druk.lmplayground.storage

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import com.druk.lmplayground.ui.SegmentedTabs
import com.druk.lmplayground.ui.TabTextAction
import com.druk.lmplayground.ui.TabTitle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

class ModelsFragment : Fragment() {

    private val viewModel: StorageViewModel by viewModels()

    /** The chat's model state, shared so this tab can load and unload models. */
    private val chat: com.druk.lmplayground.conversation.ConversationViewModel by activityViewModels()

    // Action deferred across the notification-permission prompt (a download
    // kicked off only after the user answers the permission dialog).
    private var pendingDownload: (() -> Unit)? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.requestStorageFolderChange(uri)
        }
    }

    // Import from folder: read access to the picked folder is enough.
    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.importFromFolder(it) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingDownload?.invoke()
        pendingDownload = null
    }

    /** Run [action] (a download) after ensuring the notification permission has been asked for. */
    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            action()
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not()
            && hasAskedNotificationPermission()
        ) {
            action()
        } else {
            pendingDownload = action
            setAskedNotificationPermission()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasAskedNotificationPermission(): Boolean {
        return requireContext().getSharedPreferences("download_prefs", 0)
            .getBoolean("asked_notification_permission", false)
    }

    private fun setAskedNotificationPermission() {
        requireContext().getSharedPreferences("download_prefs", 0)
            .edit().putBoolean("asked_notification_permission", true).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadStorageInfo()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            val storageInfo by viewModel.storageInfo.observeAsState()
            val allModels by viewModel.allModels.observeAsState(emptyList())
            val embeddingModel by viewModel.embeddingModel.observeAsState()
            val dictationModel by viewModel.dictationModel.observeAsState()
            val downloadingProgress by viewModel.downloadingModels.observeAsState(emptyMap())
            val snackbarMessage by viewModel.snackbarMessage.observeAsState()
            val pendingMigration by viewModel.pendingMigration.observeAsState()
            val migrationProgress by viewModel.migrationProgress.observeAsState()

            val prevDownloadCount = remember { androidx.compose.runtime.mutableIntStateOf(downloadingProgress.size) }
            LaunchedEffect(downloadingProgress.size) {
                if (downloadingProgress.size < prevDownloadCount.intValue) {
                    viewModel.loadStorageInfo()
                }
                prevDownloadCount.intValue = downloadingProgress.size
            }

            val loadedModel by chat.loadedModel.observeAsState()
            val modelReady by chat.isModelReady.observeAsState(false)
            val onGpu by chat.loadedOnGpu.observeAsState(false)
            val generating by chat.isGenerating.observeAsState(false)
            val files by viewModel.downloadedModels.observeAsState(emptyList())
            var segment by rememberSaveable { mutableIntStateOf(0) }
            val snackbarHostState = remember { SnackbarHostState() }
            // Browse shows these itself; the other segments show them here.
            LaunchedEffect(snackbarMessage, segment) {
                val message = snackbarMessage
                if (segment != 1 && message != null) {
                    snackbarHostState.showSnackbar(message)
                    viewModel.clearSnackbar()
                }
            }

            val importFolder = {
                try {
                    importFolderLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    viewModel.showSnackbar(getString(R.string.import_folder_no_access))
                }
            }
            val changeFolder = {
                try {
                    folderPickerLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    // No DocumentsUI on this device — offer the
                    // app-private fallback folder instead.
                    val fallbackUri = StorageDocuments.appStorageFallbackUri(requireContext())
                    if (fallbackUri != null) {
                        Toast.makeText(requireContext(), R.string.storage_fallback_used, Toast.LENGTH_LONG).show()
                        viewModel.requestStorageFolderChange(fallbackUri)
                    } else {
                        Toast.makeText(requireContext(), R.string.folder_picker_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            }

            PlaygroundTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    TabTitle(stringResource(R.string.models)) {
                        TabTextAction(stringResource(R.string.models_tab_import), ImportIcon, onClick = importFolder)
                    }
                    SegmentedTabs(
                        listOf(
                            stringResource(R.string.models_tab_mine),
                            stringResource(R.string.models_tab_browse),
                            stringResource(R.string.models_tab_cloud),
                        ),
                        selected = segment,
                        onSelect = { segment = it },
                    )
                    when (segment) {
                        0 -> MyModelsSection(
                            models = allModels,
                            fileSizes = files.associate { it.name to it.sizeBytes },
                            loaded = loadedModel?.let { LoadedModelState(it, modelReady, onGpu) },
                            busy = generating || (loadedModel != null && !modelReady),
                            onLoad = { chat.loadModel(it) },
                            onUnload = { chat.unloadModel() },
                            onDelete = { viewModel.deleteModel(it) },
                            onBrowse = { segment = 1 },
                        )
                        1 -> Box {
                            ModelsContent(
                                storageInfo = storageInfo,
                                allModels = allModels,
                                downloadingModels = downloadingProgress,
                                snackbarMessage = snackbarMessage,
                                pendingMigration = pendingMigration,
                                migrationProgress = migrationProgress,
                                deviceLanguage = viewModel.deviceLanguage,
                                snackbarHostState = snackbarHostState,
                                onChangeFolderClick = changeFolder,
                                onDeleteModel = { model -> viewModel.deleteModel(model) },
                                onDownloadModel = { model, includeMmproj ->
                                    withNotificationPermission { viewModel.downloadModel(model, includeMmproj) }
                                },
                                onDownloadMmproj = { model ->
                                    withNotificationPermission { viewModel.downloadMmprojOnly(model) }
                                },
                                onCancelDownload = { model -> viewModel.cancelDownload(model) },
                                onSnackbarDismiss = { viewModel.clearSnackbar() },
                                onConfirmMigration = { viewModel.confirmMigration() },
                                onSkipMigration = { viewModel.skipMigration() },
                                onCancelMigration = { viewModel.cancelMigration() },
                                embeddingModel = embeddingModel,
                                dictationModel = dictationModel,
                                onImportFolderClick = importFolder,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        else -> CloudModelsPlaceholder()
                    }
                }
                SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
                val progress = migrationProgress
                if (segment != 1 && progress != null) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(if (progress.isImport) R.string.importing_models else R.string.migrating_models)) },
                        text = {
                            Text(
                                stringResource(R.string.migration_progress, progress.currentIndex, progress.totalCount) +
                                    "\n" + progress.currentModel
                            )
                        },
                        confirmButton = {},
                    )
                }
                }
                }
            }
        }
    }
}
