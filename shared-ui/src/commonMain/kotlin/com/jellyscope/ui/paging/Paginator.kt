// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.paging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes first-page / next-page loads for a paged list ViewModel.
 *
 * Encapsulates the Mutex + Job + dispatcher launch scaffolding that
 * CollectionViewModel, PersonViewModel and LibraryBrowseViewModel each carried
 * verbatim. The actual page fetch + state reduction stays in the caller via
 * [loadPage]; the caller keeps its own state-shape-specific guards before
 * calling [more].
 *
 * @param loadPage suspending page loader; `reset == true` loads the first page,
 *   `false` appends the next one. It runs under the paginator's lock so only one
 *   load is ever in flight.
 */
class Paginator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val loadPage: suspend (reset: Boolean) -> Unit,
) {
    private val mutex = Mutex()
    private var job: Job? = null

    /** (Re)load the first page, cancelling any in-flight load first. */
    fun first() {
        job?.cancel()
        job =
            scope.launch(dispatcher) {
                mutex.withLock { loadPage(true) }
            }
    }

    /** Append the next page; a no-op while a load is already running. */
    fun more() {
        if (job?.isActive == true) return
        job =
            scope.launch(dispatcher) {
                if (!mutex.tryLock()) {
                    return@launch
                }
                try {
                    loadPage(false)
                } finally {
                    mutex.unlock()
                }
            }
    }
}
