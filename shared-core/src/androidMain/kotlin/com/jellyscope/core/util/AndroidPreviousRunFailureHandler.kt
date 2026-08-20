// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.util

import com.jellyscope.core.data.local.PreviousRunFailurePlatform
import com.jellyscope.core.data.local.PreviousRunFailureStore
import kotlinx.atomicfu.atomic
import java.lang.Thread.UncaughtExceptionHandler

private val processHandlerInstaller = PreviousRunFailureHandlerInstaller()

actual fun installPreviousRunFailureHandler(
    store: PreviousRunFailureStore,
    platform: PreviousRunFailurePlatform,
) {
    processHandlerInstaller.install(
        store = store,
        platform = platform,
        previousHandler = Thread.getDefaultUncaughtExceptionHandler(),
        setDefaultHandler = Thread::setDefaultUncaughtExceptionHandler,
    )
}

internal class PreviousRunFailureHandlerInstaller {
    private val installed = atomic(false)

    fun install(
        store: PreviousRunFailureStore,
        platform: PreviousRunFailurePlatform,
        previousHandler: UncaughtExceptionHandler?,
        setDefaultHandler: (UncaughtExceptionHandler?) -> Unit,
    ): Boolean {
        if (!installed.compareAndSet(false, true)) return false
        val handler = PreviousRunFailureUncaughtExceptionHandler(store, platform, previousHandler)
        setDefaultHandler(handler)
        return true
    }
}

private class PreviousRunFailureUncaughtExceptionHandler(
    private val store: PreviousRunFailureStore,
    private val platform: PreviousRunFailurePlatform,
    private val previousHandler: UncaughtExceptionHandler?,
) : UncaughtExceptionHandler {
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        runCatching { store.write(throwable, platform) }
        if (previousHandler != null && previousHandler !== this) {
            previousHandler.uncaughtException(thread, throwable)
        }
    }
}
