// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher

expect fun platformIoDispatcher(): CoroutineDispatcher
