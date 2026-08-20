// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component.launch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.jellyscope.ui.theme.AmbientLaunchDimens
import com.jellyscope.ui.theme.AmbientLaunchTokens

private val AmbientTextSelectionColors =
    TextSelectionColors(
        handleColor = AmbientLaunchTokens.accent,
        backgroundColor = AmbientLaunchTokens.accent.copy(alpha = 0.38f),
    )

/** Theme-independent outlined text field for Ambient launch forms. */
@Composable
fun AmbientTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    isError: Boolean = false,
    contentDescription: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val borderColor =
        when {
            isError -> AmbientLaunchTokens.error
            focused -> AmbientLaunchTokens.accent
            else -> AmbientLaunchTokens.glassBorder
        }
    val shape = RoundedCornerShape(AmbientLaunchDimens.controlRadius)

    CompositionLocalProvider(LocalTextSelectionColors provides AmbientTextSelectionColors) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
        ) {
            Text(
                text = label,
                color = if (isError) AmbientLaunchTokens.error else AmbientLaunchTokens.textSecondary,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = AmbientLaunchDimens.controlMinHeight)
                        .clip(shape)
                        .background(AmbientLaunchTokens.glassFill)
                        .border(AmbientLaunchDimens.controlBorder, borderColor, shape)
                        .semantics {
                            this.contentDescription = contentDescription ?: label
                        },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = AmbientLaunchTokens.textPrimary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
                cursorBrush = SolidColor(AmbientLaunchTokens.accent),
                decorationBox = { innerTextField ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = AmbientLaunchDimens.controlHorizontalPadding,
                                    vertical = AmbientLaunchDimens.controlVerticalPadding,
                                ),
                        horizontalArrangement = Arrangement.spacedBy(AmbientLaunchDimens.controlGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leadingIcon?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                modifier = Modifier.size(AmbientLaunchDimens.cardIconSize),
                                tint = AmbientLaunchTokens.accent,
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) { innerTextField() }
                        trailingContent?.invoke()
                    }
                },
            )
        }
    }
}
