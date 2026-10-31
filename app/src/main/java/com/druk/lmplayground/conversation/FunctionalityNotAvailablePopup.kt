package com.druk.lmplayground.conversation

import androidx.compose.ui.Modifier
import com.druk.lmplayground.theme.menuOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.druk.lmplayground.R

@Composable
fun FunctionalityNotAvailablePopup(onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.menuOutline(),
        onDismissRequest = onDismiss,
        text = {
            Text(
                text = stringResource(R.string.functionality_not_available),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        }
    )
}
