package com.example.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.min

private const val VOICE_TAG = "VoiceMedia"

data class VoiceRecordingState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val previewReady: Boolean = false,
    val durationSec: Int = 0,
    val waveform: List<Int> = emptyList(),
    val previewFilePath: String? = null
)

data class VoicePlaybackState(
    val activeId: String? = null,
    val playing: Boolean = false,
    val progress: Float = 0f,
    val currentSec: Int = 0,
    val totalSec: Int = 0,
    val speed: Float = 1.0f
)

class VoiceRecorderController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var tickerJob: Job? = null
    private var elapsedMillis: Long = 0L

    private val _state = MutableStateFlow(VoiceRecordingState())
    val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

    fun startRecording(): Boolean {
        cancelRecording()
        return try {
            val file = File(context.cacheDir, "uchat_voice_${System.currentTimeMillis()}.m4a")
            outputFile = file
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(22_050)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mr
            elapsedMillis = 0L
            _state.value = VoiceRecordingState(
                recording = true,
                paused = false,
                previewReady = false,
                durationSec = 0,
                waveform = emptyList(),
                previewFilePath = file.absolutePath
            )
            startTicker()
            true
        } catch (e: Exception) {
            Log.e(VOICE_TAG, "Failed to start recording", e)
            cancelRecording()
            false
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (_state.value.recording) {
                delay(200L)
                if (!_state.value.paused) {
                    elapsedMillis += 200L
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = ((amp / 32767f) * 100f).toInt().coerceIn(12, 100)
                    val updatedBars = (_state.value.waveform + normalized).takeLast(48)
                    val secs = (elapsedMillis / 1000L).toInt()
                    _state.value = _state.value.copy(
                        durationSec = secs,
                        waveform = updatedBars
                    )
                    // Cap at 90s for responsive Supabase Storage uploads
                    if (secs >= 90) {
                        stopForPreview()
                        break
                    }
                }
            }
        }
    }

    fun togglePauseResume() {
        val current = _state.value
        if (!current.recording) return
        try {
            if (current.paused) {
                recorder?.resume()
                _state.value = current.copy(paused = false)
            } else {
                recorder?.pause()
                _state.value = current.copy(paused = true)
            }
        } catch (e: Exception) {
            Log.w(VOICE_TAG, "Pause/resume failed: ${e.message}")
        }
    }

    fun stopForPreview() {
        tickerJob?.cancel()
        tickerJob = null
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(VOICE_TAG, "Stop recorder warning: ${e.message}")
        } finally {
            recorder = null
        }
        val file = outputFile
        if (file != null && file.exists() && file.length() > 0L) {
            val current = _state.value
            val bars = if (current.waveform.isEmpty()) List(16) { 28 } else current.waveform
            _state.value = current.copy(
                recording = false,
                paused = false,
                previewReady = true,
                durationSec = current.durationSec.coerceAtLeast(1),
                waveform = bars,
                previewFilePath = file.absolutePath
            )
        } else {
            cancelRecording()
        }
    }

    fun finalizeForSend(): Triple<String, Int, List<Int>>? {
        if (_state.value.recording) {
            stopForPreview()
        }
        val file = outputFile ?: return null
        val snap = _state.value
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val duration = snap.durationSec.coerceAtLeast(1)
        val bars = snap.waveform.ifEmpty { List(20) { 35 } }.take(48)
        clearStateOnly()
        return if (base64.length <= 2_500_000) Triple(base64, duration, bars) else null
    }

    fun cancelRecording() {
        tickerJob?.cancel()
        tickerJob = null
        try {
            recorder?.apply {
                runCatching { stop() }
                release()
            }
        } catch (_: Exception) {
        } finally {
            recorder = null
        }
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
        _state.value = VoiceRecordingState()
    }

    private fun clearStateOnly() {
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
        _state.value = VoiceRecordingState()
    }

    fun release() {
        cancelRecording()
    }
}

class VoicePlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var tempFile: File? = null

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    fun togglePlayBase64(messageId: String, base64Audio: String?, fallbackDurationSec: Int) {
        val current = _state.value
        if (current.activeId == messageId && mediaPlayer != null) {
            if (current.playing) pause() else resume()
            return
        }
        if (base64Audio.isNullOrBlank()) return
        stop()
        if (base64Audio.startsWith("https://") || base64Audio.startsWith("http://")) {
            scope.launch {
                try {
                    val file = File(context.cacheDir, "uchat_play_${messageId.hashCode()}.m4a")
                    if (!file.exists() || file.length() == 0L) {
                        val bytes = withContext(Dispatchers.IO) {
                            URL(base64Audio).openStream().use { it.readBytes() }
                        }
                        file.writeBytes(bytes)
                    }
                    tempFile = file
                    startFromFile(messageId, file.absolutePath, fallbackDurationSec)
                } catch (e: Exception) {
                    Log.e(VOICE_TAG, "Failed to stream voice note from Supabase Storage", e)
                    stop()
                }
            }
            return
        }
        try {
            val bytes = Base64.decode(base64Audio, Base64.DEFAULT)
            val file = File(context.cacheDir, "uchat_play_${messageId.hashCode()}.m4a")
            file.writeBytes(bytes)
            tempFile = file
            startFromFile(messageId, file.absolutePath, fallbackDurationSec)
        } catch (e: Exception) {
            Log.e(VOICE_TAG, "Failed to decode voice note", e)
            stop()
        }
    }

    fun togglePlayFile(id: String, filePath: String, fallbackDurationSec: Int) {
        val current = _state.value
        if (current.activeId == id && mediaPlayer != null) {
            if (current.playing) pause() else resume()
            return
        }
        stop()
        startFromFile(id, filePath, fallbackDurationSec)
    }

    private fun startFromFile(id: String, path: String, fallbackDurationSec: Int) {
        try {
            val speed = _state.value.speed
            val mp = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                runCatching { playbackParams = playbackParams.setSpeed(speed) }
                setOnCompletionListener {
                    stop()
                }
                start()
            }
            mediaPlayer = mp
            val totalSec = ((mp.duration / 1000).takeIf { it > 0 } ?: fallbackDurationSec.coerceAtLeast(1))
            _state.value = VoicePlaybackState(
                activeId = id,
                playing = true,
                progress = 0f,
                currentSec = 0,
                totalSec = totalSec,
                speed = speed
            )
            startProgressTicker()
        } catch (e: Exception) {
            Log.e(VOICE_TAG, "Playback error", e)
            stop()
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (mediaPlayer != null && _state.value.playing) {
                val mp = mediaPlayer ?: break
                val dur = mp.duration.coerceAtLeast(1)
                val pos = mp.currentPosition.coerceIn(0, dur)
                _state.value = _state.value.copy(
                    progress = min(1f, pos.toFloat() / dur.toFloat()),
                    currentSec = pos / 1000,
                    totalSec = (dur / 1000).coerceAtLeast(1)
                )
                delay(120L)
            }
        }
    }

    fun pause() {
        runCatching { mediaPlayer?.pause() }
        progressJob?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    fun resume() {
        runCatching { mediaPlayer?.start() }
        _state.value = _state.value.copy(playing = true)
        startProgressTicker()
    }

    fun cycleSpeed() {
        val nextSpeed = when (_state.value.speed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        _state.value = _state.value.copy(speed = nextSpeed)
        runCatching {
            mediaPlayer?.let { mp ->
                mp.playbackParams = mp.playbackParams.setSpeed(nextSpeed)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
        tempFile?.let { runCatching { it.delete() } }
        tempFile = null
        _state.value = VoicePlaybackState(speed = _state.value.speed)
    }
}
