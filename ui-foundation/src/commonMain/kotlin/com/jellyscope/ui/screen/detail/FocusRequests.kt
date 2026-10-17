// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.ui.focus.FocusRequester

/**
 * `FocusRequester.requestFocus()` returns `false` when focus did NOT move and only
 * THROWS when the requester is unattached to any node. The long-standing
 * `runCatching { requestFocus() }.isSuccess` idiom is therefore wrong: it reports
 * success whenever the node merely exists, even if focus never landed. This returns
 * the REAL result — did focus actually move here?
 */
fun FocusRequester.requestFocusSafely(): Boolean = runCatching { requestFocus() }.getOrDefault(false)
