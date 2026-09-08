// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.Session

data class TvItemDetailRequest(
    val session: Session,
    val itemId: String,
    val initialSeasonId: String? = null,
)
