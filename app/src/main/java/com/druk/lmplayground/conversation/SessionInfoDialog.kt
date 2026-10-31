package com.druk.lmplayground.conversation

import com.druk.lmplayground.theme.menuBorder
import com.druk.lmplayground.theme.MenuCardShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * "Session Info", opened by tapping a reply's token count. The engine's
 * report is "Heading" lines followed by indented "Label: value" lines; it is
 * shown as small headings over label/value rows, with tighter padding than a
 * standard AlertDialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoDialog(report: String, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MenuCardShape, border = menuBorder, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.session_info),
                    style = MaterialTheme.typography.titleMedium
                )
                report.lines().filter { it.isNotBlank() }.forEach { line ->
                    if (!line.startsWith(" ")) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                text = line.substringBefore(':').trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = line.substringAfter(':', "").trim(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
