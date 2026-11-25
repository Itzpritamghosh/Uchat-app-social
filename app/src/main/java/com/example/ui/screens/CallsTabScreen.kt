package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CallRecord
import com.example.data.model.IceCandidateRecord
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.media.CallAudioController
import com.example.media.WebRtcCallEngine
import com.example.media.WebRtcCallState
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.UserAvatar
import com.example.ui.components.WebRtcVideoRenderer
import com.example.ui.components.formatDurationSec
import com.example.ui.components.formatTimestampShort
import com.example.ui.theme.LocalUChatColors
import com.example.viewmodel.UChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsTabScreen(
    viewModel: UChatViewModel
) {
    val myUid = viewModel.currentUserId
    val callsState by viewModel.callsState.collectAsStateWithLifecycle()
    val profilesState by viewModel.discoverableProfilesState.collectAsStateWithLifecycle()
    val presenceByUid by viewModel.presenceByUidState.collectAsStateWithLifecycle()
    val presenceNowMs by viewModel.presenceClockMs.collectAsStateWithLifecycle()
    val blockedUsers by viewModel.blockedUsersState.collectAsStateWithLifecycle()

    val blockedIds = remember(blockedUsers) { blockedUsers.map { it.blockedUid }.toSet() }
    val allProfiles = (profilesState as? UiState.Success)?.data ?: emptyList()
    val profileByUid = remember(allProfiles, presenceByUid) {
        buildMap {
            allProfiles.forEach { put(it.userId, it) }
            putAll(presenceByUid)
        }
    }

    var showNewCallSheet by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Calls", style = MaterialTheme.typography.headlineMedium)
                IconButton(
                    onClick = { confirmClearHistory = true },
                    modifier = Modifier.testTag("calls_clear_history_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear call history")
                }
            }

            when (val state = callsState) {
                is UiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UiState.Error -> {
                    EmptyStateCard(Icons.Filled.Call, "Unable to load calls", state.message)
                }
                is UiState.Success -> {
                    if (state.data.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Filled.Call,
                            title = "No recent calls",
                            body = "Place a free peer-to-peer WebRTC audio or video call with any UChat user.",
                            action = {
                                TextButton(onClick = { showNewCallSheet = true }) {
                                    Icon(Icons.Filled.AddIcCall, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Start a Call")
                                }
                            }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp)
                        ) {
                            items(state.data, key = { it.id }) { call ->
                                val peerProfile = profileByUid[call.peerUid(myUid)]
                                CallHistoryRow(
                                    call = call,
                                    myUid = myUid,
                                    avatarBase64 = peerProfile?.avatarBase64,
                                    onCallback = { callType ->
                                        val target = peerProfile ?: UserProfile(
                                            userId = call.peerUid(myUid),
                                            username = call.peerUsername(myUid),
                                            displayName = call.peerName(myUid)
                                        )
                                        viewModel.initiateCall(target, callType)
                                    }
                                )
                                HorizontalDivider(
                                    color = LocalUChatColors.current.divider.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(start = 78.dp, end = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showNewCallSheet = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("fab_new_call")
        ) {
            Icon(Icons.Filled.AddIcCall, contentDescription = "Start new call")
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Clear Call History") },
            text = { Text("Remove all completed call entries from your call log?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCallHistory()
                    confirmClearHistory = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    if (showNewCallSheet) {
        val callable = allProfiles.filter { it.userId != myUid && it.userId !in blockedIds }
        ModalBottomSheet(
            onDismissRequest = { showNewCallSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Text(
                    text = "Start WebRTC Audio or Video Call",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                if (callable.isEmpty()) {
                    Text(
                        text = "Invite another user to sign in on UChat to start calling.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(callable, key = { it.userId }) { user ->
                            UserDirectoryRow(
                                profile = user,
                                presenceNowMs = presenceNowMs,
                                onClick = {
                                    showNewCallSheet = false
                                    viewModel.initiateCall(user, "audio")
                                },
                                trailing = {
                                    Row {
                                        IconButton(onClick = {
                                            showNewCallSheet = false
                                            viewModel.initiateCall(user, "audio")
                                        }) {
                                            Icon(Icons.Filled.Call, contentDescription = "Audio call ${user.displayName}", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = {
                                            showNewCallSheet = false
                                            viewModel.initiateCall(user, "video")
                                        }) {
                                            Icon(Icons.Filled.Videocam, contentDescription = "Video call ${user.displayName}", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(
    call: CallRecord,
    myUid: String,
    avatarBase64: String?,
    onCallback: (String) -> Unit
) {
    val extended = LocalUChatColors.current
    val outgoing = call.outgoing(myUid)
    val missed = !outgoing && (call.status == "missed" || call.status == "rejected" || call.status == "cancelled")
    val directionIcon = when {
        missed -> Icons.AutoMirrored.Filled.CallMissed
        outgoing -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val statusTint = if (missed) extended.missedRed else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCallback(call.callType) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("call_history_row_${call.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(name = call.peerName(myUid), avatarBase64 = avatarBase64, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.peerName(myUid),
                style = MaterialTheme.typography.titleMedium,
                color = if (missed) extended.missedRed else MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(directionIcon, contentDescription = null, tint = statusTint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                val durLabel = if (call.durationSec > 0L) " • ${formatDurationSec(call.durationSec)}" else " • ${call.status}"
                Text(
                    text = "${formatTimestampShort(call.createdAt)}$durLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { onCallback("audio") }) {
            Icon(Icons.Filled.Call, contentDescription = "Audio callback", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { onCallback("video") }) {
            Icon(Icons.Filled.Videocam, contentDescription = "Video callback", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ActiveCallOverlay(
    call: CallRecord,
    myUid: String,
    customRingtoneUri: String?,
    soundEnabled: Boolean,
    candidatesFlow: Flow<List<IceCandidateRecord>>,
    onPublishOffer: (String) -> Unit,
    onAcceptWithAnswer: (String) -> Unit,
    onMarkConnected: () -> Unit,
    onSendIceCandidate: (sdp: String, sdpMid: String, sdpMLineIndex: Int) -> Unit,
    onReject: () -> Unit,
    onEnd: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isOutgoing = call.outgoing(myUid)
    val isVideo = call.callType == "video"

    var micMuted by remember(call.id) { mutableStateOf(false) }
    var speakerOn by remember(call.id) { mutableStateOf(isVideo) }
    var cameraOn by remember(call.id) { mutableStateOf(isVideo) }
    var useFrontCamera by remember(call.id) { mutableStateOf(true) }
    var elapsedSec by remember(call.id) { mutableLongStateOf(0L) }
    var calleeAccepted by remember(call.id) { mutableStateOf(isOutgoing || call.status != "ringing") }
    var offerPublished by remember(call.id) { mutableStateOf(false) }
    var answerPublished by remember(call.id) { mutableStateOf(false) }
    var remoteAnswerApplied by remember(call.id) { mutableStateOf(false) }
    var iceNotice by remember(call.id) { mutableStateOf<String?>(null) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionsChecked by remember(call.id) {
        mutableStateOf(hasAudioPermission && (!isVideo || hasCameraPermission))
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] == true || hasAudioPermission
        hasCameraPermission = results[Manifest.permission.CAMERA] == true || hasCameraPermission
        permissionsChecked = true
    }

    LaunchedEffect(call.id) {
        val toRequest = buildList {
            if (!hasAudioPermission) add(Manifest.permission.RECORD_AUDIO)
            if (isVideo && !hasCameraPermission) add(Manifest.permission.CAMERA)
        }
        if (toRequest.isNotEmpty()) {
            permissionsLauncher.launch(toRequest.toTypedArray())
        } else {
            permissionsChecked = true
        }
    }

    // Start real peer-to-peer WebRTC engine once permissions are resolved and (caller OR callee accepted)
    val shouldStartWebRtc = permissionsChecked && hasAudioPermission && (isOutgoing || calleeAccepted)
    val rtcEngine = remember(call.id, shouldStartWebRtc) {
        if (!shouldStartWebRtc) {
            null
        } else {
            WebRtcCallEngine(
                context = context,
                isVideo = isVideo && hasCameraPermission,
                onLocalIceCandidate = { cand ->
                    onSendIceCandidate(cand.sdp, cand.sdpMid ?: "0", cand.sdpMLineIndex)
                },
                onIceConnected = {
                    iceNotice = null
                    onMarkConnected()
                },
                onIceDisconnectedOrFailed = { reason ->
                    iceNotice = reason
                }
            )
        }
    }

    val rtcState by (rtcEngine?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(WebRtcCallState()) })
    val allCandidates by candidatesFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // Caller creates & publishes real WebRTC SDP Offer via Supabase Realtime
    LaunchedEffect(rtcEngine) {
        if (rtcEngine != null && isOutgoing && !offerPublished) {
            offerPublished = true
            rtcEngine.createOffer { offerSdp ->
                onPublishOffer(offerSdp)
            }
        }
    }

    // Caller applies remote SDP Answer when callee accepts
    LaunchedEffect(rtcEngine, call.sdpAnswer) {
        val remoteAnswer = call.sdpAnswer
        if (rtcEngine != null && isOutgoing && !remoteAnswerApplied && !remoteAnswer.isNullOrBlank()) {
            remoteAnswerApplied = true
            rtcEngine.setRemoteAnswer(remoteAnswer)
        }
    }

    // Callee applies remote SDP Offer and publishes SDP Answer after tapping Accept
    LaunchedEffect(rtcEngine, calleeAccepted, call.sdpOffer) {
        val remoteOffer = call.sdpOffer
        if (rtcEngine != null && !isOutgoing && calleeAccepted && !answerPublished && !remoteOffer.isNullOrBlank()) {
            answerPublished = true
            rtcEngine.handleRemoteOfferAndCreateAnswer(remoteOffer) { answerSdp ->
                onAcceptWithAnswer(answerSdp)
            }
        }
    }

    // Feed incoming remote ICE candidates from Supabase Realtime into PeerConnection
    LaunchedEffect(rtcEngine, allCandidates) {
        if (rtcEngine != null) {
            allCandidates
                .filter { it.senderId != myUid }
                .forEach { record ->
                    rtcEngine.addRemoteIceCandidate(
                        sdpMid = record.sdpMid,
                        sdpMLineIndex = record.sdpMLineIndex.toInt(),
                        sdp = record.candidate
                    )
                }
        }
    }

    // Ringtone / Ringback lifecycle — guaranteed single instance, stops immediately when accepted/connected/disposed
    LaunchedEffect(call.id, call.status, calleeAccepted, soundEnabled) {
        if (call.status == "ringing" && !(!isOutgoing && calleeAccepted) && soundEnabled) {
            if (isOutgoing) {
                CallAudioController.startOutgoingRingback(call.id, scope)
            } else {
                CallAudioController.startIncomingRingtone(context, call.id, customRingtoneUri)
            }
        } else {
            CallAudioController.stopAllTones()
        }
    }

    // Configure speaker routing and count duration once connected
    LaunchedEffect(call.id, call.status) {
        if (call.status == "connected" || call.status == "connecting") {
            CallAudioController.setSpeakerphoneOn(context, speakerOn)
        }
        if (call.status == "connected") {
            while (true) {
                delay(1000L)
                elapsedSec += 1L
            }
        }
    }

    DisposableEffect(rtcEngine) {
        onDispose {
            rtcEngine?.release()
        }
    }

    DisposableEffect(call.id) {
        onDispose {
            CallAudioController.resetAudioRouting(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF06160E), Color(0xFF000000), Color(0xFF0A1410))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("active_call_overlay")
    ) {
        // Real WebRTC Video Rendering (Full-screen Remote Video + PiP Local Camera)
        if (isVideo && rtcEngine != null) {
            val remoteTrack = rtcState.remoteVideoTrack
            val localTrack = rtcState.localVideoTrack

            if (remoteTrack != null) {
                WebRtcVideoRenderer(
                    videoTrack = remoteTrack,
                    eglBaseContext = rtcEngine.eglBase.eglBaseContext,
                    mirror = false,
                    zOrderMediaOverlay = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("webrtc_remote_video_view")
                )
                if (localTrack != null && cameraOn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 72.dp, end = 16.dp)
                            .size(width = 114.dp, height = 164.dp)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    ) {
                        WebRtcVideoRenderer(
                            videoTrack = localTrack,
                            eglBaseContext = rtcEngine.eglBase.eglBaseContext,
                            mirror = useFrontCamera,
                            zOrderMediaOverlay = true,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("webrtc_local_pip_video_view")
                        )
                    }
                }
            } else if (localTrack != null && cameraOn) {
                // Local live WebRTC camera preview while ringing or negotiating peer video
                WebRtcVideoRenderer(
                    videoTrack = localTrack,
                    eglBaseContext = rtcEngine.eglBase.eglBaseContext,
                    mirror = useFrontCamera,
                    zOrderMediaOverlay = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("webrtc_local_preview_view")
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f))
                )
            }
        }

        // Top caller identity & live WebRTC signaling status
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 44.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val hideCenterIdentity = isVideo && rtcState.remoteVideoTrack != null
            if (!hideCenterIdentity) {
                UserAvatar(name = call.peerName(myUid), size = 96.dp)
                Spacer(Modifier.height(18.dp))
                Text(
                    text = call.peerName(myUid),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Text(
                    text = "@${call.peerUsername(myUid)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
            }

            val statusLabel = when {
                !hasAudioPermission && permissionsChecked -> "Microphone permission required"
                iceNotice != null -> iceNotice!!
                call.status == "ringing" && isOutgoing -> "Calling (${call.callType})..."
                call.status == "ringing" && !isOutgoing && !calleeAccepted -> "Incoming ${call.callType} call..."
                call.status == "connecting" || (!isOutgoing && calleeAccepted && call.status == "ringing") ->
                    "Connecting WebRTC P2P (${rtcState.iceState.lowercase()})..."
                call.status == "connected" -> "WebRTC Live • ${formatDurationSec(elapsedSec)}"
                else -> call.status.replaceFirstChar { it.uppercase() }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
            if (rtcState.errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = rtcState.errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Bottom Controls
        Surface(
            color = Color(0xFF141416).copy(alpha = 0.92f),
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .fillMaxWidth()
        ) {
            if (call.status == "ringing" && !isOutgoing && !calleeAccepted) {
                // Incoming call: Accept or Decline
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallCircleButton(
                        icon = Icons.Filled.CallEnd,
                        label = "Decline",
                        bg = Color(0xFFFF453A),
                        onClick = {
                            CallAudioController.stopAllTones()
                            onReject()
                        },
                        testTag = "call_reject_button"
                    )
                    CallCircleButton(
                        icon = if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                        label = "Accept",
                        bg = Color(0xFF25D366),
                        iconTint = Color.Black,
                        onClick = {
                            CallAudioController.stopAllTones()
                            calleeAccepted = true
                        },
                        testTag = "call_accept_button"
                    )
                }
            } else {
                // Outgoing, Connecting, or Live P2P Call Controls
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CallCircleButton(
                            icon = if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (micMuted) "Unmute" else "Mute",
                            bg = if (micMuted) Color.White else Color(0xFF28282C),
                            iconTint = if (micMuted) Color.Black else Color.White,
                            onClick = {
                                micMuted = !micMuted
                                rtcEngine?.setMicMuted(micMuted)
                                CallAudioController.setMicrophoneMute(context, micMuted)
                            },
                            testTag = "call_mute_button"
                        )
                        CallCircleButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            label = if (speakerOn) "Speaker On" else "Speaker",
                            bg = if (speakerOn) MaterialTheme.colorScheme.primary else Color(0xFF28282C),
                            iconTint = if (speakerOn) Color.Black else Color.White,
                            onClick = {
                                speakerOn = !speakerOn
                                CallAudioController.setSpeakerphoneOn(context, speakerOn)
                            },
                            testTag = "call_speaker_button"
                        )
                        if (isVideo) {
                            CallCircleButton(
                                icon = if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                label = if (cameraOn) "Cam On" else "Cam Off",
                                bg = if (cameraOn) Color(0xFF28282C) else Color.White,
                                iconTint = if (cameraOn) Color.White else Color.Black,
                                onClick = {
                                    cameraOn = !cameraOn
                                    rtcEngine?.setCameraEnabled(cameraOn)
                                },
                                testTag = "call_camera_toggle_button"
                            )
                            CallCircleButton(
                                icon = Icons.Filled.Cameraswitch,
                                label = "Flip",
                                bg = Color(0xFF28282C),
                                onClick = {
                                    useFrontCamera = !useFrontCamera
                                    rtcEngine?.switchCamera()
                                },
                                testTag = "call_camera_flip_button"
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    CallCircleButton(
                        icon = Icons.Filled.CallEnd,
                        label = if (call.status == "ringing" && isOutgoing) "Cancel" else "End Call",
                        bg = Color(0xFFFF453A),
                        onClick = {
                            rtcEngine?.release()
                            CallAudioController.resetAudioRouting(context)
                            onEnd(elapsedSec)
                        },
                        testTag = "call_end_button"
                    )
                }
            }
        }
    }
}

@Composable
private fun CallCircleButton(
    icon: ImageVector,
    label: String,
    bg: Color,
    iconTint: Color = Color.White,
    onClick: () -> Unit,
    testTag: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = bg,
            shape = CircleShape,
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .testTag(testTag)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
    }
}
