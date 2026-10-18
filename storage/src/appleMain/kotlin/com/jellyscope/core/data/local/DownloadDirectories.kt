// SPDX-License-Identifier: MPL-2.0
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.jellyscope.core.data.local

import platform.Foundation.NSURL

internal expect fun appleDownloadsRootDirectory(): NSURL
