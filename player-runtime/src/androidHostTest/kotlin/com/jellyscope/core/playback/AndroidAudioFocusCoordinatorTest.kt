// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAudioFocusCoordinatorTest {
    @Test
    fun mapsGainDuckTransientAndPermanentLossWithoutInventingUnknownEvents() {
        assertEquals(AndroidAudioFocusEvent.Gained, mapAndroidAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(
            AndroidAudioFocusEvent.Duck,
            mapAndroidAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        assertEquals(
            AndroidAudioFocusEvent.TransientLoss,
            mapAndroidAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(AndroidAudioFocusEvent.PermanentLoss, mapAndroidAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS))
        assertNull(mapAndroidAudioFocusChange(Int.MIN_VALUE))
    }
}
