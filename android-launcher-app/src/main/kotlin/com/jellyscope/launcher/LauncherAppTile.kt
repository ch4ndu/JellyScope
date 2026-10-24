// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text

// A fresh control must not adopt a repeat from a press that began on another
// control. Initial down/up and long-press timing remain owned by foundation.
internal val KeyEvent.isRepeatedLauncherActivation: Boolean
    get() =
        type == KeyEventType.KeyDown &&
            nativeKeyEvent.repeatCount > 0 &&
            when (key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> true
                else -> false
            }

@Composable
internal fun LauncherAppTile(
    app: LauncherApp,
    label: String,
    focus: LauncherFocus,
    target: String,
    position: Int,
    interactionEpoch: Int,
    clickLabel: String,
    onFocused: () -> Unit,
    direction: (Key) -> Boolean,
    onClick: () -> Unit,
    onLongClick: ((Rect) -> Unit)? = null,
    moving: Boolean = false,
    selected: Boolean = false,
) {
    // A mode change retires the old press owner. The root repeat guard also
    // prevents its held Select from seeding the replacement control.
    key(interactionEpoch) {
        var focused by remember { mutableStateOf(false) }
        var bounds by remember { mutableStateOf(Rect.Zero) }
        val active = focused || selected || moving
        val scale by animateFloatAsState(
            if (active) LauncherStyle.APP_FOCUS_SCALE else 1f,
            tween(LauncherStyle.FOCUS_MILLIS),
            label = "app-focus",
        )
        val reveal = remember { BringIntoViewRequester() }
        val overhang = with(LocalDensity.current) { (LauncherStyle.appWidth * (LauncherStyle.APP_FOCUS_SCALE - 1f) / 2).toPx() }
        LaunchedEffect(focused, position) {
            if (focused) reveal.bringIntoView(Rect(-overhang, -overhang, bounds.width + overhang, bounds.height + overhang))
        }
        val longLabel = if (onLongClick == null) null else stringResource(R.string.app_options)
        val moveDescription = stringResource(R.string.move_instructions)
        Column(
            Modifier
                .width(LauncherStyle.appWidth)
                .zIndex(if (active) 1f else 0f)
                .launcherFocus(focus, target, onFocused)
                .bringIntoViewRequester(reveal)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && direction(it.key) }
                .semantics {
                    contentDescription = label
                    if (moving) stateDescription = moveDescription
                }.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = clickLabel,
                    onLongClickLabel = longLabel,
                    onLongClick =
                        onLongClick?.let { action ->
                            {
                                focus.request(target)
                                action(bounds)
                            }
                        },
                    onClick = onClick,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(LauncherStyle.appWidth, LauncherStyle.appHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }.border(
                        if (active) LauncherStyle.focusBorder else LauncherStyle.noBorder,
                        if (active) LauncherStyle.focusOutline else LauncherStyle.clear,
                        RoundedCornerShape(LauncherStyle.radius),
                    ).clip(RoundedCornerShape(LauncherStyle.radius))
                    .background(LauncherStyle.tile),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    app.banner != null ->
                        Image(
                            app.banner,
                            null,
                            Modifier.size(LauncherStyle.appWidth, LauncherStyle.appHeight),
                            contentScale = ContentScale.Crop,
                        )
                    app.icon != null -> Image(app.icon, null, Modifier.size(LauncherStyle.appIcon))
                    else -> Unit
                }
            }
            Text(
                if (active) label else "",
                Modifier.padding(top = LauncherStyle.appLabelGap).height(LauncherStyle.appLabelHeight),
                color = LauncherStyle.text,
                style = LauncherStyle.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
