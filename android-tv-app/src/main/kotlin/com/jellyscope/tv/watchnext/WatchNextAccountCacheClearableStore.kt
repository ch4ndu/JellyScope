// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.annotation.SuppressLint
import android.content.Context
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.data.local.RefetchableServerCache
import com.jellyscope.core.domain.model.AccountIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Clears only JellyScope-owned Watch Next rows and account-qualified poster files. */
@SuppressLint("RestrictedApi")
class WatchNextAccountCacheClearableStore(
    context: Context,
) : AccountScopedClearableStore,
    RefetchableServerCache {
    private val appContext = context.applicationContext

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        withContext(Dispatchers.IO) {
            WatchNextContract.posterDirectory(appContext, accountIdentity).deleteRecursively()
            deleteOwnedPrograms { payload ->
                payload.serverId == accountIdentity.serverId && payload.userId == accountIdentity.userId
            }
        }
    }

    override suspend fun clearServerScoped(serverId: String) {
        withContext(Dispatchers.IO) {
            WatchNextContract.serverPosterDirectory(appContext, serverId).deleteRecursively()
            deleteOwnedPrograms { payload -> payload.serverId == serverId }
        }
    }

    override suspend fun clearServerScoped() {
        withContext(Dispatchers.IO) {
            WatchNextContract.posterRootDirectory(appContext).deleteRecursively()
            deleteOwnedPrograms { true }
        }
    }

    private fun deleteOwnedPrograms(matches: (WatchNextPayload) -> Boolean) {
        val cursor =
            appContext.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                WatchNextProgram.PROJECTION,
                null,
                null,
                null,
            ) ?: return
        cursor.use {
            while (cursor.moveToNext()) {
                val program = WatchNextProgram.fromCursor(cursor)
                val payload = WatchNextContract.parseAccountPayload(program.internalProviderId)
                val ownedByThisApp = program.packageName == appContext.packageName
                if (ownedByThisApp && (payload == null || matches(payload))) {
                    appContext.contentResolver.delete(
                        TvContractCompat.buildWatchNextProgramUri(program.id),
                        null,
                        null,
                    )
                }
            }
        }
    }
}
