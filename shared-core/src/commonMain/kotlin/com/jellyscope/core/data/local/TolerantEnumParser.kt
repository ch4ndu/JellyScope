// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

/**
 * Decodes a persisted enum name without throwing when the name is unknown.
 * Exact, case-sensitive matching keeps newer stored values safe to read after
 * a downgrade while leaving each caller responsible for its own fallback.
 */
internal inline fun <reified T : Enum<T>> String?.toTolerantEnumOrNull(): T? = enumValues<T>().firstOrNull { entry -> entry.name == this }
