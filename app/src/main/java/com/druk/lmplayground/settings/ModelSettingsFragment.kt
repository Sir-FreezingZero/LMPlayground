@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.PlaygroundTheme
import java.io.File

/**
 * Settings → Model: the settings every model uses (PrivateLM-style, one set
 * for all models) and whether models run on the GPU. Parameter changes reach
 * the open chat when you go back to it (a new context size starts that chat
 * over); the performance mode applies from the next model load.
 */
class ModelSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                ModelSettingsScreen(onBackClick = { findNavController().popBackStack() })
            }
        }
    }
}

@Composable
private fun ModelSettingsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_settings)) },
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
            SettingsSection(R.string.model_parameters) {
                var params by remember { mutableStateOf(prefs.modelParams) }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.model_params_all_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelParamsControls(
                        params = params,
                        onParamsChange = { params = it; prefs.modelParams = it },
                        recommendedMaxTokens = prefs.recommendedMaxTokens,
                    )
                }
            }
            SettingsSection(R.string.performance) {
                var mode by remember { mutableStateOf(prefs.performanceMode) }
                val blockedFile = remember { File(context.filesDir, "vulkan_text.blocked") }
                var blocked by remember { mutableStateOf(blockedFile.exists()) }
                listOf(
                    Triple(StoragePreferences.PERFORMANCE_AUTO, R.string.performance_auto, R.string.performance_auto_desc),
                    Triple(StoragePreferences.PERFORMANCE_GPU, R.string.performance_gpu, R.string.performance_gpu_desc),
                    Triple(StoragePreferences.PERFORMANCE_CPU, R.string.performance_cpu, R.string.performance_cpu_desc),
                ).forEach { (value, title, desc) ->
                    ChoiceRow(
                        icon = when (value) {
                            StoragePreferences.PERFORMANCE_GPU -> Icons.Outlined.Bolt
                            StoragePreferences.PERFORMANCE_CPU -> Icons.Outlined.Shield
                            else -> Icons.Outlined.AutoAwesome
                        },
                        title = stringResource(title),
                        description = stringResource(desc),
                        selected = mode == value,
                        onClick = {
                            mode = value
                            prefs.performanceMode = value
                            // Choosing GPU on purpose clears the crash guard.
                            if (value == StoragePreferences.PERFORMANCE_GPU && blockedFile.delete()) blocked = false
                        },
                    )
                }
                Text(
                    text = stringResource(if (blocked) R.string.performance_blocked else R.string.performance_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
