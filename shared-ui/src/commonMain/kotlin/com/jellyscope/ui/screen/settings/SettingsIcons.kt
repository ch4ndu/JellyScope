// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object SettingsIcons {
    val AboutJellyScope: ImageVector by lazy {
        settingsIcon("settings_about_jellyscope") {
            settingsPath {
                moveTo(4f, 11f)
                arcToRelative(8f, 8f, 0f, false, true, 16f, 0f)
                horizontalLineTo(4f)
                close()
                moveTo(7f, 11f)
                verticalLineToRelative(5f)
                moveTo(10f, 11f)
                verticalLineToRelative(7f)
                moveTo(13f, 11f)
                verticalLineToRelative(6f)
                moveTo(16f, 11f)
                verticalLineToRelative(5f)
                moveTo(7f, 16f)
                curveToRelative(0f, 1f, -1f, 2f, -2f, 2f)
                moveTo(10f, 18f)
                curveToRelative(0f, 1f, -1f, 2f, -2f, 2f)
                moveTo(13f, 17f)
                curveToRelative(0f, 1f, 1f, 2f, 2f, 2f)
            }
            settingsPath {
                moveTo(22.5f, 18f)
                arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 18f)
                arcTo(3.5f, 3.5f, 0f, true, true, 22.5f, 18f)
                close()
            }
            settingsPath {
                moveTo(19f, 17.2f)
                verticalLineToRelative(2.2f)
                moveTo(19f, 15.5f)
                horizontalLineToRelative(0.01f)
            }
        }
    }

    val ActiveAccount: ImageVector by lazy {
        settingsIcon("settings_active_account") {
            settingsPath {
                moveTo(12f, 2.8f)
                lineTo(19f, 6f)
                verticalLineToRelative(5.2f)
                curveToRelative(0f, 4.5f, -2.8f, 7.8f, -7f, 10f)
                curveToRelative(-4.2f, -2.2f, -7f, -5.5f, -7f, -10f)
                verticalLineTo(6f)
                lineToRelative(7f, -3.2f)
                close()
            }
            settingsPath {
                moveToRelative(8.8f, 12f)
                lineToRelative(2.1f, 2.1f)
                lineToRelative(4.5f, -4.5f)
            }
        }
    }

    val AddAccount: ImageVector by lazy {
        settingsIcon("settings_add_account") {
            settingsPath {
                moveTo(12f, 8f)
                arcTo(3f, 3f, 0f, true, true, 6f, 8f)
                arcTo(3f, 3f, 0f, true, true, 12f, 8f)
                close()
            }
            settingsPath {
                moveTo(3.8f, 20f)
                arcToRelative(5.2f, 5.2f, 0f, false, true, 10.4f, 0f)
                moveTo(18f, 6f)
                verticalLineToRelative(6f)
                moveTo(15f, 9f)
                horizontalLineToRelative(6f)
            }
        }
    }

    val AppVersion: ImageVector by lazy {
        settingsIcon("settings_app_version") {
            settingsPath {
                moveTo(4f, 4f)
                horizontalLineToRelative(7f)
                lineToRelative(9f, 9f)
                lineToRelative(-7f, 7f)
                lineToRelative(-9f, -9f)
                verticalLineTo(4f)
                close()
            }
            settingsPath {
                moveTo(9.7f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, true, true, 7.3f, 8.5f)
                arcTo(1.2f, 1.2f, 0f, true, true, 9.7f, 8.5f)
                close()
            }
        }
    }

    val AudioFormats: ImageVector by lazy {
        settingsIcon("settings_audio_formats") {
            settingsPath {
                moveTo(3f, 12f)
                horizontalLineToRelative(1f)
                moveTo(6f, 9f)
                verticalLineToRelative(6f)
                moveTo(9f, 6f)
                verticalLineToRelative(12f)
                moveTo(12f, 3f)
                verticalLineToRelative(18f)
                moveTo(15f, 6f)
                verticalLineToRelative(12f)
                moveTo(18f, 9f)
                verticalLineToRelative(6f)
                moveTo(21f, 12f)
                horizontalLineToRelative(-1f)
            }
        }
    }

    val AudioLanguage: ImageVector by lazy {
        settingsIcon("settings_audio_language") {
            settingsPath {
                moveTo(5f, 4f)
                horizontalLineToRelative(14f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(9f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineToRelative(-7f)
                lineToRelative(-4.5f, 3f)
                verticalLineToRelative(-3f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(6f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
            settingsPath {
                moveTo(7f, 11f)
                verticalLineTo(9f)
                moveTo(10f, 13f)
                verticalLineTo(7f)
                moveTo(13f, 12f)
                verticalLineTo(8f)
                moveTo(16f, 13f)
                verticalLineTo(7f)
                moveTo(19f, 11f)
                verticalLineTo(9f)
            }
        }
    }

    val AudioOutput: ImageVector by lazy {
        settingsIcon("settings_audio_output") {
            settingsPath {
                moveTo(4f, 10f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(4f)
                lineToRelative(5f, 4f)
                verticalLineTo(6f)
                lineToRelative(-5f, 4f)
                horizontalLineTo(4f)
                close()
            }
            settingsPath {
                moveTo(16f, 9f)
                arcToRelative(4f, 4f, 0f, false, true, 0f, 6f)
                moveTo(18.5f, 6.5f)
                arcToRelative(7.5f, 7.5f, 0f, false, true, 0f, 11f)
            }
        }
    }

    val ClearSubtitles: ImageVector by lazy {
        settingsIcon("settings_clear_subtitles") {
            settingsPath {
                moveTo(4f, 7f)
                horizontalLineToRelative(16f)
                moveTo(9f, 7f)
                verticalLineTo(4f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(3f)
                moveTo(6f, 7f)
                lineToRelative(1f, 14f)
                horizontalLineToRelative(10f)
                lineToRelative(1f, -14f)
                moveTo(10f, 11f)
                verticalLineToRelative(6f)
                moveTo(14f, 11f)
                verticalLineToRelative(6f)
            }
        }
    }

    val Commercials: ImageVector by lazy {
        settingsIcon("settings_commercials") {
            settingsPath {
                moveTo(20f, 12f)
                arcTo(8f, 8f, 0f, true, true, 4f, 12f)
                arcTo(8f, 8f, 0f, true, true, 20f, 12f)
                close()
            }
            settingsPath {
                moveToRelative(6.3f, 6.3f)
                lineToRelative(11.4f, 11.4f)
            }
        }
    }

    val Credits: ImageVector by lazy {
        settingsIcon("settings_credits") {
            settingsPath {
                moveTo(5f, 5f)
                verticalLineToRelative(14f)
                moveTo(9f, 6f)
                lineToRelative(9f, 6f)
                lineToRelative(-9f, 6f)
                verticalLineTo(6f)
                close()
            }
        }
    }

    val FocusedCardZoom: ImageVector by lazy {
        settingsIcon("settings_focused_card_zoom") {
            settingsPath {
                moveTo(8f, 4f)
                horizontalLineTo(4f)
                verticalLineToRelative(4f)
                moveTo(16f, 4f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(4f)
                moveTo(4f, 16f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(4f)
                moveTo(20f, 16f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-4f)
            }
            settingsPath {
                moveTo(10f, 9f)
                horizontalLineTo(14f)
                arcTo(1f, 1f, 0f, false, true, 15f, 10f)
                verticalLineTo(14f)
                arcTo(1f, 1f, 0f, false, true, 14f, 15f)
                horizontalLineTo(10f)
                arcTo(1f, 1f, 0f, false, true, 9f, 14f)
                verticalLineTo(10f)
                arcTo(1f, 1f, 0f, false, true, 10f, 9f)
                close()
            }
            settingsPath {
                moveTo(12f, 10.8f)
                verticalLineToRelative(2.4f)
                moveTo(10.8f, 12f)
                horizontalLineToRelative(2.4f)
            }
        }
    }

    val HdrHandling: ImageVector by lazy {
        settingsIcon("settings_hdr_handling") {
            settingsPath {
                moveTo(5f, 5f)
                horizontalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 21f, 7f)
                verticalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 19f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 17f)
                verticalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 5f)
                close()
            }
            settingsPath {
                moveTo(14.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, true, true, 9.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, true, true, 14.5f, 12f)
                close()
            }
            settingsPath {
                moveTo(12f, 7.5f)
                verticalLineTo(6f)
                moveTo(12f, 18f)
                verticalLineToRelative(-1.5f)
                moveTo(7.5f, 12f)
                horizontalLineTo(6f)
                moveTo(18f, 12f)
                horizontalLineToRelative(-1.5f)
                moveTo(8.8f, 8.8f)
                lineTo(7.7f, 7.7f)
                moveTo(16.3f, 16.3f)
                lineToRelative(-1.1f, -1.1f)
                moveTo(15.2f, 8.8f)
                lineToRelative(1.1f, -1.1f)
                moveTo(7.7f, 16.3f)
                lineToRelative(1.1f, -1.1f)
            }
        }
    }

    val Intros: ImageVector by lazy {
        settingsIcon("settings_intros") {
            settingsPath {
                moveToRelative(4f, 6f)
                lineToRelative(7f, 6f)
                lineToRelative(-7f, 6f)
                verticalLineTo(6f)
                close()
                moveTo(11f, 6f)
                lineToRelative(7f, 6f)
                lineToRelative(-7f, 6f)
                verticalLineTo(6f)
                close()
            }
        }
    }

    val LibraryGridHero: ImageVector by lazy {
        settingsIcon("settings_library_grid_hero") {
            settingsPath {
                moveTo(5f, 4f)
                horizontalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 21f, 6f)
                verticalLineTo(18f)
                arcTo(2f, 2f, 0f, false, true, 19f, 20f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 18f)
                verticalLineTo(6f)
                arcTo(2f, 2f, 0f, false, true, 5f, 4f)
                close()
            }
            settingsPath {
                moveTo(3f, 11f)
                horizontalLineToRelative(12f)
                moveTo(9f, 11f)
                verticalLineToRelative(9f)
                moveTo(15f, 4f)
                verticalLineToRelative(16f)
            }
        }
    }

    val MatchRefreshRate: ImageVector by lazy {
        settingsIcon("settings_match_refresh_rate") {
            settingsPath {
                moveTo(18.5f, 8f)
                arcTo(7.5f, 7.5f, 0f, false, false, 5.8f, 6.5f)
                lineTo(4f, 8.5f)
                moveTo(5.5f, 16f)
                arcTo(7.5f, 7.5f, 0f, false, false, 18.2f, 17.5f)
                lineToRelative(1.8f, -2f)
                moveTo(4f, 5f)
                verticalLineToRelative(3.5f)
                horizontalLineToRelative(3.5f)
                moveTo(20f, 19f)
                verticalLineToRelative(-3.5f)
                horizontalLineToRelative(-3.5f)
            }
            settingsPath {
                moveTo(8.5f, 10f)
                verticalLineToRelative(4f)
                moveTo(8.5f, 12f)
                horizontalLineToRelative(3f)
                moveTo(11.5f, 10f)
                verticalLineToRelative(4f)
                moveTo(14f, 10f)
                horizontalLineToRelative(3f)
                lineToRelative(-3f, 4f)
                horizontalLineToRelative(3f)
            }
        }
    }

    val MaxBitrate: ImageVector by lazy {
        settingsIcon("settings_max_bitrate") {
            settingsPath {
                moveTo(4f, 18f)
                arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
                moveTo(7f, 15f)
                lineToRelative(-2f, -1f)
                moveTo(9f, 11f)
                lineTo(7.5f, 8.8f)
                moveTo(12f, 10f)
                verticalLineTo(7f)
                moveTo(15f, 11f)
                lineToRelative(1.5f, -2.2f)
                moveTo(17f, 15f)
                lineToRelative(2f, -1f)
            }
            settingsPath {
                moveToRelative(12f, 17f)
                lineToRelative(4f, -4f)
            }
            settingsPath {
                moveTo(13.3f, 17f)
                arcTo(1.3f, 1.3f, 0f, true, true, 10.7f, 17f)
                arcTo(1.3f, 1.3f, 0f, true, true, 13.3f, 17f)
                close()
            }
        }
    }

    val OpenSubtitlesKey: ImageVector by lazy {
        settingsIcon("settings_opensubtitles_key") {
            settingsPath {
                moveTo(20f, 8.5f)
                arcTo(4.5f, 4.5f, 0f, true, true, 11f, 8.5f)
                arcTo(4.5f, 4.5f, 0f, true, true, 20f, 8.5f)
                close()
            }
            settingsPath {
                moveToRelative(12.3f, 11.7f)
                lineToRelative(-7.8f, 7.8f)
                moveTo(7.2f, 16.8f)
                lineToRelative(2f, 2f)
                moveTo(9.3f, 14.7f)
                lineToRelative(2f, 2f)
            }
        }
    }

    val Previews: ImageVector by lazy {
        settingsIcon("settings_previews") {
            settingsPath {
                moveTo(5f, 5f)
                horizontalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 21f, 7f)
                verticalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 19f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 17f)
                verticalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 5f)
                close()
            }
            settingsPath {
                moveToRelative(9f, 9f)
                lineToRelative(6f, 3f)
                lineToRelative(-6f, 3f)
                verticalLineTo(9f)
                close()
                moveTo(17f, 8f)
                verticalLineToRelative(8f)
            }
        }
    }

    val Recaps: ImageVector by lazy {
        settingsIcon("settings_recaps") {
            settingsPath {
                moveTo(18.5f, 8f)
                arcTo(7.5f, 7.5f, 0f, true, false, 19f, 15f)
                moveTo(18.5f, 8f)
                verticalLineTo(3.8f)
                moveTo(18.5f, 8f)
                horizontalLineToRelative(-4.2f)
            }
        }
    }

    val RefreshCapabilities: ImageVector by lazy {
        settingsIcon("settings_refresh_capabilities") {
            settingsPath {
                moveTo(5f, 4f)
                horizontalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 21f, 6f)
                verticalLineTo(15f)
                arcTo(2f, 2f, 0f, false, true, 19f, 17f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 15f)
                verticalLineTo(6f)
                arcTo(2f, 2f, 0f, false, true, 5f, 4f)
                close()
            }
            settingsPath {
                moveTo(8f, 21f)
                horizontalLineToRelative(8f)
                moveTo(12f, 17f)
                verticalLineToRelative(4f)
                moveTo(15.8f, 9f)
                arcTo(4f, 4f, 0f, false, false, 9f, 7.5f)
                lineTo(8f, 9f)
                moveTo(8.2f, 12f)
                arcToRelative(4f, 4f, 0f, false, false, 6.8f, 1.5f)
                lineToRelative(1f, -1.5f)
                moveTo(8f, 6f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(3f)
                moveTo(16f, 15f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(-3f)
            }
        }
    }

    val RememberLibrary: ImageVector by lazy {
        settingsIcon("settings_remember_library") {
            settingsPath {
                moveTo(4.5f, 8f)
                arcTo(8f, 8f, 0f, true, true, 4f, 15f)
                moveTo(4.5f, 8f)
                verticalLineTo(3.8f)
                moveTo(4.5f, 8f)
                horizontalLineTo(8.7f)
            }
            settingsPath {
                moveTo(12f, 7.5f)
                verticalLineTo(12f)
                lineToRelative(3f, 2f)
            }
        }
    }

    val ResumeBehavior: ImageVector by lazy {
        settingsIcon("settings_resume_behavior") {
            settingsPath {
                moveTo(5.2f, 8.2f)
                arcTo(8f, 8f, 0f, true, true, 4f, 14f)
                moveTo(5.2f, 8.2f)
                verticalLineTo(4f)
                moveTo(5.2f, 8.2f)
                horizontalLineTo(9.4f)
            }
            settingsPath {
                moveToRelative(10f, 9f)
                lineToRelative(5f, 3f)
                lineToRelative(-5f, 3f)
                verticalLineTo(9f)
                close()
            }
        }
    }

    val Server: ImageVector by lazy {
        settingsIcon("settings_server") {
            settingsPath {
                moveTo(6f, 3.5f)
                horizontalLineTo(18f)
                arcTo(2f, 2f, 0f, false, true, 20f, 5.5f)
                verticalLineTo(8.5f)
                arcTo(2f, 2f, 0f, false, true, 18f, 10.5f)
                horizontalLineTo(6f)
                arcTo(2f, 2f, 0f, false, true, 4f, 8.5f)
                verticalLineTo(5.5f)
                arcTo(2f, 2f, 0f, false, true, 6f, 3.5f)
                close()
            }
            settingsPath {
                moveTo(6f, 13.5f)
                horizontalLineTo(18f)
                arcTo(2f, 2f, 0f, false, true, 20f, 15.5f)
                verticalLineTo(18.5f)
                arcTo(2f, 2f, 0f, false, true, 18f, 20.5f)
                horizontalLineTo(6f)
                arcTo(2f, 2f, 0f, false, true, 4f, 18.5f)
                verticalLineTo(15.5f)
                arcTo(2f, 2f, 0f, false, true, 6f, 13.5f)
                close()
            }
            settingsPath {
                moveTo(8f, 7f)
                horizontalLineToRelative(0.01f)
                moveTo(8f, 17f)
                horizontalLineToRelative(0.01f)
                moveTo(12f, 7f)
                horizontalLineToRelative(5f)
                moveTo(12f, 17f)
                horizontalLineToRelative(5f)
            }
        }
    }

    val ServerUrl: ImageVector by lazy {
        settingsIcon("settings_server_url") {
            settingsPath {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, true, true, 3f, 12f)
                arcTo(9f, 9f, 0f, true, true, 21f, 12f)
                close()
            }
            settingsPath {
                moveTo(3f, 12f)
                horizontalLineToRelative(18f)
                moveTo(12f, 3f)
                arcToRelative(14f, 14f, 0f, false, true, 0f, 18f)
                moveTo(12f, 3f)
                arcToRelative(14f, 14f, 0f, false, false, 0f, 18f)
            }
        }
    }

    val SignOut: ImageVector by lazy {
        settingsIcon("settings_sign_out") {
            settingsPath {
                moveTo(10f, 4f)
                horizontalLineTo(5f)
                verticalLineToRelative(16f)
                horizontalLineToRelative(5f)
                moveTo(13f, 8f)
                lineToRelative(4f, 4f)
                lineToRelative(-4f, 4f)
                moveTo(17f, 12f)
                horizontalLineTo(9f)
            }
        }
    }

    val SignedInUser: ImageVector by lazy {
        settingsIcon("settings_signed_in_user") {
            settingsPath {
                moveTo(15.5f, 7.5f)
                arcTo(3.5f, 3.5f, 0f, true, true, 8.5f, 7.5f)
                arcTo(3.5f, 3.5f, 0f, true, true, 15.5f, 7.5f)
                close()
            }
            settingsPath {
                moveTo(5f, 21f)
                arcToRelative(7f, 7f, 0f, false, true, 14f, 0f)
                horizontalLineTo(5f)
                close()
            }
        }
    }

    val SubtitleLanguage: ImageVector by lazy {
        settingsIcon("settings_subtitle_language") {
            settingsPath {
                moveTo(5f, 5f)
                horizontalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 21f, 7f)
                verticalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 19f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 17f)
                verticalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 5f)
                close()
            }
            settingsPath {
                moveTo(6f, 13f)
                horizontalLineToRelative(5f)
                moveTo(13f, 13f)
                horizontalLineToRelative(5f)
                moveTo(6f, 16f)
                horizontalLineToRelative(3f)
                moveTo(11f, 16f)
                horizontalLineToRelative(7f)
            }
        }
    }

    val Theme: ImageVector by lazy {
        settingsIcon("settings_theme") {
            settingsPath {
                moveToRelative(14.5f, 4.2f)
                lineToRelative(5.3f, 5.3f)
                lineToRelative(-8.5f, 8.5f)
                horizontalLineTo(6f)
                verticalLineToRelative(-5.3f)
                lineToRelative(8.5f, -8.5f)
                close()
            }
            settingsPath {
                moveToRelative(12.5f, 6.2f)
                lineToRelative(5.3f, 5.3f)
                moveTo(6f, 18f)
                curveToRelative(0f, 1.7f, -1f, 3f, -3f, 3f)
                curveToRelative(0f, -2f, 1.3f, -3f, 3f, -3f)
                close()
            }
        }
    }

    val TileSize: ImageVector by lazy {
        settingsIcon("settings_tile_size") {
            settingsPath {
                moveTo(5f, 4f)
                horizontalLineTo(9f)
                arcTo(1f, 1f, 0f, false, true, 10f, 5f)
                verticalLineTo(9f)
                arcTo(1f, 1f, 0f, false, true, 9f, 10f)
                horizontalLineTo(5f)
                arcTo(1f, 1f, 0f, false, true, 4f, 9f)
                verticalLineTo(5f)
                arcTo(1f, 1f, 0f, false, true, 5f, 4f)
                close()
            }
            settingsPath {
                moveTo(15f, 4f)
                horizontalLineTo(19f)
                arcTo(1f, 1f, 0f, false, true, 20f, 5f)
                verticalLineTo(9f)
                arcTo(1f, 1f, 0f, false, true, 19f, 10f)
                horizontalLineTo(15f)
                arcTo(1f, 1f, 0f, false, true, 14f, 9f)
                verticalLineTo(5f)
                arcTo(1f, 1f, 0f, false, true, 15f, 4f)
                close()
            }
            settingsPath {
                moveTo(5f, 14f)
                horizontalLineTo(9f)
                arcTo(1f, 1f, 0f, false, true, 10f, 15f)
                verticalLineTo(19f)
                arcTo(1f, 1f, 0f, false, true, 9f, 20f)
                horizontalLineTo(5f)
                arcTo(1f, 1f, 0f, false, true, 4f, 19f)
                verticalLineTo(15f)
                arcTo(1f, 1f, 0f, false, true, 5f, 14f)
                close()
            }
            settingsPath {
                moveTo(15f, 14f)
                horizontalLineTo(19f)
                arcTo(1f, 1f, 0f, false, true, 20f, 15f)
                verticalLineTo(19f)
                arcTo(1f, 1f, 0f, false, true, 19f, 20f)
                horizontalLineTo(15f)
                arcTo(1f, 1f, 0f, false, true, 14f, 19f)
                verticalLineTo(15f)
                arcTo(1f, 1f, 0f, false, true, 15f, 14f)
                close()
            }
        }
    }

    val VideoFormats: ImageVector by lazy {
        settingsIcon("settings_video_formats") {
            settingsPath {
                moveTo(5f, 4f)
                horizontalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 21f, 6f)
                verticalLineTo(18f)
                arcTo(2f, 2f, 0f, false, true, 19f, 20f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 18f)
                verticalLineTo(6f)
                arcTo(2f, 2f, 0f, false, true, 5f, 4f)
                close()
            }
            settingsPath {
                moveTo(7f, 4f)
                verticalLineToRelative(16f)
                moveTo(17f, 4f)
                verticalLineToRelative(16f)
                moveTo(3f, 8f)
                horizontalLineToRelative(4f)
                moveTo(3f, 16f)
                horizontalLineToRelative(4f)
                moveTo(17f, 8f)
                horizontalLineToRelative(4f)
                moveTo(17f, 16f)
                horizontalLineToRelative(4f)
            }
            settingsPath {
                moveToRelative(10f, 9f)
                lineToRelative(5f, 3f)
                lineToRelative(-5f, 3f)
                verticalLineTo(9f)
                close()
            }
        }
    }
}

private fun settingsIcon(
    name: String,
    paths: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(paths)
        .build()

private fun ImageVector.Builder.settingsPath(pathBuilder: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = pathBuilder,
    )
}
