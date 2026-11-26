package com.example.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

private const val WEBRTC_TAG = "WebRtcEngine"

data class WebRtcCallState(
    val iceState: String = "NEW",
    val peerState: String = "NEW",
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val hasLocalCamera: Boolean = false,
    val localCandidatesSent: Int = 0,
    val remoteCandidatesAdded: Int = 0,
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Real peer-to-peer WebRTC audio & video engine with STUN + TURN relay servers
 * (for 4G/5G carrier NAT traversal), hardware-accelerated JavaAudioDeviceModule,
 * thread-safe ICE buffering, and Unified Plan media transceivers.
 */
class WebRtcCallEngine(
    context: Context,
    private val isVideoCall: Boolean,
    private val canCaptureCamera: Boolean,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onIceConnected: () -> Unit,
    private val onIceDisconnectedOrFailed: (String) -> Unit
) {
    companion object {
        private val factoryInitialized = AtomicBoolean(false)

        // Public STUN + OpenRelay TURN (UDP, TCP, port 80 & TLS 443) for reliable mobile 4G/5G/Wi-Fi calling
        val ICE_SERVERS: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turns:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    val eglBase: EglBase = EglBase.create()

    private val _state = MutableStateFlow(WebRtcCallState())
    val state: StateFlow<WebRtcCallState> = _state.asStateFlow()

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val iceLock = Any()
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val addedCandidateKeys = mutableSetOf<String>()
    private val connectedNotified = AtomicBoolean(false)
    @Volatile
    private var released = false

    init {
        initializeEngine()
    }

    private fun postMain(block: () -> Unit) {
        if (released) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post {
                if (!released) block()
            }
        }
    }

    private fun notifyConnectedOnce() {
        _state.value = _state.value.copy(isConnected = true, errorMessage = null)
        if (connectedNotified.compareAndSet(false, true)) {
            postMain { onIceConnected() }
        }
    }

    private fun initializeEngine() {
        try {
            if (factoryInitialized.compareAndSet(false, true)) {
                val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initOptions)
            }

            val adm = JavaAudioDeviceModule.builder(appContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
                .setUseHardwareAcousticEchoCanceler(
                    JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
                )
                .setUseHardwareNoiseSuppressor(
                    JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
                )
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioRecord init error: $errorMessage")
                    }
                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.e(WEBRTC_TAG, "AudioRecord start error ($errorCode): $errorMessage")
                    }
                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioRecord error: $errorMessage")
                    }
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioTrack init error: $errorMessage")
                    }
                    override fun onWebRtcAudioTrackStartError(
                        errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.e(WEBRTC_TAG, "AudioTrack start error ($errorCode): $errorMessage")
                    }
                    override fun onWebRtcAudioTrackError(errorMessage: String?) {
                        Log.e(WEBRTC_TAG, "AudioTrack error: $errorMessage")
                    }
                })
                .createAudioDeviceModule()
            audioDeviceModule = adm

            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            val factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            peerConnectionFactory = factory

            val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                iceCandidatePoolSize = 4
            }

            peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (released) return
                    _state.value = _state.value.copy(
                        localCandidatesSent = _state.value.localCandidatesSent + 1
                    )
                    postMain { onLocalIceCandidate(candidate) }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    if (released) return
                    Log.d(WEBRTC_TAG, "ICE state -> $newState")
                    _state.value = _state.value.copy(iceState = newState.name)
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> notifyConnectedOnce()
                        PeerConnection.IceConnectionState.FAILED ->
                            postMain { onIceDisconnectedOrFailed("Reconnecting network...") }
                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            postMain { onIceDisconnectedOrFailed("Connection interrupted...") }
                        else -> Unit
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    if (released) return
                    Log.d(WEBRTC_TAG, "PeerConnection state -> $newState")
                    _state.value = _state.value.copy(peerState = newState.name)
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> notifyConnectedOnce()
                        PeerConnection.PeerConnectionState.FAILED ->
                            postMain { onIceDisconnectedOrFailed("Reconnecting network...") }
                        else -> Unit
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    if (released) return
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        track.setEnabled(true)
                        _state.value = _state.value.copy(remoteVideoTrack = track)
                    } else if (track is AudioTrack) {
                        track.setEnabled(true)
                        track.setVolume(1.0)
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    if (released) return
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        track.setEnabled(true)
                        _state.value = _state.value.copy(remoteVideoTrack = track)
                    } else if (track is AudioTrack) {
                        track.setEnabled(true)
                        track.setVolume(1.0)
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) {
                    if (released) return
                    stream?.audioTracks?.forEach {
                        it.setEnabled(true)
                        it.setVolume(1.0)
                    }
                    val remoteVid = stream?.videoTracks?.firstOrNull()
                    if (remoteVid != null) {
                        remoteVid.setEnabled(true)
                        _state.value = _state.value.copy(remoteVideoTrack = remoteVid)
                    }
                }
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(channel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
            })

            setupLocalMedia(factory)
        } catch (e: Exception) {
            Log.e(WEBRTC_TAG, "WebRTC initialization error", e)
            _state.value = _state.value.copy(errorMessage = e.localizedMessage ?: "WebRTC init failed")
        }
    }

    private fun setupLocalMedia(factory: PeerConnectionFactory) {
        val pc = peerConnection ?: return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val aSource = factory.createAudioSource(audioConstraints)
        val aTrack = factory.createAudioTrack("uchat_audio_0", aSource).apply {
            setEnabled(true)
            setVolume(1.0)
        }
        audioSource = aSource
        localAudioTrack = aTrack
        pc.addTrack(aTrack, listOf("uchat_stream"))

        if (isVideoCall) {
            val started = if (canCaptureCamera) startLocalVideo(factory, pc) else false
            if (!started) {
                // Still receive remote peer's video even if local camera is unavailable
                runCatching {
                    pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                    )
                }
            }
        }
    }

    private fun startLocalVideo(factory: PeerConnectionFactory, pc: PeerConnection): Boolean {
        return try {
            val enumerator = if (Camera2Enumerator.isSupported(appContext)) {
                Camera2Enumerator(appContext)
            } else {
                Camera1Enumerator(true)
            }
            val devices = enumerator.deviceNames
            val chosenDevice = devices.firstOrNull { enumerator.isFrontFacing(it) }
                ?: devices.firstOrNull()
            if (chosenDevice == null) {
                Log.w(WEBRTC_TAG, "No camera found on device; proceeding with receive-only video.")
                return false
            }
            val capturer = enumerator.createCapturer(chosenDevice, null) ?: return false
            val textureHelper = SurfaceTextureHelper.create("UChatCaptureThread", eglBase.eglBaseContext)
            val vSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(textureHelper, appContext, vSource.capturerObserver)
            try {
                capturer.startCapture(720, 1280, 30)
            } catch (_: Exception) {
                capturer.startCapture(640, 480, 24)
            }

            val vTrack = factory.createVideoTrack("uchat_video_0", vSource).apply { setEnabled(true) }
            videoCapturer = capturer
            surfaceTextureHelper = textureHelper
            videoSource = vSource
            localVideoTrack = vTrack
            pc.addTrack(vTrack, listOf("uchat_stream"))

            _state.value = _state.value.copy(
                localVideoTrack = vTrack,
                hasLocalCamera = true
            )
            true
        } catch (e: Exception) {
            Log.w(WEBRTC_TAG, "Camera capture unavailable: ${e.message}")
            _state.value = _state.value.copy(hasLocalCamera = false)
            false
        }
    }

    fun createOffer(iceRestart: Boolean = false, onOfferCreated: (String) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        pc.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (released || desc == null) return
                pc.setLocalDescription(object : SimpleSdpObserver("setLocalOffer") {
                    override fun onSetSuccess() {
                        if (!released) postMain { onOfferCreated(desc.description) }
                    }
                }, desc)
            }
        }, constraints)
    }

    fun handleRemoteOfferAndCreateAnswer(
        remoteSdpOffer: String,
        onAnswerCreated: (String) -> Unit
    ) {
        val pc = peerConnection ?: return
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, remoteSdpOffer)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteOffer") {
            override fun onSetSuccess() {
                if (released) return
                drainPendingRemoteCandidates()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
                }
                pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (released || answerDesc == null) return
                        pc.setLocalDescription(object : SimpleSdpObserver("setLocalAnswer") {
                            override fun onSetSuccess() {
                                if (!released) postMain { onAnswerCreated(answerDesc.description) }
                            }
                        }, answerDesc)
                    }
                }, constraints)
            }
        }, offerDesc)
    }

    fun setRemoteAnswer(remoteSdpAnswer: String) {
        if (released) return
        val pc = peerConnection ?: return
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, remoteSdpAnswer)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteAnswer") {
            override fun onSetSuccess() {
                if (released) return
                drainPendingRemoteCandidates()
            }
        }, answerDesc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        if (released || sdp.isBlank()) return
        val key = "$sdpMid|$sdpMLineIndex|$sdp"
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        val shouldAddNow = synchronized(iceLock) {
            if (!addedCandidateKeys.add(key)) return
            if (!remoteDescriptionSet) {
                pendingRemoteCandidates.add(candidate)
                false
            } else {
                true
            }
        }
        if (shouldAddNow) {
            runCatching { peerConnection?.addIceCandidate(candidate) }
            _state.value = _state.value.copy(
                remoteCandidatesAdded = _state.value.remoteCandidatesAdded + 1
            )
        }
    }

    private fun drainPendingRemoteCandidates() {
        val pc = peerConnection ?: return
        val toAdd = synchronized(iceLock) {
            remoteDescriptionSet = true
            val snapshot = pendingRemoteCandidates.toList()
            pendingRemoteCandidates.clear()
            snapshot
        }
        var added = 0
        for (c in toAdd) {
            runCatching { pc.addIceCandidate(c) }
            added++
        }
        if (added > 0) {
            _state.value = _state.value.copy(
                remoteCandidatesAdded = _state.value.remoteCandidatesAdded + added
            )
        }
    }

    fun setMicMuted(muted: Boolean) {
        runCatching { localAudioTrack?.setEnabled(!muted) }
        runCatching { audioDeviceModule?.setMicrophoneMute(muted) }
    }

    fun setCameraEnabled(enabled: Boolean) {
        runCatching { localVideoTrack?.setEnabled(enabled) }
    }

    fun switchCamera() {
        runCatching {
            videoCapturer?.switchCamera(null)
        }
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        _state.value = _state.value.copy(localVideoTrack = null, remoteVideoTrack = null)
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null
        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
        runCatching { eglBase.release() }
    }

    private open class SimpleSdpObserver(private val label: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e(WEBRTC_TAG, "$label createFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(WEBRTC_TAG, "$label setFailure: $error")
        }
    }
}
