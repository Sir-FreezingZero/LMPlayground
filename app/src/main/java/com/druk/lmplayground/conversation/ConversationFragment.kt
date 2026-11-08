package com.druk.lmplayground.conversation

import com.druk.lmplayground.theme.menuOutline
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.settings.SettingsFragment
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import com.druk.lmplayground.models.ModelInfoProvider
import com.druk.lmplayground.models.SelectModelDialog
import com.druk.lmplayground.storage.StorageViewModel
import com.druk.lmplayground.theme.PlaygroundTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.druk.lmplayground.dictation.DictationState
import kotlinx.coroutines.launch

class ConversationFragment : Fragment() {

    private val viewModel: ConversationViewModel by viewModels()
    private val storageViewModel: StorageViewModel by viewModels()

    private var attachedImageUri = mutableStateOf<Uri?>(null)

    // Destination of an in-flight camera capture. Kept as both a Uri (handed to
    // the camera app / set as the attachment) and a File (so we can check
    // length() / delete() the temp without re-parsing the FileProvider Uri).
    private var pendingCaptureUri: Uri? = null
    private var pendingCaptureFile: File? = null

    private val cameraAvailable by lazy {
        requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // The tap that asked is still "on" — no finger has to be held
            // through the system dialog — so start the recording it wanted.
            viewModel.dictation.startListening()
        } else {
            viewModel.dictation.onMicReleased()
            Toast.makeText(
                requireContext(),
                R.string.dictation_permission_denied,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // Picking a library image supersedes any unsent camera capture — drop its
        // temp so the cache never holds more than the current attachment's file.
        if (uri != null) clearPendingCapture()
        attachedImageUri.value = uri
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCaptureFile
        // Gate on a non-empty file: some OEM camera apps report success but write
        // nothing, and a 0-byte JPEG would silently degrade the turn to text-only.
        if (success && file != null && file.length() > 0L) {
            // Keep pendingCaptureFile referenced so it can be pruned after the
            // turn is sent / cleared / superseded (see clearPendingCapture).
            attachedImageUri.value = pendingCaptureUri
        } else {
            clearPendingCapture()
        }
    }

    /** Delete the in-flight/last camera temp (if any) and forget it. */
    private fun clearPendingCapture() {
        runCatching { pendingCaptureFile?.delete() }
        pendingCaptureFile = null
        pendingCaptureUri = null
    }

    // Document (RAG) attachment: OpenDocument (not GetContent) so the read
    // grant can be persisted — indexing may start after a model download,
    // and the chip reopens the original across app restarts.
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.attachDocument(uri)
    }

