// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.di

/**
 * Platform-provided core configuration. HTTP logging must stay off in release
 * builds; when on, the Authorization header is sanitized before logging.
 */
data class CoreConfig(
    val enableHttpLogging: Boolean,
)
