// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

/** Queue-facing routes for reporters with a durable local settlement and optional remote side effect. */
internal interface SplitPlaybackProgressReporter {
    val localSettlementReporter: PlaybackProgressReporter
    val guardedRemoteReporter: PlaybackProgressReporter?
}
