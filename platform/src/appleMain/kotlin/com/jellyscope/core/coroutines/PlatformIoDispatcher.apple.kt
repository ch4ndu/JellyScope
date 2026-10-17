// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// JellyScope uses Default for Apple database and file work. Keep this app
// policy independent of the coroutine library's available dispatcher APIs.
actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
