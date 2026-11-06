@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.remote.RemoteServer
import com.druk.lmplayground.remote.RemoteServers
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.launch

/**
 * Settings -> Remote servers: computers on the network whose models show up
 * in the model picker next to the phone's own. Add one by address, or scan
 * the Wi-Fi for LM Studio, Ollama and llama-server.
 */
class RemoteServersFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                RemoteServersScreen(onBackClick = { findNavController().popBackStack() })
            }
        }
    }
}

@Composable
private fun RemoteServersScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(RemoteServers.saved(prefs)) }
    // Each saved server's model count, or null while unknown / not answering.
    val counts = remember { mutableStateMapOf<String, Int?>() }
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<RemoteServer>?>(null) }

    fun update(list: List<RemoteServer>) {
        servers = list
        RemoteServers.save(prefs, list)
    }
    LaunchedEffect(servers) {
        servers.filter { it.url !in counts }.forEach { server ->
            launch { counts[server.url] = RemoteServers.models(server)?.size ?: -1 }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_servers)) },
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
            Text(
                text = stringResource(R.string.remote_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (servers.isNotEmpty()) {
                SettingsSection(R.string.remote_saved) {
                    servers.forEach { server ->
                        val count = counts[server.url]
                        ServerRow(
                            server = server,
                            subtitle = when {
                                count == null -> stringResource(R.string.remote_connecting)
                                count < 0 -> stringResource(R.string.remote_offline)
                                else -> stringResource(R.string.remote_models, count)
                            },
                            actionIcon = Icons.Outlined.Close,
                            actionLabel = stringResource(R.string.remove),
                        ) { update(servers - server) }
                    }
                }
            }
            SettingsSection(R.string.remote_add) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.remote_address)) },
                        placeholder = { Text("192.168.1.5:1234") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(R.string.remote_api_key)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        TextButton(
                            enabled = !scanning,
                            onClick = {
                                scanning = true
                                scope.launch {
                                    found = RemoteServers.scan()
                                    scanning = false
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Wifi, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(if (scanning) R.string.remote_scanning else R.string.remote_scan))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            enabled = RemoteServers.normalize(address).isNotEmpty(),
                            onClick = {
                                val url = RemoteServers.normalize(address)
                                update(servers.filter { it.url != url } + RemoteServer(url, key.trim().ifBlank { null }))
                                counts.remove(url)
                                address = ""
                                key = ""
                            },
                        ) { Text(stringResource(R.string.remote_add_button)) }
                    }
                }
                found?.let { list ->
                    val fresh = list.filter { f -> servers.none { it.url == f.url } }
                    if (fresh.isEmpty()) {
                        Text(
                            text = stringResource(R.string.remote_scan_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    fresh.forEach { server ->
                        ServerRow(
                            server = server,
                            subtitle = stringResource(R.string.remote_found),
                            actionIcon = Icons.Outlined.Add,
                            actionLabel = stringResource(R.string.remote_add_button),
                        ) { update(servers + server) }
                    }
                }
            }
        }
    }
}

/** A computer icon, the server's address over [subtitle], and one action. */
@Composable
private fun ServerRow(
    server: RemoteServer,
    subtitle: String,
    actionIcon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Computer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(server.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAction) { Icon(actionIcon, actionLabel) }
    }
}