    /**
     * Reopen an attached document's original file in the user's viewer.
     * The stored grant is persistable but the file may be gone (deleted,
     * provider removed) — every failure funnels into one toast.
     */
    private fun openAttachedDocument(doc: com.druk.lmplayground.data.RagDocumentEntity) {
        val uri = doc.sourceUri?.let(Uri::parse)
        if (uri == null) {
            Toast.makeText(requireContext(), R.string.document_source_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, doc.mimeType ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // ActivityNotFound (no viewer) or SecurityException (grant/file
            // revoked) — same user-facing outcome.
            Toast.makeText(requireContext(), R.string.document_source_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Capture a photo via the system camera app into a temp file under
     * cacheDir/chat_images, then route the result through the same attachment
     * path as the photo picker. No CAMERA permission is needed — the camera app
     * does the capture and we only receive the written image.
     */
    override fun onPause() {
        super.onPause()
        // Android mutes the microphone for a background app, so end dictation
        // rather than leave it recording silence until the duration cap.
        viewModel.dictation.onMicReleased()
    }

    /**
     * Begin dictation, asking for the microphone first if we don't have it.
     * The permission callback starts the recording on grant.
     */
    private fun startDictation() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.dictation.startListening()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Write the open chat to a Markdown file and hand it to the share sheet,
     * where it can be saved to Files or Drive or sent anywhere.
     */
    private fun exportChat() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            try {
                shareMarkdown(context, "chat", viewModel.exportChat(), getString(R.string.export_chat))
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: e.javaClass.simpleName, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCamera() {
        // Drop any temp from a prior capture that was never sent.
        clearPendingCapture()
        val uri = try {
            val dir = File(requireContext().cacheDir, "chat_images").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.fileprovider", file
            )
            pendingCaptureFile = file
            pendingCaptureUri = uri
            uri
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            takePictureLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            clearPendingCapture()
            Toast.makeText(requireContext(), R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The camera app is memory-heavy and frequently triggers our process
        // death while it is foregrounded. Persist the pending capture destination
        // so the result callback can still map back to the temp file on return.
        pendingCaptureFile?.let { outState.putString(KEY_PENDING_CAPTURE_FILE, it.absolutePath) }
    }

    override fun onResume() {
        super.onResume()
        // A default prompt changed in Settings → System prompts applies now.
        viewModel.syncSystemPrompt()
        // So do model settings changed in Settings → Model.
        viewModel.syncModelParams()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)

        // Recover a capture that was in flight when the process was killed.
        savedInstanceState?.getString(KEY_PENDING_CAPTURE_FILE)?.let { path ->
            val file = File(path)
            pendingCaptureFile = file
            pendingCaptureUri = runCatching {
                FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
            }.getOrNull()
        }

        setContent {

            val messages = viewModel.uiState.messages
            val isGenerating by viewModel.isGenerating.observeAsState()
            val isRunningTools by viewModel.isRunningTools.observeAsState(false)
            val progress by viewModel.modelLoadingProgress.observeAsState(0f)
            val modelInfo by viewModel.loadedModel.observeAsState()
            val modelStatus by viewModel.loadedModelStatus.observeAsState()
            val supportsThinking by viewModel.supportsThinking.observeAsState(false)
            // The chat switch follows measured behaviour: hidden for models that
            // always reason, since "off" would be ignored. The params sheet still
            // uses supportsThinking, because the thinking budget applies to them.
            val thinkingToggleable by viewModel.thinkingToggleable.observeAsState(false)
            val thinkingEnabled by viewModel.thinkingEnabled.observeAsState(false)
            val supportsVision by viewModel.supportsVision.observeAsState(false)
            val currentImageUri by attachedImageUri
            val isModelReady by viewModel.isModelReady.observeAsState(false)
            val models by viewModel.models.observeAsState(emptyList())
            val sessions by viewModel.sessions.observeAsState(emptyList())
            val folders by viewModel.folders.observeAsState(emptyList())
            val folderActions = remember {
                ChatFolderActions(
                    onCreateFolder = { name, moveSessionId -> viewModel.createFolder(name, moveSessionId) },
                    onRenameFolder = { folderId, name -> viewModel.renameFolder(folderId, name) },
                    onDeleteFolder = { folderId -> viewModel.deleteFolder(folderId) },
                    onMoveToFolder = { sessionId, folderId -> viewModel.moveToFolder(sessionId, folderId) },
                    onNewChatInFolder = { folderId -> viewModel.newChatInFolder(folderId) },
                )
            }
            val currentSessionId by viewModel.currentSessionId.observeAsState()
            val generationParams by viewModel.generationParams.observeAsState(GenerationParams())
            val maxContextSize by viewModel.maxContextSize.observeAsState(4096)
            val sessionModelHint by viewModel.sessionModelHint.observeAsState()
            val visionModuleHint by viewModel.visionModuleHint.observeAsState()
            val contextUsage by viewModel.contextUsage.observeAsState()
            val userError by viewModel.userError.observeAsState()
            val notice by viewModel.notice.observeAsState()
            val pendingRamWarning by viewModel.pendingRamWarning.observeAsState()
            val modelLoadError by viewModel.modelLoadError.observeAsState()
            val sessionDocuments by viewModel.sessionDocuments.observeAsState(emptyList())
            val embeddingModelPrompt by viewModel.embeddingModelPrompt.observeAsState()
            var documentPendingRemoval by remember {
                mutableStateOf<com.druk.lmplayground.data.RagDocumentEntity?>(null)
            }
            var showParamsSheet by remember { mutableStateOf(false) }
            var paramsSheetTab by remember { mutableIntStateOf(0) }
            val systemPrompt by viewModel.systemPrompt.observeAsState("")
            val chatTools by viewModel.chatTools.observeAsState(emptyMap())
            val supportsToolCalling by viewModel.supportsToolCalling.observeAsState(false)

            val dictationState by viewModel.dictation.state.observeAsState(DictationState.Idle)
            val micOn by viewModel.dictation.micOn.observeAsState(false)
            val dictationTranscript by viewModel.dictation.transcript.observeAsState()
            val dictationError by viewModel.dictation.error.observeAsState()
            val dictationModelReady by viewModel.dictation.isModelReady.observeAsState()
            val dictationDownload by viewModel.dictation.downloadProgress.observeAsState()
            var showDictationDownloadPrompt by remember { mutableStateOf(false) }

            // Surface transient ViewModel errors (e.g. message-too-large)
            // as Toasts. The ViewModel can't show UI directly, so we
            // observe a one-shot LiveData and clear it after consumption.
            val toastContext = LocalContext.current
            // Each error also turns the status line red for a few seconds.
            var errorCount by remember { mutableIntStateOf(0) }
            var showErrorLine by remember { mutableStateOf(false) }
            LaunchedEffect(userError) {
                val msg = userError ?: return@LaunchedEffect
                Toast.makeText(toastContext, msg, Toast.LENGTH_LONG).show()
                viewModel.consumeUserError()
                errorCount++
            }
            LaunchedEffect(errorCount) {
                if (errorCount == 0) return@LaunchedEffect
                showErrorLine = true
                kotlinx.coroutines.delay(4000)
                showErrorLine = false
            }

            LaunchedEffect(notice) {
                val msg = notice ?: return@LaunchedEffect
                Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()
                viewModel.consumeNotice()
            }

            LaunchedEffect(dictationError) {
                val msg = dictationError ?: return@LaunchedEffect
                Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()
                viewModel.dictation.consumeError()
            }

            // The model may have been downloaded (or deleted) since last time.
            LaunchedEffect(Unit) { viewModel.dictation.refreshModelAvailability() }

            // Storage configuration state
            val isStorageConfigured by storageViewModel.isStorageConfigured.observeAsState(true)
            var showStorageSetupDialog by remember { mutableStateOf(false) }

            // Migration state
            val pendingMigration by storageViewModel.pendingMigration.observeAsState()
            val migrationProgress by storageViewModel.migrationProgress.observeAsState()

            PlaygroundTheme {

                val scrollState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val drawerState = rememberDrawerState(DrawerValue.Closed)

                // "Follow the latest" flag for the message list. Hoisted here so
                // the jump-to-bottom button (rendered at the root, above all
                // chrome) can re-arm it. New messages re-enable following.
                var piningBottom by remember { mutableStateOf(false) }
                LaunchedEffect(messages.size) { piningBottom = true }
                val jumpEnabled by remember {
                    derivedStateOf { scrollState.canScrollForward }
                }

                val colorScheme = MaterialTheme.colorScheme

                // Chrome: the input dock and floating buttons share one solid
                // "raised surface" color over the full-bleed chat. The top bar
                // uses the chat's own background, so it stays black.
                // (A blur used to sit here; a solid fill saves the GPU work.)
                val chromeColor = colorScheme.surfaceContainerHigh

                // Only show the permanent sidebar when there's enough actual
                // window width to comfortably fit master (320dp) + detail.
                // At Medium (600-839dp), e.g. 7" tablet portrait or split-screen,
                // the chat pane would be squeezed to ~280dp — worse than just
                // hiding the sidebar behind a hamburger.
                val widthSize = calculateWindowSizeClass(requireActivity()).widthSizeClass
                val showPermanent = widthSize == WindowWidthSizeClass.Expanded

                // Foldable book posture: if a vertical hinge is in the window,
                // align the sidebar boundary with it so the seam between
                // master/detail lands on the crease instead of arbitrarily
                // bisecting one display. Fall back to 320dp on flat devices.
                val hingeWidth = com.druk.lmplayground.util.rememberHingeWidthDp(requireActivity())
                val sidebarWidth = if (hingeWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                    // Clamp the lower bound so the master pane doesn't shrink
                    // below the comfortable 320dp the rest of the UI expects.
                    maxOf(320.dp, hingeWidth)
                } else {
                    320.dp
                }

                // The bar paints no container of its own — the chrome color
                // drawn behind it provides the separation, so both the resting
                // and scrolled container colors are transparent.
                val topBarColors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
                val inputFocusRequester = remember { FocusRequester() }

                // Measured heights of the floating top bar and bottom dock,
                // fed back into the list as content padding (so the first/last
                // messages clear the chrome) and as the float offset for the
                // jump-to-bottom / model-hint buttons. The bottom value tracks
                // the dock as it grows with multi-line text and the keyboard.
                val density = LocalDensity.current
                var topBarHeightPx by remember { mutableIntStateOf(0) }
                var bottomBarHeightPx by remember { mutableIntStateOf(0) }
                val topBarHeight = with(density) { topBarHeightPx.toDp() }
                val bottomBarHeight = with(density) { bottomBarHeightPx.toDp() }
                var modelReport by remember { mutableStateOf<String?>(null) }

                // When model finishes loading, jump to bottom and open keyboard
                LaunchedEffect(isModelReady) {
                    if (isModelReady) {
                        val lastIndex = scrollState.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) {
                            scrollState.animateScrollToItem(lastIndex)
                        }
                        inputFocusRequester.requestFocus()
                    }
                }

                // Check if storage is configured on first launch
                LaunchedEffect(Unit) {
                    storageViewModel.checkStorageConfigured()
                }

                // Show setup dialog if storage not configured
                LaunchedEffect(isStorageConfigured) {
                    if (!isStorageConfigured) {
                        showStorageSetupDialog = true
                    }
                }

                // Storage Setup Dialog
                if (showStorageSetupDialog && !isStorageConfigured && pendingMigration == null) {
                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { /* Cannot dismiss - must choose folder */ },
                        title = { Text(stringResource(R.string.choose_storage_folder)) },
                        text = {
                            Text(stringResource(R.string.choose_storage_folder_message))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    (activity as? MainActivity)?.launchFolderPicker { uri ->
                                        if (uri != null) {
                                            storageViewModel.requestStorageFolderChange(uri)
                                            showStorageSetupDialog = false
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.choose_folder))
                            }
                        }
                    )
                }

                // Migration confirmation dialog
                pendingMigration?.let { migration ->
                    val totalSize = migration.modelsToMigrate.sumOf { it.sizeBytes }
                    val sizeFormatted = android.text.format.Formatter.formatFileSize(context, totalSize)

                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { storageViewModel.cancelMigration() },
                        title = { Text(stringResource(R.string.migrate_models_title)) },
                        text = {
                            Column {
                                Text(
                                    if (migration.isFromDownloads) {
                                        stringResource(R.string.migrate_models_from_downloads, migration.modelsToMigrate.size, sizeFormatted)
                                    } else {
                                        stringResource(R.string.migrate_models_message, migration.modelsToMigrate.size, sizeFormatted)
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { storageViewModel.confirmMigration() }) {
                                Text(stringResource(R.string.migrate))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { storageViewModel.skipMigration() }) {
                                Text(stringResource(R.string.skip))
                            }
                        }
                    )
                }

                // Migration progress dialog
                migrationProgress?.let { progress ->
                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { /* Cannot dismiss while migrating */ },
                        title = { Text(stringResource(R.string.migrating_models)) },
                        text = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(stringResource(R.string.migration_progress, progress.currentIndex, progress.totalCount))
                                Text(
                                    text = progress.currentModel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = { }
                    )
                }

                // Document Q&A needs the embedding model on disk — offer the
                // one-time download; the picked document stays pending and
                // indexes automatically once the download lands.
                embeddingModelPrompt?.let {
                    val embeddingModel = ModelInfoProvider.embeddingModel
                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { viewModel.dismissEmbeddingModelPrompt() },
                        title = { Text(stringResource(R.string.embedding_model_dialog_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.embedding_model_dialog_message,
                                    embeddingModel.name,
                                    embeddingModel.description.substringAfterLast("· "),
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmEmbeddingModelDownload() }) {
                                Text(stringResource(R.string.download_model))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissEmbeddingModelPrompt() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                // Dictation needs its speech model on disk — offer the one-time
                // download the first time the microphone is tapped.
                if (showDictationDownloadPrompt) {
                    val dictationModel = viewModel.dictation.modelInfo
                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { showDictationDownloadPrompt = false },
                        title = { Text(stringResource(R.string.dictation_model_dialog_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.dictation_model_dialog_message,
                                    dictationModel.name,
                                    dictationModel.description.substringAfterLast("· "),
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDictationDownloadPrompt = false
                                viewModel.dictation.downloadModel()
                                Toast.makeText(
                                    requireContext(),
                                    R.string.dictation_model_downloading,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }) {
                                Text(stringResource(R.string.download_model))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDictationDownloadPrompt = false }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                documentPendingRemoval?.let { doc ->
                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { documentPendingRemoval = null },
                        title = { Text(stringResource(R.string.remove_document_title)) },
                        text = {
                            Text(stringResource(R.string.remove_document_message, doc.displayName))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.removeDocument(doc.id)
                                    documentPendingRemoval = null
                                }
                            ) {
                                Text(stringResource(R.string.remove))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { documentPendingRemoval = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                pendingRamWarning?.let { warning ->
                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { viewModel.dismissRamWarning() },
                        title = { Text(stringResource(R.string.low_ram_warning_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.low_ram_warning_message,
                                    warning.neededRam,
                                    warning.totalRam,
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.confirmLoadDespiteRamWarning() }) {
                                Text(stringResource(R.string.load_anyway))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissRamWarning() }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    )
                }

                modelLoadError?.let { message ->
                    AlertDialog(
                        modifier = Modifier.menuOutline(),
                        onDismissRequest = { viewModel.consumeModelLoadError() },
                        title = { Text(stringResource(R.string.model_load_failed_title)) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = { viewModel.consumeModelLoadError() }) {
                                Text(stringResource(R.string.model_load_failed_dismiss))
                            }
                        }
                    )
                }

                if (showParamsSheet) {
                    GenerationParamsSheet(
                        params = generationParams,
                        maxContextSize = maxContextSize,
                        supportsThinking = supportsThinking,
                        onParamsChanged = { viewModel.updateGenerationParams(it) },
                        systemPrompt = systemPrompt,
                        onSystemPromptChanged = { viewModel.applySystemPrompt(it) },
                        renderPromptStart = viewModel::renderPromptStart,
                        tools = viewModel.toolRegistry.getAllTools(),
                        toolStates = viewModel.toolStates(chatTools),
                        onToolChanged = { name, on -> viewModel.setChatTool(name, on) },
                        initialTab = paramsSheetTab,
                        onDismiss = { showParamsSheet = false }
                    )
                }

                val mainContent: @Composable () -> Unit = {
                    // Surface restores what the old Scaffold provided: it paints
                    // the chat background AND propagates the correct content
                    // color (onBackground) to descendants. A bare Box leaves text
                    // at the default LocalContentColor (black). It's also the base
                    // the chrome sits over.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = colorScheme.background
                    ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // ---- Background: full-bleed content under the chrome.
                        // Content padding keeps the first and last messages
                        // clear of the floating chrome.
                        if (modelInfo == null && messages.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = topBarHeight, bottom = bottomBarHeight),
                                contentAlignment = Alignment.Center
                            ) {
                                WhatsNewText()
                            }
                        } else {
                            Messages(
                                messages = messages,
                                modifier = Modifier.fillMaxSize(),
                                scrollState = scrollState,
                                contentPadding = PaddingValues(
                                    top = topBarHeight,
                                    bottom = bottomBarHeight
                                ),
                                bottomInset = bottomBarHeight,
                                chromeColor = chromeColor,
                                isGenerating = isGenerating == true,
                                piningBottom = piningBottom,
                                onPiningBottomChange = { piningBottom = it },
                                sessionModelHint = sessionModelHint,
                                onSessionModelHintClick = { filename ->
                                    viewModel.loadModelByFilename(filename)
                                },
                                onSessionModelHintDismiss = {
                                    viewModel.dismissSessionModelHint()
                                },
                                visionModuleHint = visionModuleHint?.name,
                                onVisionModuleHintClick = {
                                    viewModel.downloadVisionModule()
                                    Toast.makeText(
                                        requireContext(),
                                        R.string.vision_module_downloading,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                                onTokenCountClicked = {
                                    modelReport = viewModel.getReport()
                                },
                                // Edit / regenerate run the model; branching
                                // only needs a saved chat to copy.
                                onEditMessage = if (isModelReady == true) {
                                    { msg, text -> viewModel.editMessage(msg.id, text) }
                                } else null,
                                onRegenerate = if (isModelReady == true) {
                                    { msg -> viewModel.regenerate(msg.id) }
                                } else null,
                                onBranch = if (currentSessionId != null) {
                                    { msg -> viewModel.branchFrom(msg.id) }
                                } else null,
                                onImageClick = { uri ->
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "image/*")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        startActivity(intent)
                                    } catch (_: Exception) { }
                                }
                            )
                        }

                        // What the streaming reply is doing: thinking (inside an
                        // open <think>), writing visible text, or neither yet
                        // (reading). Derived, so this screen only recomposes when
                        // it changes, not on every update.
                        val replyPhase by remember {
                            derivedStateOf {
                                val text = messages.lastOrNull()
                                    ?.takeIf { it.author == "Assistant" }?.content.orEmpty()
                                when {
                                    text.lastIndexOf("<think>") > text.lastIndexOf("</think>") ->
                                        ChatStatus.THINKING
                                    splitThinking(text).responseContent.isNotBlank() ->
                                        ChatStatus.GENERATING
                                    else -> ChatStatus.PROCESSING
                                }
                            }
                        }
                        // Glowing status line under the top bar (see StatusLine.kt).
                        val drawStatusLine = rememberStatusLine(
                            when {
                                // Red while a load error is shown, and briefly after others.
                                modelLoadError != null || showErrorLine -> ChatStatus.ERROR
                                !isModelReady -> ChatStatus.NO_MODEL
                                isRunningTools && isGenerating == true -> ChatStatus.RUNNING_TOOLS
                                isGenerating == true -> replyPhase
                                else -> ChatStatus.READY
                            }
                        )

                        // ---- Top bar, floating over the content ----
                        ConversationBar(
                            modelInfo = modelInfo,
                            modelStatus = modelStatus,
                            showNavIcon = !showPermanent,
                            compact = showPermanent,
                            onNavIconPressed = {
                                scope.launch { drawerState.open() }
                            },
                            colors = topBarColors,
                            onModelNamePressed = {
                                viewModel.loadModelList()
                            },
                            onGenerationParamsPressed = if (modelInfo != null) {
                                { paramsSheetTab = 0; showParamsSheet = true }
                            } else null,
                            onSystemPromptPressed = { paramsSheetTab = 1; showParamsSheet = true },
                            onToolsPressed = { paramsSheetTab = 2; showParamsSheet = true },
                            onExportPressed = ::exportChat,
                            contextUsage = contextUsage,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .onSizeChanged { topBarHeightPx = it.height }
                                // Same color as the chat, so it stays black
                                // (white in light mode) when scrolled.
                                .background(colorScheme.background)
                                .drawWithContent {
                                    drawContent()
                                    // Status line under the bar.
                                    drawStatusLine()
                                    // Model-loading progress hairline along the
                                    // bar's bottom edge.
                                    val strokeWidth = 2.dp.toPx()
                                    val y = size.height - strokeWidth / 2f
                                    drawLine(
                                        colorScheme.primary,
                                        start = Offset(0f, y),
                                        end = Offset(size.width * progress, y),
                                        strokeWidth = strokeWidth
                                    )
                                }
                        )

                        // ---- Bottom dock (document chips + input) ----
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .onSizeChanged { bottomBarHeightPx = it.height }
                        ) {
                            if (isModelReady && supportsToolCalling) {
                                QuickTools(
                                    tools = viewModel.toolRegistry.getAllTools(),
                                    states = viewModel.toolStates(chatTools),
                                    onToggle = { name, on -> viewModel.setChatTool(name, on) },
                                )
                            }
                            if (sessionDocuments.isNotEmpty()) {
                                DocumentChipsRow(
                                    documents = sessionDocuments,
                                    onRemove = { documentPendingRemoval = it },
                                    onOpen = { doc -> openAttachedDocument(doc) },
                                    modifier = Modifier.padding(bottom = 4.dp),
                                    chromeColor = chromeColor
                                )
                            }
                            UserInput(
                                integrateWithSurface = showPermanent,
                                // The full-width line drawn below replaces the
                                // tablet's inset divider.
                                showTopDivider = false,
                                chromeColor = chromeColor,
                                modifier = Modifier
                                    // Full-width line above the input, like the
                                    // one under the top bar.
                                    .drawWithContent {
                                        drawContent()
                                        val lineWidth = 1.dp.toPx()
                                        drawLine(
                                            colorScheme.outlineVariant,
                                            start = Offset(0f, lineWidth / 2f),
                                            end = Offset(size.width, lineWidth / 2f),
                                            strokeWidth = lineWidth
                                        )
                                    }
                                    .navigationBarsPadding()
                                    .imePadding(),
                                focusRequester = inputFocusRequester,
                                status = if (modelInfo == null || !isModelReady)
                                    UserInputStatus.NOT_LOADED
                                else if (isGenerating == true)
                                    UserInputStatus.GENERATING
                                else
                                    UserInputStatus.IDLE,
                                supportsThinking = thinkingToggleable,
                                thinkingEnabled = thinkingEnabled,
                                onThinkingToggle = { viewModel.toggleThinking() },
                                supportsVision = supportsVision,
                                cameraAvailable = cameraAvailable,
                                attachedImageUri = currentImageUri,
                                onAttachImage = {
                                    pickMediaLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onTakePhoto = { launchCamera() },
                                onAttachDocument = {
                                    try {
                                        openDocumentLauncher.launch(
                                            com.druk.lmplayground.rag.DocumentTextExtractors.OPENABLE_MIME_TYPES
                                        )
                                    } catch (e: ActivityNotFoundException) {
                                        Toast.makeText(
                                            requireContext(),
                                            R.string.document_open_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onClearImage = {
                                    attachedImageUri.value = null
                                    clearPendingCapture()
                                },
                                onSwipeUp = {
                                    if (isModelReady) { paramsSheetTab = 0; showParamsSheet = true }
                                },
                                dictationState = dictationState,
                                dictationDownloadProgress = dictationDownload,
                                micOn = micOn,
                                micRecording = viewModel.dictation.isRecording,
                                micLevel = viewModel.dictation.level,
                                onMicClick = {
                                    if (viewModel.dictation.isMicOn) {
                                        viewModel.dictation.onMicReleased()
                                        return@UserInput
                                    }
                                    // Already fetching: the ring around the mic
                                    // is the answer, not the dialog again.
                                    if (dictationDownload != null) return@UserInput
                                    viewModel.dictation.onMicPressed()
                                    // Check on every tap rather than trusting
                                    // the cached flag: the model may have
                                    // finished downloading (or been deleted)
                                    // since the screen was composed.
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        if (viewModel.dictation.isModelAvailable()) {
                                            startDictation()
                                        } else {
                                            viewModel.dictation.onMicReleased()
                                            showDictationDownloadPrompt = true
                                        }
                                    }
                                },
                                onDictationAbandoned = { viewModel.dictation.abandon() },
                                pendingTranscript = dictationTranscript,
                                onTranscriptConsumed = { viewModel.dictation.consumeTranscript() },
                                onMessageSent = { content ->
                                    val imageUri = attachedImageUri.value
                                    attachedImageUri.value = null
                                    viewModel.addMessage(
                                        Message("User", content),
                                        imageUri = imageUri
                                    )
                                    // addMessage has already copied the capture into
                                    // filesDir (synchronous persist); drop the cache temp.
                                    clearPendingCapture()
                                },
                                onCancelClicked = {
                                    viewModel.cancelGeneration()
                                },
                                // let this element handle the padding so that the
                                // fill extends behind the navigation bar
                                resetScroll = {
                                    scope.launch {
                                        val lastIndex = scrollState.layoutInfo.totalItemsCount - 1
                                        if (lastIndex >= 0) {
                                            scrollState.animateScrollToItem(lastIndex)
                                        }
                                    }
                                }
                            )
                        }

                        // Jump-to-bottom: rendered last so it sits above all the
                        // chrome with both a chrome-colored fill and a real
                        // elevation shadow. Hidden while the model hint is up.
                        JumpToBottom(
                            enabled = jumpEnabled &&
                                sessionModelHint == null &&
                                (modelInfo != null || messages.isNotEmpty()),
                            onClicked = {
                                scope.launch {
                                    val last = scrollState.layoutInfo.totalItemsCount - 1
                                    if (last >= 0) scrollState.animateScrollToItem(last)
                                }
                                piningBottom = true
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = JUMP_TO_BOTTOM_MARGIN, bottom = bottomBarHeight),
                            chromeColor = chromeColor
                        )

                        // Model picker — kept inside the chat Box (and drawn
                        // last) as an overlay over the chat pane, so no
                        // chatPaneStartOffset is needed.
                        if (models.isNotEmpty() && models.any { it.isDownloaded }) {
                            SelectModelDialog(
                                models = models,
                                loadedModelFilename = modelInfo?.filename,
                                chromeColor = chromeColor,
                                onLoadModel = { model ->
                                    viewModel.loadModel(model)
                                },
                                onUnloadModel = {
                                    viewModel.unloadModel()
                                },
                                onBrowseModels = {
                                    // Guard against NavController throwing IllegalArgumentException
                                    // when the user double-taps or another navigation moved us
                                    // off nav_home before this callback fired.
                                    val nav = findNavController()
                                    if (nav.currentDestination?.id == R.id.nav_home) {
                                        nav.navigate(R.id.action_home_to_models)
                                    }
                                },
                                onDismissRequest = {
                                    viewModel.resetModelList()
                                }
                            )
                        }
                    }
                    }

                    // Models exist but none are downloaded → jump straight to the
                    // Models screen. (When some are downloaded, the picker
                    // overlay inside the chat Box above handles it.) The
                    // session-info dialog stays a plain platform dialog.
                    if (models.isNotEmpty()) {
                        if (models.none { it.isDownloaded }) {
                            // No downloaded models - go directly to Models screen
                            LaunchedEffect(Unit) {
                                viewModel.resetModelList()
                                if (findNavController().currentDestination?.id == R.id.nav_home) {
                                    findNavController().navigate(R.id.action_home_to_models)
                                }
                            }
                        }
                    } else if (modelReport != null) {
                        SessionInfoDialog(
                            report = modelReport!!,
                            onDismiss = { modelReport = null }
                        )
                    }
                }

                if (showPermanent) {
                    PermanentNavigationDrawer(
                        drawerContent = {
                            PermanentSessionList(
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                width = sidebarWidth,
                                onSessionSelected = { sessionId ->
                                    viewModel.loadSession(sessionId)
                                },
                                onDeleteSession = { sessionId ->
                                    viewModel.deleteSession(sessionId)
                                },
                                onRenameSession = { sessionId, newTitle ->
                                    viewModel.renameSession(sessionId, newTitle)
                                },
                                onPinSession = { sessionId, pinned ->
                                    viewModel.pinSession(sessionId, pinned)
                                },
                                onNewChat = { viewModel.newConversation() },
                                onSearch = { query -> viewModel.searchSessions(query) },
                                folders = folders,
                                folderActions = folderActions,
                                onSettingsClicked = {
                                    if (findNavController().currentDestination?.id == R.id.nav_home) {
                                        findNavController().navigate(R.id.action_home_to_settings)
                                    }
                                }
                            )
                        }
                    ) { mainContent() }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            SessionListDrawer(
                                sessions = sessions,
                                currentSessionId = currentSessionId,
                                onSessionSelected = { sessionId ->
                                    viewModel.loadSession(sessionId)
                                    scope.launch { drawerState.close() }
                                },
                                onDeleteSession = { sessionId ->
                                    viewModel.deleteSession(sessionId)
                                },
                                onRenameSession = { sessionId, newTitle ->
                                    viewModel.renameSession(sessionId, newTitle)
                                },
                                onPinSession = { sessionId, pinned ->
                                    viewModel.pinSession(sessionId, pinned)
                                },
                                onNewChat = {
                                    viewModel.newConversation()
                                    scope.launch { drawerState.close() }
                                },
                                onSearch = { query -> viewModel.searchSessions(query) },
                                folders = folders,
                                folderActions = folderActions,
                                onSettingsClicked = {
                                    scope.launch {
                                        drawerState.close()
                                        if (findNavController().currentDestination?.id == R.id.nav_home) {
                                            findNavController().navigate(R.id.action_home_to_settings)
                                        }
                                    }
                                }
                            )
                        }
                    ) { mainContent() }
                }

            }
        }
    }

    companion object {
        private const val KEY_PENDING_CAPTURE_FILE = "pending_capture_file"
    }
}
