package com.example.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CALL_AUDIO_TAG = "CallAudio"

/**
 * Singleton call ringtone / ringback and audio-routing controller.
 * Prevents duplicate ringtone instances, loops while ringing, and halts immediately
 * when calls transition to connected, rejected, cancelled, or ended.
 */
object CallAudioController {
    private var ringtonePlayer: MediaPlayer? = null
    private var ringbackJob: Job? = null
    private var toneGenerator: ToneGenerator? = null
    private var activeCallIdForTone: String? = null

    fun startIncomingRingtone(context: Context, callId: String, customRingtoneUri: String?) {
        if (activeCallIdForTone == callId && ringtonePlayer?.isPlaying == true) return
        stopAllTones()
        activeCallIdForTone = callId
        try {
            val uri: Uri = customRingtoneUri
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context.applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
            ringtonePlayer = player
        } catch (e: Exception) {
            Log.w(CALL_AUDIO_TAG, "Custom/system ringtone fallback: ${e.message}")
            stopAllTones()
        }
    }

    fun startOutgoingRingback(callId: String, scope: CoroutineScope) {
        if (activeCallIdForTone == callId && ringbackJob?.isActive == true) return
        stopAllTones()
        activeCallIdForTone = callId
        ringbackJob = scope.launch {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                toneGenerator = tg
                while (true) {
                    tg.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1400)
                    delay(3400L)
                }
            } catch (e: Exception) {
                Log.w(CALL_AUDIO_TAG, "Ringback tone stopped: ${e.message}")
            }
        }
    }

    fun previewRingtoneOnce(context: Context, customRingtoneUri: String?, scope: CoroutineScope) {
        stopAllTones()
        startIncomingRingtone(context, "preview", customRingtoneUri)
        scope.launch {
            delay(3000L)
            if (activeCallIdForTone == "preview") stopAllTones()
        }
    }

    fun stopAllTones() {
        activeCallIdForTone = null
        ringbackJob?.cancel()
        ringbackJob = null
        runCatching {
            toneGenerator?.stopTone()
            toneGenerator?.release()
        }
        toneGenerator = null
        runCatching {
            ringtonePlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        ringtonePlayer = null
    }

    fun setMicrophoneMute(context: Context, muted: Boolean) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = muted
        }
    }

    fun setSpeakerphoneOn(context: Context, enabled: Boolean) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) am.setCommunicationDevice(speaker)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = enabled
            }
        }
    }

    fun resetAudioRouting(context: Context) {
        stopAllTones()
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
    }
}
