@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.server.PhoneServer
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Settings → Phone server (PrivateLM's server mode): share the model loaded
 * on the phone with other apps as an OpenAI-style server. Apps on this phone
 * only by default; other devices on the Wi-Fi need the API key. Runs while
 * the app is open.
 */
class PhoneServerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                PhoneServerScreen(onBackClick = { findNavController().popBackStack() })
            }
        }
    }
}

private fun newApiKey(): String =
    "sk-" + ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

@Composable
private fun PhoneServerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    val scope = rememberCoroutineScope()
    val running by PhoneServer.running.collectAsState()
    val url by PhoneServer.url.collectAsState()
    val log by PhoneServer.log.collectAsState()
    var port by remember { mutableStateOf(prefs.serverPort.toString()) }
    var allowNetwork by remember { mutableStateOf(prefs.serverAllowNetwork) }
    var apiKey by remember { mutableStateOf(prefs.serverApiKey.ifEmpty { newApiKey().also { prefs.serverApiKey = it } }) }
    var error by remember { mutableStateOf<String?>(null) }
    val servedName = PhoneServer.served?.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.phone_server)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSection(R.string.phone_server) {
                CompactRow(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.phone_server_run),
                    description = stringResource(R.string.phone_server_run_desc),
                    checked = running,
                    onToggle = { on ->
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (on) PhoneServer.start(prefs.serverPort, allowNetwork, apiKey) else PhoneServer.stop()
                                }
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            }
                        }
                    },
                )
                CompactRow(
                    icon = Icons.Outlined.Wifi,
                    title = stringResource(R.string.phone_server_network),
                    description = stringResource(R.string.phone_server_network_desc),
                    checked = allowNetwork,
                    onToggle = { allowNetwork = it; prefs.serverAllowNetwork = it },
                )
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { text ->
                            port = text.filter(Char::isDigit).take(5)
                            port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { prefs.serverPort = it }
                        },
                        label = { Text(stringResource(R.string.phone_server_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim(); prefs.serverApiKey = apiKey; PhoneServer.updateApiKey(apiKey) },
                        label = { Text(stringResource(R.string.phone_server_api_key)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            apiKey = newApiKey(); prefs.serverApiKey = apiKey; PhoneServer.updateApiKey(apiKey)
                        }) { Text(stringResource(R.string.phone_server_new_key)) }
                        OutlinedButton(onClick = { copy(context, apiKey) }) { Text(stringResource(R.string.copy)) }
                    }
                    if (!allowNetwork) {
                        Text(
                            stringResource(R.string.phone_server_key_local),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when {
                            servedName != null -> stringResource(R.string.phone_server_model, servedName)
                            else -> stringResource(R.string.phone_server_no_model)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    url?.let { address ->
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedButton(onClick = { copy(context, address) }) { Text(stringResource(R.string.copy)) }
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (log.isNotEmpty()) {
                SettingsSection(R.string.logs) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("LM Playground", text))
}
