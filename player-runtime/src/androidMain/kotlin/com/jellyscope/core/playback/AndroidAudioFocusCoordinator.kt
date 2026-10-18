// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

enum class AndroidAudioFocusEvent {
    Gained,
    Duck,
    TransientLoss,
    PermanentLoss,
    BecomingNoisy,
}

/** Owns the single app-wide audio-focus/noisy registration for Android players. */
class AndroidAudioFocusCoordinator(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val audioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    private var activeGeneration = 0L
    private var callback: ((Long, AndroidAudioFocusEvent) -> Unit)? = null
    private var focusRequest: AudioFocusRequest? = null
    private var receiverRegistered = false

    private val focusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            val event = mapAndroidAudioFocusChange(change) ?: return@OnAudioFocusChangeListener
            dispatch(event)
        }

    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    dispatch(AndroidAudioFocusEvent.BecomingNoisy)
                }
            }
        }

    @Synchronized
    fun request(
        generation: Long,
        onEvent: (Long, AndroidAudioFocusEvent) -> Unit,
    ): Boolean {
        abandon()
        activeGeneration = generation
        callback = onEvent
        val manager =
            audioManager ?: run {
                activeGeneration = 0L
                callback = null
                return false
            }
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request =
                    AudioFocusRequest
                        .Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(audioAttributes)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(focusChangeListener)
                        .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            callback = null
            focusRequest = null
            return false
        }
        registerNoisyReceiver()
        return true
    }

    @Synchronized
    fun abandon(generation: Long? = null) {
        if (generation != null && generation != activeGeneration) return
        val manager = audioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { request -> manager?.abandonAudioFocusRequest(request) }
        } else if (manager != null) {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusChangeListener)
        }
        unregisterNoisyReceiver()
        focusRequest = null
        callback = null
        activeGeneration = 0L
    }

    private fun dispatch(event: AndroidAudioFocusEvent) {
        val generation = activeGeneration
        callback?.invoke(generation, event)
    }

    private fun registerNoisyReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(noisyReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(noisyReceiver) }
        receiverRegistered = false
    }
}

internal fun mapAndroidAudioFocusChange(change: Int): AndroidAudioFocusEvent? =
    when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> AndroidAudioFocusEvent.Gained
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AndroidAudioFocusEvent.Duck
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AndroidAudioFocusEvent.TransientLoss
        AudioManager.AUDIOFOCUS_LOSS -> AndroidAudioFocusEvent.PermanentLoss
        else -> null
    }
