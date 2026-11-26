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
import org.webrtc.CameraEnumerator
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val WEBRTC_TAG = "WebRtcEngine"
private const val CAPTURE_WIDTH = 640
private const val CAPTURE_HEIGHT = 480
private const val CAPTURE_FPS = 24

data class WebRtcCallState(
    val iceState: String = "NEW",
    val peerState: String = "NEW",
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val hasLocalCamera: Boolean = false,
    val isFrontCamera: Boolean = true,
    val isCameraEnabled: Boolean = true,
    val isSwitchingCamera: Boolean = false,
    val localCandidatesSent: Int = 0,
    val remoteCandidatesAdded: Int = 0,
    val isConnected: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Real peer-to-peer WebRTC audio & video engine with STUN + TURN relay servers,
 * VP8-preferred universal Android codec negotiation (prevents cross-chipset H.264 High Profile
 * black screens), background-threaded landscape sensor capture with automatic freeze recovery,
 * and deadlock-free camera flipping.
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

        /**
         * Moves VP8 payload types to the front of the m=video line so every Android device
         * (MediaTek, Exynos, Qualcomm, Kirin) reliably encodes and decodes video frames
         * without H.264 High Profile black-screen mismatches.
         */
        fun preferVp8VideoCodec(sdp: String): String {
            val lineSep = if (sdp.contains("\r\n")) "\r\n" else "\n"
            val lines = sdp.split(lineSep).toMutableList()
            val videoLineIdx = lines.indexOfFirst { it.startsWith("m=video ") }
            if (videoLineIdx < 0) return sdp

            val vp8Payloads = mutableListOf<String>()
            val rtpmapRegex = Regex("^a=rtpmap:(\\d+) VP8/90000", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = rtpmapRegex.find(line)
                if (m != null) vp8Payloads.add(m.groupValues[1])
            }
            if (vp8Payloads.isEmpty()) return sdp

            val parts = lines[videoLineIdx].split(" ")
            if (parts.size <= 4) return sdp
            val header = parts.take(3)
            val payloads = parts.drop(3)
            val reordered = vp8Payloads.filter { it in payloads } + payloads.filter { it !in vp8Payloads }
            lines[videoLineIdx] = (header + reordered).joinToString(" ")
            return lines.joinToString(lineSep)
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    val eglBase: EglBase = EglBase.create()

    private val _state = MutableStateFlow(WebRtcCallState(isCameraEnabled = isVideoCall && canCaptureCamera))
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
    private var cameraEnumerator: CameraEnumerator? = null
    private var currentCameraDevice: String? = null

    private val iceLock = Any()
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val addedCandidateKeys = mutableSetOf<String>()
    private val connectedNotified = AtomicBoolean(false)
    private val isSwitchingCamera = AtomicBoolean(false)
    @Volatile
    private var cameraCapturing = false
    @Volatile
    private var desiredCameraEnabled = isVideoCall && canCaptureCamera
    @Volatile
    private var desiredFrontCamera = true
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
        refreshRemoteVideoTrackFromTransceivers()
        if (connectedNotified.compareAndSet(false, true)) {
            postMain { onIceConnected() }
        }
    }

    private fun refreshRemoteVideoTrackFromTransceivers() {
        if (released || !isVideoCall) return
        runCatching {
            val remoteVid = peerConnection?.transceivers
                ?.mapNotNull { it.receiver?.track() as? VideoTrack }
                ?.firstOrNull()
            if (remoteVid != null) {
                remoteVid.setEnabled(true)
                if (_state.value.remoteVideoTrack !== remoteVid) {
                    _state.value = _state.value.copy(remoteVideoTrack = remoteVid)
                }
            }
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

            // Disable H.264 High Profile to eliminate black-screen decode mismatches across Android devices
            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
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
            val started = if (canCaptureCamera) setupLocalVideoTrack(factory, pc) else false
            if (!started) {
                runCatching {
                    pc.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                    )
                }
            }
        }
    }

    private fun buildCameraEventsHandler(): CameraVideoCapturer.CameraEventsHandler =
        object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String?) {
                Log.w(WEBRTC_TAG, "Camera error: $errorDescription — recovering")
                recoverCameraAsync()
            }
            override fun onCameraDisconnected() {
                Log.w(WEBRTC_TAG, "Camera disconnected — recovering")
                recoverCameraAsync()
            }
            override fun onCameraFreezed(errorDescription: String?) {
                Log.w(WEBRTC_TAG, "Camera freezed: $errorDescription — recovering")
                recoverCameraAsync()
            }
            override fun onCameraOpening(cameraName: String?) {
                Log.d(WEBRTC_TAG, "Camera opening: $cameraName")
            }
            override fun onFirstFrameAvailable() {
                Log.d(WEBRTC_TAG, "Local camera first frame available")
            }
            override fun onCameraClosed() {
                Log.d(WEBRTC_TAG, "Camera closed")
            }
        }

    private fun createCameraEnumerator(): CameraEnumerator =
        if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(true)
        }

    private fun pickCameraDevice(enumerator: CameraEnumerator, preferFront: Boolean): String? {
        val devices = enumerator.deviceNames
        if (devices.isEmpty()) return null
        return if (preferFront) {
            devices.firstOrNull { enumerator.isFrontFacing(it) } ?: devices.firstOrNull()
        } else {
            devices.firstOrNull { enumerator.isBackFacing(it) } ?: devices.firstOrNull()
        }
    }

    private fun setupLocalVideoTrack(factory: PeerConnectionFactory, pc: PeerConnection): Boolean {
        return try {
            val enumerator = createCameraEnumerator()
            cameraEnumerator = enumerator
            val chosenDevice = pickCameraDevice(enumerator, preferFront = true)
            if (chosenDevice == null) {
                Log.w(WEBRTC_TAG, "No camera found on device; proceeding with receive-only video.")
                return false
            }
            val isFront = enumerator.isFrontFacing(chosenDevice)
            desiredFrontCamera = isFront
            currentCameraDevice = chosenDevice

            val textureHelper = SurfaceTextureHelper.create("UChatCaptureThread", eglBase.eglBaseContext)
            val vSource = factory.createVideoSource(false)
            vSource.adaptOutputFormat(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            val vTrack = factory.createVideoTrack("uchat_video_0", vSource).apply { setEnabled(true) }

            surfaceTextureHelper = textureHelper
            videoSource = vSource
            localVideoTrack = vTrack

            val sender = pc.addTrack(vTrack, listOf("uchat_stream"))
            runCatching {
                val params = sender.parameters
                params.encodings?.forEach { enc ->
                    enc.minBitrateBps = 120_000
                    enc.maxBitrateBps = 650_000
                    enc.maxFramerate = CAPTURE_FPS
                }
                sender.parameters = params
            }

            _state.value = _state.value.copy(
                localVideoTrack = vTrack,
                hasLocalCamera = true,
                isFrontCamera = isFront,
                isCameraEnabled = true
            )

            // Start hardware capture on dedicated background thread so UI never stalls
            cameraExecutor.execute {
                if (released) return@execute
                openAndStartCapturerOnCameraThread(chosenDevice)
            }
            true
        } catch (e: Exception) {
            Log.w(WEBRTC_TAG, "Camera setup unavailable: ${e.message}")
            _state.value = _state.value.copy(hasLocalCamera = false)
            false
        }
    }

    private fun openAndStartCapturerOnCameraThread(deviceName: String) {
        if (released) return
        val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
        val textureHelper = surfaceTextureHelper ?: return
        val vSource = videoSource ?: return

        runCatching {
            if (cameraCapturing) {
                videoCapturer?.stopCapture()
            }
        }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        cameraCapturing = false

        try {
            val capturer = enumerator.createCapturer(deviceName, buildCameraEventsHandler()) ?: return
            capturer.initialize(textureHelper, appContext, vSource.capturerObserver)
            try {
                capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            } catch (_: Exception) {
                capturer.startCapture(480, 360, 20)
            }
            videoCapturer = capturer
            currentCameraDevice = deviceName
            cameraCapturing = true
            val isFront = enumerator.isFrontFacing(deviceName)
            desiredFrontCamera = isFront
            _state.value = _state.value.copy(
                isFrontCamera = isFront,
                hasLocalCamera = true,
                isSwitchingCamera = false
            )
        } catch (e: Exception) {
            Log.e(WEBRTC_TAG, "Failed starting camera $deviceName: ${e.message}")
            cameraCapturing = false
            _state.value = _state.value.copy(isSwitchingCamera = false)
        }
    }

    private fun recoverCameraAsync() {
        if (released || !desiredCameraEnabled) return
        cameraExecutor.execute {
            if (released || !desiredCameraEnabled) return@execute
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                return@execute
            }
            val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
            val targetDevice = currentCameraDevice
                ?: pickCameraDevice(enumerator, desiredFrontCamera)
                ?: return@execute
            openAndStartCapturerOnCameraThread(targetDevice)
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
                val tunedSdp = if (isVideoCall) preferVp8VideoCodec(desc.description) else desc.description
                val tunedDesc = SessionDescription(desc.type, tunedSdp)
                pc.setLocalDescription(object : SimpleSdpObserver("setLocalOffer") {
                    override fun onSetSuccess() {
                        if (!released) postMain { onOfferCreated(tunedDesc.description) }
                    }
                }, tunedDesc)
            }
        }, constraints)
    }

    fun handleRemoteOfferAndCreateAnswer(
        remoteSdpOffer: String,
        onAnswerCreated: (String) -> Unit
    ) {
        val pc = peerConnection ?: return
        val tunedOffer = if (isVideoCall) preferVp8VideoCodec(remoteSdpOffer) else remoteSdpOffer
        val offerDesc = SessionDescription(SessionDescription.Type.OFFER, tunedOffer)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteOffer") {
            override fun onSetSuccess() {
                if (released) return
                drainPendingRemoteCandidates()
                refreshRemoteVideoTrackFromTransceivers()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
                }
                pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (released || answerDesc == null) return
                        val tunedSdp = if (isVideoCall) preferVp8VideoCodec(answerDesc.description) else answerDesc.description
                        val tunedAnswer = SessionDescription(answerDesc.type, tunedSdp)
                        pc.setLocalDescription(object : SimpleSdpObserver("setLocalAnswer") {
                            override fun onSetSuccess() {
                                if (!released) postMain { onAnswerCreated(tunedAnswer.description) }
                            }
                        }, tunedAnswer)
                    }
                }, constraints)
            }
        }, offerDesc)
    }

    fun setRemoteAnswer(remoteSdpAnswer: String) {
        if (released) return
        val pc = peerConnection ?: return
        val tunedAnswer = if (isVideoCall) preferVp8VideoCodec(remoteSdpAnswer) else remoteSdpAnswer
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, tunedAnswer)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteAnswer") {
            override fun onSetSuccess() {
                if (released) return
                drainPendingRemoteCandidates()
                refreshRemoteVideoTrackFromTransceivers()
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
        if (released || !isVideoCall) return
        desiredCameraEnabled = enabled
        runCatching { localVideoTrack?.setEnabled(enabled) }
        _state.value = _state.value.copy(isCameraEnabled = enabled)
        cameraExecutor.execute {
            if (released) return@execute
            if (enabled) {
                val capturer = videoCapturer
                if (capturer != null && !cameraCapturing) {
                    try {
                        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
                        cameraCapturing = true
                    } catch (e: Exception) {
                        Log.w(WEBRTC_TAG, "Restarting camera after re-enable: ${e.message}")
                        val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
                        val dev = currentCameraDevice ?: pickCameraDevice(enumerator, desiredFrontCamera)
                        if (dev != null) openAndStartCapturerOnCameraThread(dev)
                    }
                } else if (capturer == null) {
                    val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
                    val dev = currentCameraDevice ?: pickCameraDevice(enumerator, desiredFrontCamera)
                    if (dev != null) openAndStartCapturerOnCameraThread(dev)
                }
            } else {
                if (cameraCapturing) {
                    runCatching { videoCapturer?.stopCapture() }
                    cameraCapturing = false
                }
            }
        }
    }

    fun switchCamera() {
        if (released || !isVideoCall || !desiredCameraEnabled) return
        if (!isSwitchingCamera.compareAndSet(false, true)) return

        _state.value = _state.value.copy(isSwitchingCamera = true)
        val targetFront = !desiredFrontCamera

        // Safety watchdog: never let a buggy Camera2 HAL lock the Flip button permanently
        val watchdog = Runnable {
            if (!released && isSwitchingCamera.compareAndSet(true, false)) {
                Log.w(WEBRTC_TAG, "Camera switch timed out — reopening alternate camera cleanly")
                cameraExecutor.execute {
                    val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
                    val targetDevice = pickCameraDevice(enumerator, preferFront = targetFront)
                    if (targetDevice != null) openAndStartCapturerOnCameraThread(targetDevice)
                    else _state.value = _state.value.copy(isSwitchingCamera = false)
                }
            }
        }
        mainHandler.postDelayed(watchdog, 3500L)

        cameraExecutor.execute {
            if (released) {
                mainHandler.removeCallbacks(watchdog)
                isSwitchingCamera.set(false)
                return@execute
            }
            val capturer = videoCapturer
            if (capturer == null || !cameraCapturing) {
                mainHandler.removeCallbacks(watchdog)
                val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
                val targetDevice = pickCameraDevice(enumerator, preferFront = targetFront)
                if (targetDevice != null) {
                    openAndStartCapturerOnCameraThread(targetDevice)
                } else {
                    _state.value = _state.value.copy(isSwitchingCamera = false)
                }
                isSwitchingCamera.set(false)
                return@execute
            }

            try {
                capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        mainHandler.removeCallbacks(watchdog)
                        desiredFrontCamera = isFrontCamera
                        cameraEnumerator?.let { enumerator ->
                            currentCameraDevice = pickCameraDevice(enumerator, preferFront = isFrontCamera)
                        }
                        isSwitchingCamera.set(false)
                        _state.value = _state.value.copy(
                            isFrontCamera = isFrontCamera,
                            isSwitchingCamera = false
                        )
                    }

                    override fun onCameraSwitchError(errorDescription: String?) {
                        mainHandler.removeCallbacks(watchdog)
                        Log.w(WEBRTC_TAG, "Native switchCamera error ($errorDescription) — falling back to reopen")
                        cameraExecutor.execute {
                            val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
                            val targetDevice = pickCameraDevice(enumerator, preferFront = targetFront)
                            if (targetDevice != null) {
                                openAndStartCapturerOnCameraThread(targetDevice)
                            } else {
                                _state.value = _state.value.copy(isSwitchingCamera = false)
                            }
                            isSwitchingCamera.set(false)
                        }
                    }
                })
            } catch (e: Exception) {
                mainHandler.removeCallbacks(watchdog)
                Log.w(WEBRTC_TAG, "switchCamera threw: ${e.message} — reopening")
                val enumerator = cameraEnumerator ?: createCameraEnumerator().also { cameraEnumerator = it }
                val targetDevice = pickCameraDevice(enumerator, preferFront = targetFront)
                if (targetDevice != null) {
                    openAndStartCapturerOnCameraThread(targetDevice)
                } else {
                    _state.value = _state.value.copy(isSwitchingCamera = false)
                }
                isSwitchingCamera.set(false)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        _state.value = _state.value.copy(localVideoTrack = null, remoteVideoTrack = null, isSwitchingCamera = false)
        runCatching { cameraExecutor.shutdownNow() }
        runCatching { if (cameraCapturing) videoCapturer?.stopCapture() }
        cameraCapturing = false
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
