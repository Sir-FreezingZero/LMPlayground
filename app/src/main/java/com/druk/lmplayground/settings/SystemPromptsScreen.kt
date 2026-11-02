@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * Full-screen System Prompts page used by [SystemPromptsFragment] on phone:
 * [SystemPromptsContent] in a Scaffold with a back button. The tablet
 * Settings detail pane embeds the Content on its own.
 */
@Composable
fun SystemPromptsScreen(
    defaultPrompt: String,
    onDefaultPromptChange: (String) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_prompts)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        SystemPromptsContent(
            defaultPrompt = defaultPrompt,
            onDefaultPromptChange = onDefaultPromptChange,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

/**
 * The page body: the default system prompt, used by every chat without its
 * own (set in the chat's ⋮ menu). An empty box means no prompt.
 */
@Composable
fun SystemPromptsContent(
    defaultPrompt: String,
    onDefaultPromptChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        PromptSection(R.string.default_system_prompt) {
            var text by remember { mutableStateOf(defaultPrompt) }
            PromptField(text, stringResource(R.string.system_prompt_none)) { text = it; onDefaultPromptChange(it) }
        }
    }
}

/** A [SettingsSection] whose [content] is spaced and inset like the page's text boxes. */
@Composable
internal fun PromptSection(
    @StringRes title: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = SettingsSection(title, modifier) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A system prompt box; [placeholder] shows while it is empty. */
@Composable
internal fun PromptField(value: String, placeholder: String, onValueChange: (String) -> Unit) = OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    textStyle = MaterialTheme.typography.bodySmall,
    placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
    minLines = 3,
)

/**
 * The exact text the chat sends before every message (system prompt, date,
 * memories and active tools, in the model's chat format), from [render]
 * (null or "" when no model is loaded). Tap to expand, long-press to copy.
 */
@Composable
internal fun PromptStartSection(render: suspend () -> String?) {
    var text by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { text = render() ?: "" }
    PromptSection(R.string.prompt_start_title, Modifier.clickable { expanded = !expanded }) {
        Text(
            text = stringResource(R.string.prompt_start_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = when {
                    text == null -> "…"
                    text!!.isEmpty() -> stringResource(R.string.prompt_start_no_model)
                    else -> text!!
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
