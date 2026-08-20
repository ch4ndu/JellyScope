// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

@Composable
internal fun ScrollToTopOnChange(
    key: Any?,
    scrollToTop: suspend () -> Unit,
) {
    val currentScrollToTop by rememberUpdatedState(scrollToTop)
    var hasObservedKey by remember { mutableStateOf(false) }
    var previousKey by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(key) {
        if (key == null) {
            return@LaunchedEffect
        }
        if (!hasObservedKey) {
            previousKey = key
            hasObservedKey = true
            return@LaunchedEffect
        }
        if (previousKey != key) {
            previousKey = key
            currentScrollToTop()
        }
    }
}
