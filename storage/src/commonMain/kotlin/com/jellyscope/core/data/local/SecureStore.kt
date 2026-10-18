// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

interface SecureStore {
    suspend fun read(key: String): String?

    suspend fun write(
        key: String,
        value: String,
    )

    suspend fun remove(key: String)

    suspend fun clear()
}
