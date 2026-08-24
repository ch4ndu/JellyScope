// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.domain.model.AccountIdentity

/** Opaque authorization for a destructive session removal. */
sealed interface SessionRemovalAuthorization {
    data object None : SessionRemovalAuthorization

    class Confirmed internal constructor(
        internal val participantToken: Any,
    ) : SessionRemovalAuthorization

    companion object {
        internal fun confirmed(participantToken: Any): SessionRemovalAuthorization = Confirmed(participantToken)
    }
}

sealed class SessionRemovalError(
    message: String,
) : Exception(message) {
    data object DownloadRemovalConfirmationRequired :
        SessionRemovalError("Download removal confirmation is required.")

    data object ConfirmationStale : SessionRemovalError("Download removal confirmation is stale.")

    data object ArtifactInUse : SessionRemovalError("A retained artifact is in use.")
}

sealed interface SessionRemovalScope {
    data class Account(
        val accountIdentity: AccountIdentity,
    ) : SessionRemovalScope

    /** Stable account targets captured before credentials are removed. */
    data class FullLogout(
        val accountIdentities: List<AccountIdentity>,
    ) : SessionRemovalScope
}
