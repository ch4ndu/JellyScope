// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import kotlinx.atomicfu.atomic
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

private val processHandlerInstaller = ApplePreviousRunFailureHandlerInstaller()

@OptIn(ExperimentalNativeApi::class)
actual fun installPreviousRunFailureHandler(
    store: PreviousRunFailureStore,
    platform: PreviousRunFailurePlatform,
) {
    processHandlerInstaller.install(store, platform)
}

@OptIn(ExperimentalNativeApi::class)
internal class ApplePreviousRunFailureHandlerInstaller(
    private val installHook: (ReportUnhandledExceptionHook) -> ReportUnhandledExceptionHook? = ::setUnhandledExceptionHook,
    private val terminate: (Throwable) -> Unit = { throwable -> terminateWithUnhandledException(throwable) },
) {
    private val installed = atomic(false)

    fun install(
        store: PreviousRunFailureStore,
        platform: PreviousRunFailurePlatform,
    ): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        var previous: ReportUnhandledExceptionHook? = null
        previous =
            installHook { throwable ->
                runCatching { store.write(throwable, platform) }
                previous?.let { previousHook ->
                    try {
                        previousHook.invoke(throwable)
                    } finally {
                        terminate(throwable)
                    }
                } ?: terminate(throwable)
            }
        return true
    }
}
