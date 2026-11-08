@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.shareMarkdown
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Logs (PrivateLM's log view): the app's recent log lines,
 * including the model engine's, to see what went wrong without a computer.
 * Android lets an app read only its own log. Share sends them as a file.
 */
class LogsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                LogsScreen(onBackClick = { findNavController().popBackStack() })
            }
        }
    }
}

/** The newest [MAX_LINES] lines of this app's log. */
private fun readLog(): List<String> = try {
    val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", MAX_LINES.toString())
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().use { it.readLines() }.also { process.waitFor() }
} catch (e: Exception) {
    listOf("Couldn't read the log: ${e.message}")
}

private fun clearLog() {
    try { ProcessBuilder("logcat", "-c").start().waitFor() } catch (_: Exception) {}
}

private const val MAX_LINES = 1000

@Composable
private fun LogsScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(refresh) {
        lines = withContext(Dispatchers.IO) { readLog() }
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    val title = stringResource(R.string.logs)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.logs_refresh))
                    }
                    IconButton(onClick = {
                        scope.launch { shareMarkdown(context, "log", "```\n" + lines.joinToString("\n") + "\n```\n", title) }
                    }) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.share))
                    }
                    IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { clearLog() }
                            refresh++
                        }
                    }) {
                        Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.logs_clear))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (lines.isEmpty()) {
                Text(
                    stringResource(R.string.logs_empty),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                ) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            softWrap = false,
                            color = when {
                                line.contains(" E/") -> MaterialTheme.colorScheme.error
                                line.contains(" W/") -> Color(0xFFE0A030)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}
