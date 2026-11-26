package com.example.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CALL_AUDIO_TAG = "CallAudio"

/**
 * Singleton call ringtone / ringback, earpiece/speaker audio routing, and hardware proximity
 * screen-off controller for UChat voice and video calls.
 */
object CallAudioController {
    private var ringtonePlayer: MediaPlayer? = null
    private var ringbackJob: Job? = null
    private var toneGenerator: ToneGenerator? = null
    private var activeCallIdForTone: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

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
                val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 65)
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

    fun prepareVoiceCallAudio(context: Context, speakerOn: Boolean) {
        runCatching {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            requestCallAudioFocus(am)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applyAudioRoute(am, speakerOn)
        }
    }

    fun setMicrophoneMute(context: Context, muted: Boolean) {
        runCatching {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = muted
        }
    }

    fun setSpeakerphoneOn(context: Context, enabled: Boolean) {
        runCatching {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applyAudioRoute(am, enabled)
        }
    }

    private fun applyAudioRoute(am: AudioManager, speakerOn: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            val targetDevice = if (speakerOn) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                // Prefer connected Bluetooth/wired headset when off speaker, otherwise built-in earpiece
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            }
            if (targetDevice != null) {
                am.setCommunicationDevice(targetDevice)
            } else if (!speakerOn) {
                am.clearCommunicationDevice()
            }
        }
        @Suppress("DEPRECATION")
        runCatching { am.isSpeakerphoneOn = speakerOn }
    }

    private fun requestCallAudioFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
            }
            audioFocusRequest?.let { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    /**
     * Turns the screen off automatically when held near the ear during earpiece calls,
     * and wakes it back up the moment the phone is moved away from the ear.
     */
    @SuppressLint("WakelockTimeout")
    fun setProximityScreenOffEnabled(context: Context, enabled: Boolean) {
        runCatching {
            if (enabled) {
                if (proximityWakeLock?.isHeld == true) return
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
                val wl = proximityWakeLock ?: pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "UChat:CallProximity"
                ).also {
                    it.setReferenceCounted(false)
                    proximityWakeLock = it
                }
                if (!wl.isHeld) {
                    // Timeout after 3 hours max for safety while covering long calls
                    wl.acquire(3 * 60 * 60 * 1000L)
                }
            } else {
                releaseProximityLock()
            }
        }
    }

    private fun releaseProximityLock() {
        runCatching {
            val wl = proximityWakeLock
            if (wl != null && wl.isHeld) {
                wl.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }
        proximityWakeLock = null
    }

    fun resetAudioRouting(context: Context) {
        stopAllTones()
        releaseProximityLock()
        runCatching {
            val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }
}
