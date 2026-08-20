// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_open_source_close
import com.jellyscope.ui.generated.resources.settings_open_source_detail
import com.jellyscope.ui.generated.resources.settings_open_source_title
import com.jellyscope.ui.generated.resources.settings_open_source_view_source
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

internal const val JELLYSCOPE_SOURCE_REPOSITORY = "https://github.com/ch4ndu/JellyScope"

internal fun jellyScopeSourceUrl(sourceRevision: String): String = "$JELLYSCOPE_SOURCE_REPOSITORY/tree/$sourceRevision"

@Composable
internal fun OpenSourceNoticesDialog(
    sourceRevision: String,
    onDismiss: () -> Unit,
) {
    val sourceUrl = jellyScopeSourceUrl(sourceRevision)
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_open_source_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = Dimensions.settingsPickerListMaxHeight)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(Res.string.settings_open_source_detail, sourceUrl, sourceRevision),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.settings_open_source_close))
            }
        },
        dismissButton = {
            TextButton(onClick = { runCatching { uriHandler.openUri(sourceUrl) } }) {
                Text(stringResource(Res.string.settings_open_source_view_source))
            }
        },
    )
}
