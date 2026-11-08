package com.druk.lmplayground.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SecurityUpdate
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.druk.lmplayground.R
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.ui.GroupCard
import com.druk.lmplayground.ui.GroupDivider
import com.druk.lmplayground.ui.GroupRow
import com.druk.lmplayground.ui.SectionLabel
import com.druk.lmplayground.ui.TabBottomSpace
import com.druk.lmplayground.ui.TabTitle
import java.util.Locale

/** Where each Settings row goes; the fragment does the navigating. */
class SettingsTabActions(
    val appearance: () -> Unit,
    val soundHaptic: () -> Unit,
    val language: () -> Unit,
    val chatOptions: () -> Unit,
    val systemPrompt: () -> Unit,
    val modelSettings: () -> Unit,
    val tools: () -> Unit,
    val memory: () -> Unit,
    val remoteServers: () -> Unit,
    val advanced: () -> Unit,
    val testing: () -> Unit,
    val logs: () -> Unit,
    val faq: () -> Unit,
    val feedback: () -> Unit,
    val privacy: () -> Unit,
    /** Debug builds only: crash the inference engine. */
    val crashEngine: (() -> Unit)?,
)

/**
 * The Settings tab, laid out like the cross-platform-llm-client's Settings
 * screen: sections of grouped rows with a value and a chevron. Every
 * LM Playground setting is here, regrouped; rows for features the app
 * doesn't have yet are greyed out as "Coming soon".
 */
@Composable
fun SettingsTab(actions: SettingsTabActions, appVersion: String) {
    val context = LocalContext.current
    val prefs = remember { StoragePreferences(context) }
    // Read when the tab is shown, so values changed on a sub-page show on return.
    val params = remember { prefs.modelParams }
    val themeLabel = when (prefs.themeMode) {
        1 -> stringResource(R.string.settings_theme_light)
        2 -> stringResource(R.string.settings_theme_dark)
        else -> stringResource(R.string.settings_theme_system)
    }
    val languageLabel = currentLanguageTag()?.let { tag ->
        Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }
    } ?: stringResource(R.string.settings_theme_system)
    val performanceLabel = stringResource(
        when (prefs.performanceMode) {
            StoragePreferences.PERFORMANCE_GPU -> R.string.performance_gpu
            StoragePreferences.PERFORMANCE_CPU -> R.string.performance_cpu
            else -> R.string.performance_auto
        }
    )

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            TabTitle(stringResource(R.string.settings))

            SectionLabel(stringResource(R.string.general))
            GroupCard {
                GroupRow(Icons.Outlined.Palette, stringResource(R.string.appearance), value = themeLabel, onClick = actions.appearance)
                GroupDivider()
                GroupRow(Icons.Outlined.VolumeUp, stringResource(R.string.sound_and_haptic), onClick = actions.soundHaptic)
                GroupDivider()
                GroupRow(Icons.Outlined.Language, stringResource(R.string.language), value = languageLabel, onClick = actions.language)
            }

            SectionLabel(stringResource(R.string.settings_section_chat))
            GroupCard {
                GroupRow(Icons.Outlined.Memory, stringResource(R.string.settings_inference_mode), value = "Local", onClick = {}, comingSoon = true)
                GroupDivider()
                GroupRow(
                    Icons.Outlined.ChatBubbleOutline,
                    stringResource(R.string.settings_system_prompt),
                    value = stringResource(
                        if (prefs.defaultSystemPrompt.isBlank()) R.string.settings_prompt_default else R.string.settings_prompt_custom
                    ),
                    onClick = actions.systemPrompt,
                )
                GroupDivider()
                GroupRow(Icons.Outlined.Tune, stringResource(R.string.model_parameters), value = "%.2f".format(params.temperature), onClick = actions.modelSettings)
                GroupDivider()
                GroupRow(Icons.Outlined.Build, stringResource(R.string.tools), onClick = actions.tools)
                GroupDivider()
                GroupRow(Icons.Outlined.Psychology, stringResource(R.string.memory), onClick = actions.memory)
                GroupDivider()
                GroupRow(Icons.AutoMirrored.Outlined.Article, stringResource(R.string.settings_chat_options), onClick = actions.chatOptions)
                GroupDivider()
                GroupRow(Icons.Outlined.Computer, stringResource(R.string.remote_servers), onClick = actions.remoteServers)
            }

            SectionLabel(stringResource(R.string.performance))
            GroupCard {
                GroupRow(Icons.Outlined.Speed, stringResource(R.string.settings_gguf_litert), value = performanceLabel, onClick = actions.modelSettings)
                GroupDivider()
                GroupRow(Icons.Outlined.Image, stringResource(R.string.settings_image_generation), value = "CPU", onClick = {}, comingSoon = true)
                GroupDivider()
                GroupRow(Icons.Outlined.Hub, stringResource(R.string.settings_phone_cluster), onClick = {}, comingSoon = true)
                GroupDivider()
                GroupRow(Icons.Outlined.Tune, stringResource(R.string.advanced), onClick = actions.advanced)
            }

            SectionLabel(stringResource(R.string.settings_section_diagnostics))
            GroupCard {
                GroupRow(Icons.Outlined.Science, stringResource(R.string.testing), onClick = actions.testing)
                GroupDivider()
                GroupRow(Icons.AutoMirrored.Outlined.ReceiptLong, stringResource(R.string.logs), onClick = actions.logs)
                if (actions.crashEngine != null) {
                    GroupDivider()
                    GroupRow(Icons.Outlined.BugReport, stringResource(R.string.debug_crash_engine_title), onClick = actions.crashEngine)
                }
            }

            SectionLabel(stringResource(R.string.settings_section_about))
            GroupCard {
                GroupRow(Icons.Outlined.SecurityUpdate, stringResource(R.string.settings_app_updates), onClick = {}, comingSoon = true)
                GroupDivider()
                GroupRow(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.faq), onClick = actions.faq)
                GroupDivider()
                GroupRow(Icons.Outlined.Mail, stringResource(R.string.send_feedback), onClick = actions.feedback)
                GroupDivider()
                GroupRow(Icons.Outlined.Policy, stringResource(R.string.privacy_policy), onClick = actions.privacy)
                GroupDivider()
                GroupRow(Icons.Outlined.Info, stringResource(R.string.version, appVersion))
            }
            TabBottomSpace()
        }
    }
}
