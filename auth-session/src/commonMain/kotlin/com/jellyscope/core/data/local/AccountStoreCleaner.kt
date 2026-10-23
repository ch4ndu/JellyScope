// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity

/** Durable cleanup owned by the application composing the session boundary. */
interface AccountStoreCleaner {
    suspend fun clearAll()

    suspend fun clearServer(serverId: String)

    suspend fun clearAccount(accountIdentity: AccountIdentity)
}
