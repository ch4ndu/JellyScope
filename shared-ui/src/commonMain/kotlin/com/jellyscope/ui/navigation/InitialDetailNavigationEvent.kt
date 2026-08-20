// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import com.jellyscope.core.domain.model.AccountIdentity

/** A consumable detail target whose identity is distinct from its item ID. */
data class InitialDetailNavigationEvent(
    val eventId: Long,
    val itemId: String,
    val accountIdentity: AccountIdentity? = null,
    val boundaryEpoch: Long? = null,
) {
    fun isEligibleFor(
        accountIdentity: AccountIdentity,
        boundaryEpoch: Long,
    ): Boolean =
        this.accountIdentity == null ||
            (this.accountIdentity == accountIdentity && this.boundaryEpoch == boundaryEpoch)
}
