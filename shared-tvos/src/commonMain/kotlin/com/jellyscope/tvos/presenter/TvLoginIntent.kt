// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvLoginIntent {
    data object StartDiscovery : TvLoginIntent

    data object RetryDiscovery : TvLoginIntent

    class SubmitServer(
        val input: String,
    ) : TvLoginIntent

    class SubmitCredentials(
        val username: String,
        val password: String,
    ) : TvLoginIntent

    data object RetryQuickConnect : TvLoginIntent

    data object ResetToServerEntry : TvLoginIntent
}
