// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Dispatcher pair for tvOS presenters: [main] hosts state collection and Swift
 * callbacks; [work] hosts CPU-heavy planning/parsing, mirroring the shared
 * ViewModels' injected work dispatcher.
 */
class TvosDispatchers(
    val main: CoroutineDispatcher,
    val work: CoroutineDispatcher,
)

/**
 * Base for tvOS presenters: plain Kotlin state holders driven by
 * UseCases/Actions, observed from Swift via typed watch functions. Owners must
 * call [close] when done; the scope dies with it.
 */
abstract class TvPresenter internal constructor(
    dispatchers: TvosDispatchers,
) {
    protected val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    protected val workDispatcher: CoroutineDispatcher = dispatchers.work

    open fun close() {
        scope.cancel()
    }
}
