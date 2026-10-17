// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import kotlin.coroutines.cancellation.CancellationException

// runCatching that does NOT swallow coroutine cancellation: capturing a
// CancellationException into a Result turns routine scope teardown into a
// bogus failure (spurious warnings, planner fallbacks, user-visible errors).
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
