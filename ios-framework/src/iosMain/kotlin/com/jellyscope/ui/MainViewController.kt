// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui

import platform.UIKit.UIViewController

@Suppress("ktlint:standard:function-naming")
fun MainViewController(onPlayerFullscreenChanged: (Boolean) -> Unit): UIViewController =
    createIosAppViewController(onPlayerFullscreenChanged)
