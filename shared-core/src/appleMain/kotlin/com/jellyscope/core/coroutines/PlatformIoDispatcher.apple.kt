// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Kotlin/Native does not expose a separate public Dispatchers.IO. Default is
// its supported background dispatcher for database and file work.
actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
