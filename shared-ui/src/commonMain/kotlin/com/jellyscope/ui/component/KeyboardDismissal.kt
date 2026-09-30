// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.platform.LocalFocusManager
import com.jellyscope.ui.platform.LocalPlatformCapabilities

/**
 * On soft-keyboard platforms, clears focus on any tap outside the focused [keepKeyboardOnTap] field.
 * Taps are ignored while no marked field is focused, so unmarked content keeps its own focus.
 */
fun Modifier.dismissKeyboardOnTap(): Modifier = this then DismissKeyboardOnTapElement

fun Modifier.keepKeyboardOnTap(): Modifier = this then KeepKeyboardOnTapElement

/** Leaves a focused text field on hardware Escape instead of letting Escape navigate back. */
fun Modifier.clearFocusOnEscape(): Modifier = this then ClearFocusOnEscapeElement

/** Returns an IME-action handler that dismisses the soft keyboard; a no-op on desktop. */
@Composable
fun rememberSoftKeyboardDismissal(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val enabled = LocalPlatformCapabilities.current.softKeyboardDismissal
    return remember(focusManager, enabled) { { if (enabled) focusManager.clearFocus() } }
}

private data object DismissKeyboardOnTapElement : ModifierNodeElement<DismissKeyboardOnTapNode>() {
    override fun create() = DismissKeyboardOnTapNode()

    override fun update(node: DismissKeyboardOnTapNode) = Unit
}

private data object KeepKeyboardOnTapElement : ModifierNodeElement<KeepKeyboardOnTapNode>() {
    override fun create() = KeepKeyboardOnTapNode()

    override fun update(node: KeepKeyboardOnTapNode) = Unit
}

private data object ClearFocusOnEscapeElement : ModifierNodeElement<ClearFocusOnEscapeNode>() {
    override fun create() = ClearFocusOnEscapeNode()

    override fun update(node: ClearFocusOnEscapeNode) = Unit
}

private data object DismissKeyboardTraverseKey

private class DismissKeyboardOnTapNode :
    DelegatingNode(),
    TraversableNode,
    CompositionLocalConsumerModifierNode {
    override val traverseKey: Any = DismissKeyboardTraverseKey

    var tapStartedInTextField = false
    var focusedTextField: KeepKeyboardOnTapNode? = null

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    // Initial pass reaches this ancestor before any descendant field can mark the tap.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    tapStartedInTextField = false
                    if (
                        awaitTapRelease(down) &&
                        !tapStartedInTextField &&
                        focusedTextField?.isAttached == true &&
                        currentValueOf(LocalPlatformCapabilities).softKeyboardDismissal
                    ) {
                        currentValueOf(LocalFocusManager).clearFocus()
                    }
                }
            },
        )
    }
}

private class KeepKeyboardOnTapNode :
    DelegatingNode(),
    FocusEventModifierNode {
    private val dismissNode: DismissKeyboardOnTapNode?
        get() = findNearestAncestor(DismissKeyboardTraverseKey) as? DismissKeyboardOnTapNode

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dismissNode?.tapStartedInTextField = true
                }
            },
        )
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (!isAttached) return
        val dismissNode = dismissNode ?: return
        if (focusState.hasFocus) {
            dismissNode.focusedTextField = this
        } else if (dismissNode.focusedTextField === this) {
            dismissNode.focusedTextField = null
        }
    }
}

private class ClearFocusOnEscapeNode :
    Modifier.Node(),
    KeyInputModifierNode,
    CompositionLocalConsumerModifierNode {
    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key != Key.Escape) return false
        currentValueOf(LocalFocusManager).clearFocus()
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}

// Initial pass sees a real release unconsumed and a synthetic cancel pre-consumed; Final lets buttons click first.
private suspend fun AwaitPointerEventScope.awaitTapRelease(down: PointerInputChange): Boolean {
    while (true) {
        val change =
            awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                ?: return false
        if (change.changedToUpIgnoreConsumed()) {
            val released = !change.isConsumed
            awaitPointerEvent(PointerEventPass.Final)
            return released
        }
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return false
    }
}
