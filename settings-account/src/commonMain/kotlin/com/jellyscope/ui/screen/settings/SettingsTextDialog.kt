// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_picker_cancel
import com.jellyscope.ui.generated.resources.settings_text_dialog_save
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsTextDialog(
    title: String,
    value: String,
    onSave: (String, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    label: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var draft by remember(value) { mutableStateOf(value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column {
                subtitle?.let { supportingText ->
                    Text(
                        text = supportingText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = label?.let { text -> { Text(text = text) } },
                    singleLine = true,
                    visualTransformation = visualTransformation,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(draft, onDismiss)
                },
            ) {
                Text(stringResource(Res.string.settings_text_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_picker_cancel))
            }
        },
    )
}
