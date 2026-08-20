// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.settings_picker_cancel
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

internal data class SettingsPickerOption<T>(
    val value: T,
    val label: String,
    val supporting: String? = null,
    val disabledReason: String? = null,
    /** Optional visual preview, used for theme swatches. */
    val leading: (@Composable () -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsOptionPicker(
    title: String,
    subtitle: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    selectionInProgress: Boolean = false,
    dismissOnSelection: Boolean = true,
) {
    if (LocalWindowWidthTier.current == WindowWidthTier.Compact) {
        ModalBottomSheet(
            onDismissRequest = { if (!selectionInProgress) onDismiss() },
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            SettingsOptionPickerContent(
                title = title,
                subtitle = subtitle,
                options = options,
                selectedValue = selectedValue,
                onOptionSelected = onOptionSelected,
                onDismiss = onDismiss,
                selectionInProgress = selectionInProgress,
                dismissOnSelection = dismissOnSelection,
            )
        }
    } else {
        AlertDialog(
            onDismissRequest = { if (!selectionInProgress) onDismiss() },
            title = { Text(text = title) },
            text = {
                SettingsOptionList(
                    subtitle = subtitle,
                    options = options,
                    selectedValue = selectedValue,
                    onOptionSelected = onOptionSelected,
                    onDismiss = onDismiss,
                    selectionInProgress = selectionInProgress,
                    dismissOnSelection = dismissOnSelection,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss, enabled = !selectionInProgress) {
                    Text(stringResource(Res.string.settings_picker_cancel))
                }
            },
        )
    }
}

@Composable
private fun <T> SettingsOptionPickerContent(
    title: String,
    subtitle: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    selectionInProgress: Boolean,
    dismissOnSelection: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = Dimensions.playerDialogMaxWidth)
                .padding(Dimensions.formSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        SettingsOptionList(
            subtitle = subtitle,
            options = options,
            selectedValue = selectedValue,
            onOptionSelected = onOptionSelected,
            onDismiss = onDismiss,
            selectionInProgress = selectionInProgress,
            dismissOnSelection = dismissOnSelection,
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = Dimensions.settingsPickerListMaxHeight),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss, enabled = !selectionInProgress) {
                Text(stringResource(Res.string.settings_picker_cancel))
            }
        }
    }
}

@Composable
private fun <T> SettingsOptionList(
    subtitle: String,
    options: List<SettingsPickerOption<T>>,
    selectedValue: T,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    selectionInProgress: Boolean,
    dismissOnSelection: Boolean,
    modifier: Modifier = Modifier.heightIn(max = Dimensions.settingsPickerListMaxHeight),
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // Keep the explanation and options reachable at large font scales.
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (selectionInProgress) {
            CircularProgressIndicator(modifier = Modifier.size(Dimensions.controlButtonIconSize))
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(
                        width = Dimensions.settingsCardBorderWidth,
                        color =
                            MaterialTheme.colorScheme.outline.copy(
                                alpha = Dimensions.settingsCardBorderAlpha,
                            ),
                        shape = MaterialTheme.shapes.small,
                    ),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = option.value == selectedValue
                val isEnabled = option.disabledReason == null && !selectionInProgress
                val optionModifier =
                    if (isEnabled) {
                        // The unlabeled check icon requires selectable semantics.
                        Modifier.selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = {
                                onOptionSelected(option.value)
                                if (dismissOnSelection) onDismiss()
                            },
                        )
                    } else {
                        Modifier
                    }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (isSelected) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(
                                            alpha = Dimensions.settingsPickerSelectedAlpha,
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            ).then(optionModifier)
                            .heightIn(min = Dimensions.minTouchTarget)
                            .padding(horizontal = Dimensions.formSpacing, vertical = Dimensions.contentSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                        ) {
                            option.leading?.invoke()
                            Text(
                                text = option.label,
                                color =
                                    if (isEnabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = Dimensions.settingsPickerDisabledAlpha,
                                        )
                                    },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        (option.disabledReason ?: option.supporting)?.let { supporting ->
                            Text(
                                text = supporting,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimensions.controlButtonIconSize),
                        )
                    }
                }
                if (index < options.lastIndex) {
                    HorizontalDivider(
                        thickness = Dp.Hairline,
                        color =
                            MaterialTheme.colorScheme.outline.copy(
                                alpha = Dimensions.settingsCardBorderAlpha,
                            ),
                    )
                }
            }
        }
    }
}
