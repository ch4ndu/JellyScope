// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
