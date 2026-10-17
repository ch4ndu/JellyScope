// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Fire-once auto-advance countdown keyed by the current item generation. */
@Composable
fun rememberAutoplayCountdownMillis(
    policy: AutoplayPolicySnapshot,
    countdownStarted: Boolean,
    active: Boolean,
    tickMs: Long,
    onAutoAdvance: () -> Unit,
): Long? {
    var remainingMs by remember(policy.countdownKey) {
        mutableLongStateOf(policy.delayMs.coerceAtLeast(0L))
    }
    LaunchedEffect(policy.countdownKey, active, countdownStarted) {
        if (!active) return@LaunchedEffect
        val delayMs = policy.countdownDelayMs(countdownStarted) ?: return@LaunchedEffect
        remainingMs = delayMs
        if (delayMs == 0L) {
            onAutoAdvance()
            return@LaunchedEffect
        }
        while (remainingMs > 0L) {
            delay(tickMs)
            remainingMs = (remainingMs - tickMs).coerceAtLeast(0L)
        }
        onAutoAdvance()
    }
    return remainingMs.takeIf { active }
}
