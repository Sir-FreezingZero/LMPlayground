package com.druk.lmplayground.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.ConversationViewModel
import com.druk.lmplayground.server.PhoneServer
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.PlaygroundTheme
import com.druk.lmplayground.ui.BigButton
import com.druk.lmplayground.ui.GroupCard
import com.druk.lmplayground.ui.GroupDivider
import com.druk.lmplayground.ui.GroupRow
import com.druk.lmplayground.ui.RowIcon
import com.druk.lmplayground.ui.SectionLabel
import com.druk.lmplayground.ui.StatusBadge
import com.druk.lmplayground.ui.TabBottomSpace
import com.druk.lmplayground.ui.TabColors
import com.druk.lmplayground.ui.TabSwitch
import com.druk.lmplayground.ui.TabTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * The Server tab (PrivateLM's server mode, laid out like the
 * cross-platform-llm-client's Server screen): share the model loaded on the
 * phone as an OpenAI-style API. Apps on this phone only by default; letting
 * Wi-Fi devices in always requires the API key. Runs while the app is open.
 */
class PhoneServerFragment : Fragment() {

    private val chat: ConversationViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            val loaded by chat.loadedModel.observeAsState()
            val ready by chat.isModelReady.observeAsState(false)
            PlaygroundTheme {
                ServerTab(modelFile = loaded?.filename?.substringAfterLast('|'), modelReady = ready)
            }
        }
    }
}

private fun newApiKey(): String =
    "sk-" + ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

@Composable
private fun ServerTab(modelFile: String?, modelReady: Boolean) {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    val scope = rememberCoroutineScope()
    val running by PhoneServer.running.collectAsState()
    val url by PhoneServer.url.collectAsState()
    val log by PhoneServer.log.collectAsState()
    var allowNetwork by remember { mutableStateOf(prefs.serverAllowNetwork) }
    var requireKey by remember { mutableStateOf(prefs.serverRequireKey) }
    var apiKey by remember { mutableStateOf(prefs.serverApiKey) }
    var port by remember { mutableStateOf(prefs.serverPort) }
    var editPort by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Network access always needs the key.
    val keyOn = requireKey || allowNetwork
    val canServe = modelFile != null && modelReady

    // Reads the saved settings, not this composition's values, which are
    // stale inside a click handler that has just changed them.
    fun restartIfRunning() {
        if (!PhoneServer.running.value) return
        val network = prefs.serverAllowNetwork
        val key = prefs.serverApiKey.takeIf { prefs.serverRequireKey || network }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PhoneServer.stop()
                    PhoneServer.start(prefs.serverPort, network, key)
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            TabTitle(stringResource(R.string.server_tab))

            // Status and Start/Stop.
            GroupCard {
                Column(Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (running) TabColors.Green else MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.server_local_api),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(if (running) R.string.server_running else R.string.server_stopped),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            running && url != null -> url!!
                            modelFile != null -> stringResource(R.string.server_expose, modelFile)
                            else -> stringResource(R.string.phone_server_no_model)
                        },
                        style = if (running) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                        else MaterialTheme.typography.bodyMedium,
                        color = if (running) TabColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (running && url != null) {
                        TextButton(
                            onClick = { copy(context, url!!) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text(stringResource(R.string.copy), color = TabColors.Accent) }
                    }
                    error?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = TabColors.Red, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    BigButton(
                        text = stringResource(if (running) R.string.server_stop else R.string.server_start),
                        icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        color = if (running) TabColors.Red else TabColors.Accent,
                        enabled = running || canServe,
                    ) {
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (running) PhoneServer.stop()
                                    else PhoneServer.start(prefs.serverPort, allowNetwork, if (keyOn) apiKey else null)
                                }
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            }
                        }
                    }
                }
            }

            SectionLabel(stringResource(R.string.server_model))
            GroupCard {
                Row(Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(Icons.Outlined.Memory)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            modelFile ?: stringResource(R.string.server_no_model_title),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(if (modelFile != null) R.string.server_loaded_here else R.string.server_load_in_models),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (canServe) StatusBadge(stringResource(R.string.server_ready))
                }
            }

            SectionLabel(stringResource(R.string.server_access))
            GroupCard {
                GroupRow(
                    icon = Icons.Outlined.AccountTree,
                    title = stringResource(R.string.server_allow_network),
                    subtitle = stringResource(if (allowNetwork) R.string.server_network_on else R.string.server_network_off),
                    trailing = {
                        TabSwitch(allowNetwork) {
                            allowNetwork = it
                            prefs.serverAllowNetwork = it
                            if (it && apiKey.isBlank()) { apiKey = newApiKey(); prefs.serverApiKey = apiKey }
                            restartIfRunning()
                        }
                    },
                )
                GroupDivider()
                GroupRow(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.server_require_key),
                    subtitle = stringResource(if (allowNetwork) R.string.server_key_required else R.string.server_key_desc),
                    trailing = {
                        TabSwitch(keyOn, enabled = !allowNetwork) {
                            requireKey = it
                            prefs.serverRequireKey = it
                            if (it && apiKey.isBlank()) { apiKey = newApiKey(); prefs.serverApiKey = apiKey }
                            PhoneServer.updateApiKey(if (it) apiKey else null)
                        }
                    },
                )
                GroupDivider()
                GroupRow(
                    icon = Icons.Outlined.Numbers,
                    title = stringResource(R.string.phone_server_port),
                    value = port.toString(),
                    onClick = { editPort = true },
                )
            }

            SectionLabel(stringResource(R.string.phone_server_api_key))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = com.druk.lmplayground.ui.TabEdge, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (apiKey.isEmpty()) {
                        Text(
                            stringResource(R.string.server_key_optional),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    BasicTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it.trim()
                            prefs.serverApiKey = apiKey
                            PhoneServer.updateApiKey(if (keyOn) apiKey else null)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(TabColors.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = {
                    apiKey = newApiKey(); prefs.serverApiKey = apiKey
                    PhoneServer.updateApiKey(if (keyOn) apiKey else null)
                }) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.phone_server_new_key), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { copy(context, apiKey) }, enabled = apiKey.isNotEmpty()) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge))
            Text(
                stringResource(R.string.server_key_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 4.dp),
            )

            if (log.isNotEmpty()) {
                SectionLabel(stringResource(R.string.logs))
                GroupCard {
                    Column(Modifier.padding(horizontal = com.druk.lmplayground.ui.TabEdge, vertical = 8.dp)) {
                        log.asReversed().forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            TabBottomSpace()
        }
    }

    if (editPort) {
        var text by remember { mutableStateOf(port.toString()) }
        val value = text.toIntOrNull()?.takeIf { it in 1024..65535 }
        AlertDialog(
            onDismissRequest = { editPort = false },
            title = { Text(stringResource(R.string.phone_server_port)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    isError = value == null,
                    supportingText = { Text(stringResource(R.string.server_port_range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = value != null, onClick = {
                    port = value!!
                    prefs.serverPort = value
                    editPort = false
                    restartIfRunning()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editPort = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("LM Playground", text))
}
