// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

/**
 * English [DetailFormatterStrings] for pure formatter unit tests, mirroring the
 * shipped `strings.xml` values so assertions stay locale-stable without the
 * Compose resource runtime.
 */
internal fun englishDetailFormatterStrings(): DetailFormatterStrings =
    DetailFormatterStrings(
        timeLeftTemplate = "%1\$s left",
        directedByTemplate = "Directed by %1\$s",
        versionTemplate = "Version %1\$d",
        monthAbbreviations =
            listOf(
                "Jan",
                "Feb",
                "Mar",
                "Apr",
                "May",
                "Jun",
                "Jul",
                "Aug",
                "Sep",
                "Oct",
                "Nov",
                "Dec",
            ),
    )
