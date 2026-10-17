// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

/**
 * Stable, credential-free identity for one Jellyfin user on one server.
 *
 * Server URLs are connection metadata and may change without changing this
 * identity. The values are validated here so cache and cleanup boundaries
 * cannot be created for an ambiguous account.
 */
data class AccountIdentity(
    val serverId: String,
    val userId: String,
) {
    init {
        require(serverId.isNotBlank()) { "serverId must not be blank." }
        require(userId.isNotBlank()) { "userId must not be blank." }
    }

    /** Canonical internal account key. Credentials and URLs are intentionally excluded. */
    val accountId: String
        get() = "$serverId|$userId"
}

fun Session.accountIdentity(): AccountIdentity =
    AccountIdentity(
        serverId = serverId,
        userId = userId,
    )
