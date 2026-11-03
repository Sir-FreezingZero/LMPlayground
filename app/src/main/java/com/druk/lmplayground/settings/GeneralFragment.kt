@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.HtmlPreviewInternet
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.theme.PlaygroundTheme

/**
 * Settings -> General: app settings no model controls, in the Tools page's
 * style (headed sections of compact switch rows; tap a row for its details).
 */
class GeneralFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                GeneralScreen(onBackClick = { findNavController().popBackStack() })
            }
        }
    }
}

@Composable
private fun GeneralScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.general)) },
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
            SettingsSection(R.string.general_chats) {
                var includeDate by remember { mutableStateOf(prefs.includeDateInPrompt) }
                CompactRow(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.date_in_prompt_title),
                    description = stringResource(R.string.date_in_prompt_desc),
                    checked = includeDate,
                    onToggle = { includeDate = it; prefs.includeDateInPrompt = it },
                )
                var autoName by remember { mutableStateOf(prefs.autoNameChats) }
                CompactRow(
                    icon = Icons.Outlined.Edit,
                    title = stringResource(R.string.auto_name_chats_title),
                    description = stringResource(R.string.auto_name_chats_desc),
                    checked = autoName,
                    onToggle = { autoName = it; prefs.autoNameChats = it },
                )
                var quickToolNames by remember { mutableStateOf(prefs.quickToolNames) }
                CompactRow(
                    icon = Icons.Outlined.Build,
                    title = stringResource(R.string.quick_tool_names_title),
                    description = stringResource(R.string.quick_tool_names_desc),
                    checked = quickToolNames,
                    onToggle = { quickToolNames = it; prefs.quickToolNames = it },
                )
            }
            SettingsSection(R.string.general_previews) {
                var previews by remember { mutableStateOf(prefs.livePreviews) }
                CompactRow(
                    icon = Icons.Outlined.Web,
                    title = stringResource(R.string.live_previews_title),
                    description = stringResource(R.string.live_previews_desc),
                    checked = previews,
                    onToggle = { previews = it; prefs.livePreviews = it },
                )
                // Turning access off also deletes everything previews stored online.
                var internet by remember { mutableStateOf(HtmlPreviewInternet.isEnabled(context)) }
                CompactRow(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.tools_html_preview_internet_title),
                    description = stringResource(R.string.tools_html_preview_internet_desc),
                    checked = internet,
                    onToggle = { internet = it; HtmlPreviewInternet.setEnabled(context, it) },
                )
            }
        }
    }
}
