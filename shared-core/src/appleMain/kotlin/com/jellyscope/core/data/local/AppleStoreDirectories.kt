// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import platform.Foundation.NSSearchPathDirectory

// iOS keeps durable local data where it already lives (moving it would abandon
// existing installs); tvOS guarantees only purgeable storage outside the 500 KB
// UserDefaults budget, so everything larger goes to Caches and every Room store
// is treated as re-fetchable there.

/** Where the Room store database lives: iOS Documents, tvOS Caches. */
internal expect val storeDatabaseSearchPath: NSSearchPathDirectory

/** Where local subtitle assets live: iOS Application Support, tvOS Caches. */
internal expect val subtitleAssetsSearchPath: NSSearchPathDirectory
