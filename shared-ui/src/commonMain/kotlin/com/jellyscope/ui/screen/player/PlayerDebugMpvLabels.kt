// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.player_debug_codec_description
import com.jellyscope.ui.generated.resources.player_debug_decoded_size
import com.jellyscope.ui.generated.resources.player_debug_decoding
import com.jellyscope.ui.generated.resources.player_debug_hardware
import com.jellyscope.ui.generated.resources.player_debug_hardware_copy_back
import com.jellyscope.ui.generated.resources.player_debug_software
import com.jellyscope.ui.generated.resources.player_debug_unknown
import org.jetbrains.compose.resources.stringResource

data class PlayerDebugMpvLabels(
    val codecDescription: String,
    val decoding: String,
    val decodedSize: String,
    val hardware: String,
    val hardwareCopyBack: String,
    val software: String,
    val unknown: String,
)

@Composable
fun playerDebugMpvLabels(): PlayerDebugMpvLabels =
    PlayerDebugMpvLabels(
        codecDescription = stringResource(Res.string.player_debug_codec_description),
        decoding = stringResource(Res.string.player_debug_decoding),
        decodedSize = stringResource(Res.string.player_debug_decoded_size),
        hardware = stringResource(Res.string.player_debug_hardware),
        hardwareCopyBack = stringResource(Res.string.player_debug_hardware_copy_back),
        software = stringResource(Res.string.player_debug_software),
        unknown = stringResource(Res.string.player_debug_unknown),
    )
