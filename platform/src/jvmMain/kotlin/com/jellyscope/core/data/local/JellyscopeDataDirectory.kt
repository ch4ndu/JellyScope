// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import java.io.File

fun jellyscopeDataDirectory(): File = File(System.getProperty("user.home"), ".jellyscope").apply { mkdirs() }
