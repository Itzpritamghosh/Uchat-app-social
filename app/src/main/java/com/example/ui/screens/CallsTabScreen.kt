package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    var selectedCallIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    val allCalls = (callsState as? UiState.Success)?.data ?: emptyList()
    val deletableCallIds = remember(allCalls) {
        allCalls.filter { !it.activeCall() }.map { it.id }.toSet()
    }
    val inSelectionMode = selectedCallIds.isNotEmpty()

    LaunchedEffect(deletableCallIds) {
        if (selectedCallIds.isNotEmpty()) {
            selectedCallIds = selectedCallIds.intersect(deletableCallIds)
        }
    }

    BackHandler(enabled = inSelectionMode) {
        selectedCallIds = emptySet()
    }

    fun toggleCallSelection(call: CallRecord) {
        if (call.activeCall()) return
        selectedCallIds = if (call.id in selectedCallIds) {
            selectedCallIds - call.id
        } else {
            selectedCallIds + call.id
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (inSelectionMode) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { selectedCallIds = emptySet() },
                                modifier = Modifier.testTag("calls_selection_close_button")
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${selectedCallIds.size} selected",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    selectedCallIds = if (selectedCallIds.size == deletableCallIds.size) {
                                        emptySet()
                                    } else {
                                        deletableCallIds
                                    }
                                },
                                modifier = Modifier.testTag("calls_select_all_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SelectAll,
                                    contentDescription = "Select all call logs"
                                )
                            }
                            IconButton(
                                onClick = { confirmDeleteSelected = true },
                                modifier = Modifier.testTag("calls_delete_selected_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete selected call logs",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            } else {
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
                            body = "Place a real-time audio or video call with any UChat user.",
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
                                val isSelected = call.id in selectedCallIds
                                CallHistoryRow(
                                    call = call,
                                    myUid = myUid,
                                    avatarBase64 = peerProfile?.avatarBase64,
                                    selected = isSelected,
                                    inSelectionMode = inSelectionMode,
                                    onLongPress = { toggleCallSelection(call) },
                                    onToggleSelect = { toggleCallSelection(call) },
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

        if (!inSelectionMode) {
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
    }

    if (confirmDeleteSelected && selectedCallIds.isNotEmpty()) {
        val count = selectedCallIds.size
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(if (count == 1) "Delete Call Log" else "Delete $count Call Logs") },
            text = {
                Text(
                    if (count == 1) {
                        "Remove the selected call entry from your call log?"
                    } else {
                        "Remove the $count selected call entries from your call log?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelectedCallLogs(selectedCallIds)
                        selectedCallIds = emptySet()
                        confirmDeleteSelected = false
                    },
                    modifier = Modifier.testTag("calls_confirm_delete_selected_button")
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text("Cancel") }
            }
        )
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
                    text = "Start Audio or Video Call",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallHistoryRow(
    call: CallRecord,
    myUid: String,
    avatarBase64: String?,
    selected: Boolean,
    inSelectionMode: Boolean,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
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
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .combinedClickable(
                onClick = {
                    if (inSelectionMode) onToggleSelect() else onCallback(call.callType)
                },
                onLongClick = onLongPress
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("call_history_row_${call.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            UserAvatar(name = call.peerName(myUid), avatarBase64 = avatarBase64, size = 48.dp)
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
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
        if (!inSelectionMode) {
            IconButton(onClick = { onCallback("audio") }) {
                Icon(Icons.Filled.Call, contentDescription = "Audio callback", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { onCallback("video") }) {
                Icon(Icons.Filled.Videocam, contentDescription = "Video callback", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
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
    var controlsVisible by remember(call.id) { mutableStateOf(true) }
    var elapsedSec by remember(call.id) { mutableLongStateOf(0L) }
    var calleeAccepted by remember(call.id) { mutableStateOf(isOutgoing || call.status != "ringing") }
    var offerPublished by remember(call.id) { mutableStateOf(false) }
    var lastAppliedOffer by remember(call.id) { mutableStateOf<String?>(null) }
    var lastAppliedAnswer by remember(call.id) { mutableStateOf<String?>(null) }
    var iceRestartAttempted by remember(call.id) { mutableStateOf(false) }
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

    fun requestMissingCallPermissions() {
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

    LaunchedEffect(call.id) {
        requestMissingCallPermissions()
    }

    val isIncomingRinging = call.status == "ringing" && !isOutgoing && !calleeAccepted

    // Prepare voice communication audio mode + earpiece/speaker route as soon as call is active
    LaunchedEffect(call.id, isIncomingRinging, speakerOn) {
        if (!isIncomingRinging) {
            CallAudioController.prepareVoiceCallAudio(context, speakerOn)
        }
    }

    // Hardware proximity sensor: turn screen off when held near ear in earpiece mode, wake when removed
    val useProximitySensor = !isIncomingRinging && !speakerOn
    LaunchedEffect(call.id, useProximitySensor) {
        CallAudioController.setProximityScreenOffEnabled(context, useProximitySensor)
    }

    // Keep screen awake during video calls or speakerphone calls
    val keepScreenOn = isVideo || speakerOn
    DisposableEffect(call.id, keepScreenOn) {
        val window = context.findActivity()?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Start WebRTC engine once microphone permission is granted and (caller OR callee accepted)
    val shouldStartWebRtc = permissionsChecked && hasAudioPermission && (isOutgoing || calleeAccepted)
    val rtcEngine = remember(call.id, shouldStartWebRtc) {
        if (!shouldStartWebRtc) {
            null
        } else {
            CallAudioController.prepareVoiceCallAudio(context, speakerOn)
            WebRtcCallEngine(
                context = context,
                isVideoCall = isVideo,
                canCaptureCamera = isVideo && hasCameraPermission,
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
    val isLiveConnected = call.status == "connected" || rtcState.isConnected

    // Caller creates & publishes initial WebRTC SDP Offer
    LaunchedEffect(rtcEngine) {
        if (rtcEngine != null && isOutgoing && !offerPublished) {
            offerPublished = true
            rtcEngine.createOffer(iceRestart = false) { offerSdp ->
                onPublishOffer(offerSdp)
            }
        }
    }

    // Caller applies remote SDP Answer whenever callee publishes or updates it (including after ICE restart)
    LaunchedEffect(rtcEngine, call.sdpAnswer) {
        val remoteAnswer = call.sdpAnswer
        if (rtcEngine != null && isOutgoing && !remoteAnswer.isNullOrBlank() && remoteAnswer != lastAppliedAnswer) {
            lastAppliedAnswer = remoteAnswer
            rtcEngine.setRemoteAnswer(remoteAnswer)
        }
    }

    // Callee applies remote SDP Offer and publishes SDP Answer (also handles caller ICE restart offers)
    LaunchedEffect(rtcEngine, calleeAccepted, call.sdpOffer) {
        val remoteOffer = call.sdpOffer
        if (rtcEngine != null && !isOutgoing && calleeAccepted && !remoteOffer.isNullOrBlank() && remoteOffer != lastAppliedOffer) {
            lastAppliedOffer = remoteOffer
            rtcEngine.handleRemoteOfferAndCreateAnswer(remoteOffer) { answerSdp ->
                onAcceptWithAnswer(answerSdp)
            }
        }
    }

    // Automatic ICE restart if caller stays stuck connecting/failed for 12s after answer arrives
    LaunchedEffect(rtcEngine, isOutgoing, lastAppliedAnswer, rtcState.isConnected, rtcState.iceState) {
        if (rtcEngine != null && isOutgoing && !lastAppliedAnswer.isNullOrBlank() && !rtcState.isConnected && !iceRestartAttempted) {
            val waitMs = if (rtcState.iceState == "FAILED") 2_000L else 12_000L
            delay(waitMs)
            if (!rtcState.isConnected && !iceRestartAttempted) {
                iceRestartAttempted = true
                iceNotice = "Switching to relay connection..."
                rtcEngine.createOffer(iceRestart = true) { newOfferSdp ->
                    onPublishOffer(newOfferSdp)
                }
            }
        }
    }

    // Feed incoming remote ICE candidates from Supabase into PeerConnection
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

    // Ringtone / Ringback lifecycle
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

    // Re-apply speaker/earpiece route right after WebRTC audio track starts & count live call duration
    LaunchedEffect(call.id, isLiveConnected) {
        if (isLiveConnected) {
            CallAudioController.setSpeakerphoneOn(context, speakerOn)
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

    val hasRemoteVideo = isVideo && rtcEngine != null && rtcState.remoteVideoTrack != null
    val hasLocalVideo = isVideo && rtcEngine != null && rtcState.localVideoTrack != null && cameraOn

    // Edge-to-edge full-screen root Box (NO statusBarsPadding here so video renders right behind the transparent status bar!)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF071D13), Color(0xFF030705), Color(0xFF06130C))
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = hasRemoteVideo
            ) {
                controlsVisible = !controlsVisible
            }
            .testTag("active_call_overlay")
    ) {
        // Full-bleed edge-to-edge WebRTC video layer
        if (isVideo && rtcEngine != null) {
            val remoteTrack = rtcState.remoteVideoTrack
            val localTrack = rtcState.localVideoTrack

            if (remoteTrack != null) {
                key("remote_video_${call.id}") {
                    WebRtcVideoRenderer(
                        videoTrack = remoteTrack,
                        eglBaseContext = rtcEngine.eglBase.eglBaseContext,
                        mirror = false,
                        zOrderMediaOverlay = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("webrtc_remote_video_view")
                    )
                }
            } else if (localTrack != null && cameraOn) {
                key("local_full_preview_${call.id}") {
                    WebRtcVideoRenderer(
                        videoTrack = localTrack,
                        eglBaseContext = rtcEngine.eglBase.eglBaseContext,
                        mirror = useFrontCamera,
                        zOrderMediaOverlay = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("webrtc_local_preview_view")
                    )
                }
                // Soft vignette over self-preview while waiting for peer video
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f))
                )
            }

            // Subtle top & bottom gradients so transparent status bar icons and controls stay crystal-clear over bright video
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                        )
                    )
            )

            // Floating Local Camera PiP (top-right, below transparent status bar; tap to flip camera)
            if (remoteTrack != null && localTrack != null && cameraOn) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 14.dp, end = 14.dp)
                        .size(width = 112.dp, height = 158.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                        .clickable {
                            useFrontCamera = !useFrontCamera
                            rtcEngine.switchCamera()
                        }
                ) {
                    key("local_pip_${call.id}") {
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
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Cameraswitch,
                                contentDescription = "Switch camera",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        val statusLabel = when {
            !hasAudioPermission && permissionsChecked -> "Microphone permission required"
            isLiveConnected -> formatDurationSec(elapsedSec)
            iceNotice != null -> iceNotice!!
            call.status == "ringing" && isOutgoing -> "Ringing..."
            isIncomingRinging -> "Incoming ${if (isVideo) "video" else "voice"} call"
            else -> "Connecting..."
        }

        // Top Header (compact top-left HUD when remote video is streaming; centered hero card for voice/ringing)
        if (hasRemoteVideo) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 14.dp, end = 138.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isLiveConnected) Color(0xFF25D366) else Color(0xFFFFD60A))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = call.peerName(myUid),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (hasLocalVideo) 36.dp else 72.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), CircleShape)
                ) {
                    UserAvatar(name = call.peerName(myUid), size = 96.dp)
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = call.peerName(myUid),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "@${call.peerUsername(myUid)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isLiveConnected) Color(0xFF25D366) else MaterialTheme.colorScheme.primary
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isLiveConnected) {
                                "${if (speakerOn) "Speaker" else "Earpiece"} • $statusLabel"
                            } else {
                                statusLabel
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                }
                if (!hasAudioPermission && permissionsChecked) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { requestMissingCallPermissions() }) {
                        Text("Allow Microphone")
                    }
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
        }

        // Bottom Floating Controls Dock
        AnimatedVisibility(
            visible = !hasRemoteVideo || controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .fillMaxWidth()
        ) {
            Surface(
                color = Color(0xFF121417).copy(alpha = if (hasRemoteVideo) 0.78f else 0.92f),
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
            ) {
                if (isIncomingRinging) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp, horizontal = 40.dp),
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
                                requestMissingCallPermissions()
                                calleeAccepted = true
                            },
                            testTag = "call_accept_button"
                        )
                    }
                } else if (isVideo) {
                    // Compact sleek single-row dock for video calls so maximum screen shows video
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallCircleButton(
                            icon = if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (micMuted) "Unmute" else "Mute",
                            bg = if (micMuted) Color.White else Color.White.copy(alpha = 0.16f),
                            iconTint = if (micMuted) Color.Black else Color.White,
                            buttonSizeDp = 52,
                            onClick = {
                                micMuted = !micMuted
                                rtcEngine?.setMicMuted(micMuted)
                                CallAudioController.setMicrophoneMute(context, micMuted)
                            },
                            testTag = "call_mute_button"
                        )
                        CallCircleButton(
                            icon = if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            label = if (cameraOn) "Video" else "Cam Off",
                            bg = if (cameraOn) Color.White.copy(alpha = 0.16f) else Color.White,
                            iconTint = if (cameraOn) Color.White else Color.Black,
                            buttonSizeDp = 52,
                            onClick = {
                                cameraOn = !cameraOn
                                rtcEngine?.setCameraEnabled(cameraOn)
                            },
                            testTag = "call_camera_toggle_button"
                        )
                        CallCircleButton(
                            icon = Icons.Filled.Cameraswitch,
                            label = "Flip",
                            bg = Color.White.copy(alpha = 0.16f),
                            buttonSizeDp = 52,
                            onClick = {
                                useFrontCamera = !useFrontCamera
                                rtcEngine?.switchCamera()
                            },
                            testTag = "call_camera_flip_button"
                        )
                        CallCircleButton(
                            icon = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Hearing,
                            label = if (speakerOn) "Speaker" else "Earpiece",
                            bg = if (speakerOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.16f),
                            iconTint = if (speakerOn) Color.Black else Color.White,
                            buttonSizeDp = 52,
                            onClick = {
                                speakerOn = !speakerOn
                                CallAudioController.setSpeakerphoneOn(context, speakerOn)
                            },
                            testTag = "call_speaker_button"
                        )
                        CallCircleButton(
                            icon = Icons.Filled.CallEnd,
                            label = "End",
                            bg = Color(0xFFFF453A),
                            buttonSizeDp = 52,
                            onClick = {
                                rtcEngine?.release()
                                CallAudioController.resetAudioRouting(context)
                                onEnd(elapsedSec)
                            },
                            testTag = "call_end_button"
                        )
                    }
                } else {
                    // Clean Voice Call dock with Mute, Speaker/Earpiece toggle, and Red End Call
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            CallCircleButton(
                                icon = if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                label = if (micMuted) "Unmute" else "Mute",
                                bg = if (micMuted) Color.White else Color.White.copy(alpha = 0.16f),
                                iconTint = if (micMuted) Color.Black else Color.White,
                                onClick = {
                                    micMuted = !micMuted
                                    rtcEngine?.setMicMuted(micMuted)
                                    CallAudioController.setMicrophoneMute(context, micMuted)
                                },
                                testTag = "call_mute_button"
                            )
                            CallCircleButton(
                                icon = if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Hearing,
                                label = if (speakerOn) "Speaker" else "Earpiece",
                                bg = if (speakerOn) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.16f),
                                iconTint = if (speakerOn) Color.Black else Color.White,
                                onClick = {
                                    speakerOn = !speakerOn
                                    CallAudioController.setSpeakerphoneOn(context, speakerOn)
                                },
                                testTag = "call_speaker_button"
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        CallCircleButton(
                            icon = Icons.Filled.CallEnd,
                            label = if (call.status == "ringing" && isOutgoing) "Cancel" else "End Call",
                            bg = Color(0xFFFF453A),
                            buttonSizeDp = 64,
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
}

@Composable
private fun CallCircleButton(
    icon: ImageVector,
    label: String,
    bg: Color,
    iconTint: Color = Color.White,
    buttonSizeDp: Int = 58,
    onClick: () -> Unit,
    testTag: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = bg,
            shape = CircleShape,
            modifier = Modifier
                .size(buttonSizeDp.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .testTag(testTag)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size((buttonSizeDp * 0.45f).dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1
        )
    }
}
