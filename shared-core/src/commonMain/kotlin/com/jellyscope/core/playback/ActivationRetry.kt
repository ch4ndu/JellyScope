// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

internal const val MAX_ACTIVATION_ATTEMPTS = 10

internal fun nextActivationAttempt(attempt: Int): Int? = (attempt + 1).takeIf { nextAttempt -> nextAttempt <= MAX_ACTIVATION_ATTEMPTS }
