// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

@Composable
internal fun LauncherAppActions(
    state: LauncherUiState,
    model: LauncherViewModel,
    open: () -> Unit,
) {
    val edit = state.appEdit as? LauncherAppEdit.Menu ?: return
    val app = state.editedApp ?: return
    val focus = remember(edit) { LauncherFocus() }
    val actions = if (state.apps.catalog.size > 1) listOf("open", "move") else listOf("open")
    val enabled = actions.filter { it == "open" || !state.orderWriting }
    Dialog(
        onDismissRequest = model::dismissAppEdit,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        LaunchedEffect(enabled) { focus.request(focus.current?.takeIf { it in enabled } ?: enabled.first()) }
        BoxWithConstraints(
            Modifier.fillMaxSize().background(LauncherStyle.scrim).onPreviewKeyEvent {
                if (it.isRepeatedLauncherActivation) {
                    true
                } else if (it.type != KeyEventType.KeyDown ||
                    it.key !in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
                ) {
                    false
                } else {
                    val index = enabled.indexOf(focus.current).coerceAtLeast(0)
                    if (it.key == Key.DirectionUp) focus.request(enabled[(index - 1).coerceAtLeast(0)])
                    if (it.key == Key.DirectionDown) focus.request(enabled[(index + 1).coerceAtMost(enabled.lastIndex)])
                    true
                }
            },
        ) {
            val density = LocalDensity.current
            val width = minOf(LauncherStyle.menuWidth, maxWidth - LauncherStyle.menuPadding * 2)
            val height =
                LauncherStyle.menuTitle + LauncherStyle.menuRow * actions.size + LauncherStyle.menuPadding * 2 +
                    if (state.orderWriting) LauncherStyle.menuRow else 0.dp
            val anchor = edit.target.anchor
            val x =
                with(density) { anchor.left.toDp() }.coerceIn(
                    LauncherStyle.menuPadding,
                    (maxWidth - width - LauncherStyle.menuPadding).coerceAtLeast(LauncherStyle.menuPadding),
                )
            val below = with(density) { anchor.bottom.toDp() } + LauncherStyle.menuPadding
            val y =
                (if (below + height <= maxHeight) below else with(density) { anchor.top.toDp() } - height).coerceIn(
                    LauncherStyle.menuPadding,
                    (maxHeight - height - LauncherStyle.menuPadding).coerceAtLeast(LauncherStyle.menuPadding),
                )
            Column(
                Modifier
                    .offset { IntOffset(x.roundToPx(), y.roundToPx()) }
                    .width(width)
                    .background(LauncherStyle.menu)
                    .padding(LauncherStyle.menuPadding)
                    .focusGroup(),
            ) {
                Text(
                    app.label,
                    Modifier.height(LauncherStyle.menuTitle).padding(horizontal = LauncherStyle.menuPadding),
                    color = LauncherStyle.menuText,
                    style = LauncherStyle.body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                actions.forEach { action ->
                    key(action) {
                        AppActionRow(
                            stringResource(if (action == "open") R.string.open_app else R.string.move_app),
                            focus,
                            action,
                            action in enabled,
                        ) { if (action == "open") open() else model.startMoving() }
                    }
                }
                if (state.orderWriting) {
                    Text(
                        stringResource(R.string.saving_app_order),
                        Modifier.height(LauncherStyle.menuRow),
                        color = LauncherStyle.menuText,
                        style = LauncherStyle.body,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppActionRow(
    label: String,
    focus: LauncherFocus,
    target: String,
    enabled: Boolean,
    click: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(LauncherStyle.menuRow)
            .launcherFocus(focus, target)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) LauncherStyle.menuFocus else LauncherStyle.clear)
            .combinedClickable(
                interactionSource =
                    remember {
                        MutableInteractionSource()
                    },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = click,
            ).padding(horizontal = LauncherStyle.menuPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = LauncherStyle.menuText.copy(alpha = if (enabled) 1f else 0.45f), style = LauncherStyle.body)
    }
}
