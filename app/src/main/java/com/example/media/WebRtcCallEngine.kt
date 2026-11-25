package com.example.media

import android.content.Context
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
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

private const val WEBRTC_TAG = "WebRtcEngine"

data class WebRtcCallState(
    val iceState: String = "NEW",
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTrack: VideoTrack? = null,
    val hasLocalCamera: Boolean = false,
    val localCandidatesSent: Int = 0,
    val remoteCandidatesAdded: Int = 0,
    val errorMessage: String? = null
)

/**
 * Real, free peer-to-peer WebRTC audio/video engine using Google public STUN servers
 * and Firestore signaling callbacks.
 */
class WebRtcCallEngine(
    private val context: Context,
    private val isVideo: Boolean,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onIceConnected: () -> Unit,
    private val onIceDisconnectedOrFailed: (String) -> Unit
) {
    companion object {
        private val factoryInitialized = AtomicBoolean(false)

        val FREE_STUN_SERVERS: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )
    }

    private val appContext = context.applicationContext
    val eglBase: EglBase = EglBase.create()

    private val _state = MutableStateFlow(WebRtcCallState())
    val state: StateFlow<WebRtcCallState> = _state.asStateFlow()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val addedCandidateKeys = mutableSetOf<String>()
    private var released = false

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            if (factoryInitialized.compareAndSet(false, true)) {
                val initOptions = PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initOptions)
            }

            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            peerConnectionFactory = factory

            val rtcConfig = PeerConnection.RTCConfiguration(FREE_STUN_SERVERS).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }

            peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (released) return
                    _state.value = _state.value.copy(
                        localCandidatesSent = _state.value.localCandidatesSent + 1
                    )
                    onLocalIceCandidate(candidate)
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    if (released) return
                    Log.d(WEBRTC_TAG, "ICE state -> $newState")
                    _state.value = _state.value.copy(iceState = newState.name)
                    when (newState) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> onIceConnected()
                        PeerConnection.IceConnectionState.FAILED -> onIceDisconnectedOrFailed("Connection failed")
                        PeerConnection.IceConnectionState.DISCONNECTED -> onIceDisconnectedOrFailed("Peer disconnected")
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
                    }
                }

                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    if (released) return
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        track.setEnabled(true)
                        _state.value = _state.value.copy(remoteVideoTrack = track)
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) {
                    if (released) return
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

        // Audio track with echo cancellation & noise suppression
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val aSource = factory.createAudioSource(audioConstraints)
        val aTrack = factory.createAudioTrack("uchat_audio_0", aSource).apply { setEnabled(true) }
        audioSource = aSource
        localAudioTrack = aTrack
        pc.addTrack(aTrack, listOf("uchat_stream"))

        if (isVideo) {
            startLocalVideo(factory, pc)
        }
    }

    private fun startLocalVideo(factory: PeerConnectionFactory, pc: PeerConnection) {
        try {
            val enumerator = if (Camera2Enumerator.isSupported(appContext)) {
                Camera2Enumerator(appContext)
            } else {
                Camera1Enumerator(true)
            }
            val devices = enumerator.deviceNames
            val chosenDevice = devices.firstOrNull { enumerator.isFrontFacing(it) }
                ?: devices.firstOrNull()
            if (chosenDevice == null) {
                Log.w(WEBRTC_TAG, "No camera found on device; proceeding with audio-only media.")
                return
            }
            val capturer = enumerator.createCapturer(chosenDevice, null) ?: return
            val textureHelper = SurfaceTextureHelper.create("UChatCaptureThread", eglBase.eglBaseContext)
            val vSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(textureHelper, appContext, vSource.capturerObserver)
            capturer.startCapture(640, 480, 24)

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
        } catch (e: Exception) {
            Log.w(WEBRTC_TAG, "Camera capture unavailable: ${e.message}")
            _state.value = _state.value.copy(hasLocalCamera = false)
        }
    }

    fun createOffer(onOfferCreated: (String) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
        pc.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (released || desc == null) return
                pc.setLocalDescription(object : SimpleSdpObserver("setLocalOffer") {
                    override fun onSetSuccess() {
                        if (!released) onOfferCreated(desc.description)
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
                remoteDescriptionSet = true
                drainPendingRemoteCandidates()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
                }
                pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (released || answerDesc == null) return
                        pc.setLocalDescription(object : SimpleSdpObserver("setLocalAnswer") {
                            override fun onSetSuccess() {
                                if (!released) onAnswerCreated(answerDesc.description)
                            }
                        }, answerDesc)
                    }
                }, constraints)
            }
        }, offerDesc)
    }

    fun setRemoteAnswer(remoteSdpAnswer: String) {
        if (released || remoteDescriptionSet) return
        val pc = peerConnection ?: return
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, remoteSdpAnswer)
        pc.setRemoteDescription(object : SimpleSdpObserver("setRemoteAnswer") {
            override fun onSetSuccess() {
                if (released) return
                remoteDescriptionSet = true
                drainPendingRemoteCandidates()
            }
        }, answerDesc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        if (released) return
        val key = "$sdpMid|$sdpMLineIndex|$sdp"
        if (!addedCandidateKeys.add(key)) return
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        if (!remoteDescriptionSet) {
            pendingRemoteCandidates.add(candidate)
        } else {
            runCatching { peerConnection?.addIceCandidate(candidate) }
            _state.value = _state.value.copy(
                remoteCandidatesAdded = _state.value.remoteCandidatesAdded + 1
            )
        }
    }

    private fun drainPendingRemoteCandidates() {
        val pc = peerConnection ?: return
        val iterator = pendingRemoteCandidates.iterator()
        var added = 0
        while (iterator.hasNext()) {
            val c = iterator.next()
            runCatching { pc.addIceCandidate(c) }
            iterator.remove()
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
