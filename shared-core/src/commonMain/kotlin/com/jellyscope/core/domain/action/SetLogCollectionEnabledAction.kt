// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.LogCollectionPreferenceStore
import com.jellyscope.core.data.local.NoOpPreviousRunFailureStore
import com.jellyscope.core.data.local.PreviousRunFailureStore
import com.jellyscope.core.domain.platform.NativeDiagnosticLogSource
import com.jellyscope.core.util.LogBufferStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SetLogCollectionEnabledAction(
    private val preferenceStore: LogCollectionPreferenceStore,
    private val logBufferStore: LogBufferStore,
    private val nativeDiagnosticLogSource: NativeDiagnosticLogSource = NativeDiagnosticLogSource.None,
    private val previousRunFailureStore: PreviousRunFailureStore = NoOpPreviousRunFailureStore,
) {
    private val mutex = Mutex()

    suspend operator fun invoke(enabled: Boolean) {
        mutex.withLock {
            if (enabled && !preferenceStore.enabled.value) {
                // A prior disabled-state purge may have failed after the durable false was
                // published. Retry that exact diagnostic purge before allowing collection to
                // become enabled again, so re-enable never hydrates stale rows.
                logBufferStore.purgeForDisabledCollection()
            }
            preferenceStore.setEnabled(enabled)
            if (!enabled) {
                var firstFailure: Throwable? = null

                fun rememberFailure(failure: Throwable) {
                    if (firstFailure == null) {
                        firstFailure = failure
                    }
                }

                try {
                    logBufferStore.purgeForDisabledCollection()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    rememberFailure(exception)
                }
                try {
                    nativeDiagnosticLogSource.clear()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    rememberFailure(exception)
                }
                try {
                    previousRunFailureStore.clear()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    rememberFailure(exception)
                }
                firstFailure?.let { failure -> throw failure }
            }
        }
    }
}
