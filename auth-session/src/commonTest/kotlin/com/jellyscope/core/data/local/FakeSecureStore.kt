// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

class FakeSecureStore : SecureStore {
    private val values = mutableMapOf<String, String>()
    var failOnRemoveKey: String? = null
    var failOnWriteKey: String? = null

    override suspend fun read(key: String): String? = values[key]

    override suspend fun write(
        key: String,
        value: String,
    ) {
        if (key == failOnWriteKey) {
            throw IllegalStateException("write failed")
        }
        values[key] = value
    }

    override suspend fun remove(key: String) {
        if (key == failOnRemoveKey) {
            throw IllegalStateException("remove failed")
        }
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }
}
