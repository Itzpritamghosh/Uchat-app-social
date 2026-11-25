package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.media.MediaUtils
import com.example.media.VoicePlaybackManager
import com.example.media.VoicePlaybackState
import com.example.media.VoiceRecorderController
import com.example.ui.components.AudioWaveformBars
import com.example.ui.components.MessageStatusTicks
import com.example.ui.components.UserAvatar
import com.example.ui.components.extractFirstUrl
import com.example.ui.components.formatDateSeparator
import com.example.ui.components.formatDurationSec
import com.example.ui.components.formatTimeOnly
import com.example.ui.theme.LocalUChatColors
import com.example.viewmodel.UChatViewModel
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val QuickEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🎉")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatDetailScreen(
    conversation: Conversation,
    viewModel: UChatViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit
) {
    if (showBackButton) {
        BackHandler(onBack = onBack)
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val myUid = viewModel.currentUserId

    val messagesState by viewModel.activeMessagesState.collectAsStateWithLifecycle()
    val myProfileState by viewModel.myProfileState.collectAsStateWithLifecycle()
    val profilesState by viewModel.discoverableProfilesState.collectAsStateWithLifecycle()
    val presenceByUid by viewModel.presenceByUidState.collectAsStateWithLifecycle()
    val presenceNowMs by viewModel.presenceClockMs.collectAsStateWithLifecycle()
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val contacts by viewModel.contactsState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    val myProfile = (myProfileState as? UiState.Success)?.data
    val allProfiles = (profilesState as? UiState.Success)?.data ?: emptyList()
    val isSelfChat = conversation.isSelfChat(myUid)
    val peerUid = conversation.peerUid(myUid)
    val peerProfile = remember(allProfiles, presenceByUid, peerUid) {
        peerUid?.let { presenceByUid[it] ?: allProfiles.firstOrNull { p -> p.userId == it } }
    }
    val allConversations = (conversationsState as? UiState.Success)?.data ?: emptyList()

    val voiceRecorder = remember(context) { VoiceRecorderController(context, scope) }
    val voicePlayer = remember(context) { VoicePlaybackManager(context, scope) }
    val recState by voiceRecorder.state.collectAsStateWithLifecycle()
    val playState by voicePlayer.state.collectAsStateWithLifecycle()

    DisposableEffect(conversation.id) {
        onDispose {
            voiceRecorder.release()
            voicePlayer.stop()
            viewModel.setTyping(conversation.id, false)
        }
    }

    var composerText by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showEmojiTray by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showContactPicker by remember { mutableStateOf(false) }
    var showGroupInfoSheet by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Typing indicator debounce
    LaunchedEffect(composerText) {
        if (composerText.isNotBlank()) {
            viewModel.setTyping(conversation.id, true)
            delay(2500L)
            viewModel.setTyping(conversation.id, false)
        } else {
            viewModel.setTyping(conversation.id, false)
        }
    }

    // Mark messages delivered & read whenever snapshot updates
    val messagesList = (messagesState as? UiState.Success)?.data ?: emptyList()
    LaunchedEffect(messagesList.size, conversation.id) {
        if (messagesList.isNotEmpty()) {
            viewModel.markConversationRead(conversation.id, messagesList)
        }
    }

    // Launchers
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val base64 = MediaUtils.compressUriToBase64(context, uri, maxDimension = 760, maxBase64Chars = 250_000)
                if (base64 != null) {
                    viewModel.sendMessage(conversation, messageType = "image", text = "", mediaBase64 = base64, replyTo = replyTarget)
                    replyTarget = null
                } else {
                    viewModel.showStatus("Image could not be compressed under 300KB limit.")
                }
            }
        }
    }

    val cameraPreviewLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) {
            scope.launch {
                val base64 = MediaUtils.compressBitmapToBase64(bmp, maxDimension = 760, maxBase64Chars = 250_000)
                if (base64 != null) {
                    viewModel.sendMessage(conversation, messageType = "image", text = "", mediaBase64 = base64, replyTo = replyTarget)
                    replyTarget = null
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraPreviewLauncher.launch(null)
        else viewModel.showStatus("Camera permission is required to capture photos.")
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (!voiceRecorder.startRecording()) {
                viewModel.showStatus("Could not access device microphone.")
            }
        } else {
            viewModel.showStatus("Microphone permission is required for voice messages.")
        }
    }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val (name, base64) = MediaUtils.readSmallDocument(context, uri)
                viewModel.sendMessage(
                    conversation = conversation,
                    messageType = "document",
                    text = name,
                    mediaBase64 = base64,
                    mediaName = name,
                    replyTo = replyTarget
                )
                replyTarget = null
            }
        }
    }

    val audioFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val (name, base64) = MediaUtils.readSmallDocument(context, uri)
                viewModel.sendMessage(
                    conversation = conversation,
                    messageType = "audio",
                    text = name,
                    mediaBase64 = base64,
                    mediaName = name,
                    replyTo = replyTarget
                )
                replyTarget = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun shareCurrentLocation() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                viewModel.sendMessage(
                    conversation = conversation,
                    messageType = "location",
                    text = "Shared location (${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})",
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    replyTo = replyTarget
                )
                replyTarget = null
            } else {
                viewModel.showStatus("Unable to read GPS coordinates right now. Ensure location services are enabled.")
            }
        }.addOnFailureListener {
            viewModel.showStatus("Location unavailable on this device.")
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shareCurrentLocation()
        else viewModel.showStatus("Location permission denied.")
    }

    val listState = rememberLazyListState()
    val filteredMessages = remember(messagesList, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isBlank()) messagesList
        else messagesList.filter { it.text.lowercase().contains(q) || (it.mediaName?.lowercase()?.contains(q) == true) }
    }

    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // Optional subtle wallpaper
        if (settings.wallpaperStyle == "doodle") {
            Image(
                painter = painterResource(id = R.drawable.img_chat_wallpaper_dark),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.55f)
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Chat App Bar
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showBackButton) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("chat_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats")
                            }
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (conversation.kind == "group") showGroupInfoSheet = true
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isOnline = !isSelfChat && conversation.kind == "direct" && peerProfile?.isCurrentlyOnline(presenceNowMs) == true
                            UserAvatar(
                                name = conversation.displayTitle(myUid),
                                avatarBase64 = if (conversation.kind == "group") conversation.avatarBase64 else peerProfile?.avatarBase64,
                                size = 40.dp,
                                online = isOnline,
                                showOfflineDot = !isSelfChat && conversation.kind == "direct" && peerProfile != null,
                                isGroup = conversation.kind == "group",
                                isSelfChat = isSelfChat
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conversation.displayTitle(myUid),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = when {
                                    isSelfChat -> "Message yourself • Synced across devices"
                                    !conversation.typingUid.isNullOrBlank() && conversation.typingUid != myUid -> "typing..."
                                    conversation.kind == "group" -> "${conversation.memberUids.size} members • Tap for group info"
                                    isOnline -> "Online"
                                    peerProfile?.showLastSeen == true && peerProfile.lastSeen != null ->
                                        "Offline • Last seen ${formatTimeOnly(peerProfile.lastSeen)}"
                                    peerProfile != null -> "Offline"
                                    else -> conversation.peerUsername(myUid)
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelfChat || subtitle == "typing..." || subtitle == "Online") {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (conversation.kind == "direct" && !isSelfChat && peerProfile != null) {
                            IconButton(
                                onClick = { viewModel.initiateCall(peerProfile, "video") },
                                modifier = Modifier.testTag("chat_video_call_button")
                            ) {
                                Icon(Icons.Filled.Videocam, contentDescription = "Video call")
                            }
                            IconButton(
                                onClick = { viewModel.initiateCall(peerProfile, "audio") },
                                modifier = Modifier.testTag("chat_audio_call_button")
                            ) {
                                Icon(Icons.Filled.Call, contentDescription = "Audio call")
                            }
                        } else if (conversation.kind == "group") {
                            IconButton(
                                onClick = { showGroupInfoSheet = true },
                                modifier = Modifier.testTag("chat_group_info_button")
                            ) {
                                Icon(Icons.Filled.Group, contentDescription = "Group settings")
                            }
                        }

                        IconButton(
                            onClick = {
                                showSearchBar = !showSearchBar
                                if (!showSearchBar) searchQuery = ""
                            },
                            modifier = Modifier.testTag("chat_search_toggle_button")
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Search in conversation")
                        }
                    }

                    if (showSearchBar) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search messages...") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showSearchBar = false; searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close message search")
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                    HorizontalDivider(color = LocalUChatColors.current.divider)
                }
            }

            // Messages Stream
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (messagesState) {
                    is UiState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is UiState.Error -> {
                        Text(
                            text = (messagesState as UiState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                    }
                    is UiState.Success -> {
                        if (filteredMessages.isEmpty()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                                ) {
                                    Text(
                                        text = if (isSelfChat) {
                                            "Your personal cloud space. Save notes, links, photos, voice memos, or files to yourself across devices."
                                        } else {
                                            "Messages are synced in real time via UChat cloud. Say hello!"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(filteredMessages, key = { _, m -> m.id }) { index, msg ->
                                    val dateLabel = formatDateSeparator(msg.createdAt)
                                    val prevDateLabel = if (index > 0) formatDateSeparator(filteredMessages[index - 1].createdAt) else null
                                    if (dateLabel != prevDateLabel) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                                                shape = RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    text = dateLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }

                                    MessageBubbleItem(
                                        message = msg,
                                        isMine = msg.senderId == myUid,
                                        isGroup = conversation.kind == "group",
                                        isSelfChat = isSelfChat,
                                        showReadReceipts = myProfile?.readReceipts ?: true,
                                        playbackState = playState,
                                        onPlayVoice = {
                                            voicePlayer.togglePlayBase64(
                                                msg.id,
                                                msg.mediaBase64,
                                                (msg.mediaDurationSec ?: 1L).toInt()
                                            )
                                        },
                                        onCycleSpeed = { voicePlayer.cycleSpeed() },
                                        onOpenUrl = { url ->
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }
                                        },
                                        onLongPress = { selectedMessage = msg }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Reply banner
            replyTarget?.let { target ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(34.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${target.senderName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = target.text.ifBlank { "[${target.messageType}]" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { replyTarget = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
            }

            // Quick Emoji Tray
            if (showEmojiTray) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("😀", "😂", "❤️", "🔥", "👍", "🙏", "🎉", "😎").forEach { em ->
                            Text(
                                text = em,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { composerText += em }
                                    .padding(6.dp)
                            )
                        }
                    }
                }
            }

            // Voice Recorder Bar OR Standard Message Composer
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                if (recState.recording || recState.previewReady) {
                    VoiceRecorderComposerBar(
                        recState = recState,
                        playbackState = playState,
                        onTogglePauseResume = { voiceRecorder.togglePauseResume() },
                        onStopToPreview = { voiceRecorder.stopForPreview() },
                        onPlayPreview = {
                            recState.previewFilePath?.let { path ->
                                voicePlayer.togglePlayFile("voice_preview", path, recState.durationSec)
                            }
                        },
                        onCancel = {
                            voicePlayer.stop()
                            voiceRecorder.cancelRecording()
                        },
                        onSend = {
                            voicePlayer.stop()
                            val payload = voiceRecorder.finalizeForSend()
                            if (payload != null) {
                                viewModel.sendMessage(
                                    conversation = conversation,
                                    messageType = "voice",
                                    text = "",
                                    mediaBase64 = payload.first,
                                    mediaDurationSec = payload.second,
                                    waveform = payload.third,
                                    replyTo = replyTarget
                                )
                                replyTarget = null
                            } else {
                                viewModel.showStatus("Voice note was empty or exceeded limit.")
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(26.dp),
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(26.dp))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                IconButton(
                                    onClick = { showEmojiTray = !showEmojiTray },
                                    modifier = Modifier.testTag("composer_emoji_button")
                                ) {
                                    Icon(
                                        Icons.Filled.EmojiEmotions,
                                        contentDescription = "Emoji picker",
                                        tint = if (showEmojiTray) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedTextField(
                                    value = composerText,
                                    onValueChange = { composerText = it.take(4000) },
                                    placeholder = {
                                        Text(
                                            text = if (isSelfChat) "Write a note to yourself..." else "Message",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    maxLines = 4,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("composer_text_input")
                                )
                                IconButton(
                                    onClick = { showAttachmentSheet = true },
                                    modifier = Modifier.testTag("composer_attach_button")
                                ) {
                                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach media", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(
                                    onClick = {
                                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                        if (granted) cameraPreviewLauncher.launch(null)
                                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    },
                                    modifier = Modifier.testTag("composer_camera_button")
                                ) {
                                    Icon(Icons.Filled.CameraAlt, contentDescription = "Camera capture", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        if (composerText.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable {
                                        val textToSend = composerText
                                        composerText = ""
                                        showEmojiTray = false
                                        viewModel.sendMessage(conversation, messageType = "text", text = textToSend, replyTo = replyTarget)
                                        replyTarget = null
                                    }
                                    .testTag("composer_send_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send message",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable {
                                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            if (!voiceRecorder.startRecording()) {
                                                viewModel.showStatus("Unable to start voice recording.")
                                            }
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                    .testTag("composer_voice_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Mic,
                                        contentDescription = "Record voice message",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Attachment Picker Sheet
    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Share in Chat", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachmentTile(Icons.Filled.Image, "Gallery", Color(0xFF25D366)) {
                        showAttachmentSheet = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    AttachmentTile(Icons.Filled.Description, "Document", Color(0xFF7C4DFF)) {
                        showAttachmentSheet = false
                        docPicker.launch(arrayOf("*/*"))
                    }
                    AttachmentTile(Icons.Filled.AudioFile, "Audio", Color(0xFFFFB300)) {
                        showAttachmentSheet = false
                        audioFilePicker.launch(arrayOf("audio/*"))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AttachmentTile(Icons.Filled.LocationOn, "Location", Color(0xFF00BFA5)) {
                        showAttachmentSheet = false
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) shareCurrentLocation()
                        else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    AttachmentTile(Icons.Filled.Person, "Contact", Color(0xFF29B6F6)) {
                        showAttachmentSheet = false
                        showContactPicker = true
                    }
                    AttachmentTile(Icons.Filled.CameraAlt, "Camera", Color(0xFFFF5252)) {
                        showAttachmentSheet = false
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) cameraPreviewLauncher.launch(null)
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }
    }

    // Share Contact Picker Dialog
    if (showContactPicker) {
        val shareable = contacts.map { c ->
            Triple(c.contactUid, c.displayName, c.username)
        }.ifEmpty {
            allProfiles.filter { it.userId != myUid }.map { Triple(it.userId, it.displayName, it.username) }
        }
        AlertDialog(
            onDismissRequest = { showContactPicker = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Share UChat Contact") },
            text = {
                if (shareable.isEmpty()) {
                    Text("No contacts available to share yet.")
                } else {
                    LazyColumn(modifier = Modifier.height(220.dp)) {
                        items(shareable.size) { idx ->
                            val (uid, name, uname) = shareable[idx]
                            val liveContact = presenceByUid[uid]
                            val isContactOnline = liveContact?.isCurrentlyOnline(presenceNowMs) == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.sendMessage(
                                            conversation = conversation,
                                            messageType = "contact",
                                            text = "$name (@$uname)",
                                            contactName = name,
                                            contactUsername = uname,
                                            replyTo = replyTarget
                                        )
                                        replyTarget = null
                                        showContactPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    name = name,
                                    avatarBase64 = liveContact?.avatarBase64,
                                    size = 38.dp,
                                    online = isContactOnline,
                                    showOfflineDot = true
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "@$uname • ${if (isContactOnline) "Online" else "Offline"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isContactOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showContactPicker = false }) { Text("Close") }
            }
        )
    }

    // Message Action Sheet (React, Reply, Forward, Copy, Star, Edit, Delete)
    selectedMessage?.let { msg ->
        val isMine = msg.senderId == myUid
        val starred = myUid in msg.starredBy
        ModalBottomSheet(
            onDismissRequest = { selectedMessage = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(bottom = 28.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickEmojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    viewModel.reactToMessage(conversation.id, msg, emoji)
                                    selectedMessage = null
                                }
                                .padding(6.dp)
                        )
                    }
                }
                HorizontalDivider(color = LocalUChatColors.current.divider)
                MessageActionOption(Icons.AutoMirrored.Filled.Reply, "Reply") {
                    replyTarget = msg
                    selectedMessage = null
                }
                MessageActionOption(Icons.AutoMirrored.Filled.Forward, "Forward") {
                    forwardMessage = msg
                    selectedMessage = null
                }
                if (msg.text.isNotBlank() && !msg.deleted) {
                    MessageActionOption(Icons.Filled.ContentCopy, "Copy text") {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("UChat Message", msg.text))
                        viewModel.showStatus("Copied to clipboard")
                        selectedMessage = null
                    }
                }
                MessageActionOption(
                    if (starred) Icons.Filled.Star else Icons.Filled.StarOutline,
                    if (starred) "Unstar message" else "Star message"
                ) {
                    viewModel.toggleStarMessage(conversation.id, msg)
                    selectedMessage = null
                }
                if (isMine && !msg.deleted && msg.messageType == "text") {
                    MessageActionOption(Icons.Filled.Edit, "Edit message") {
                        editingMessage = msg
                        selectedMessage = null
                    }
                }
                if (isMine && !msg.deleted) {
                    MessageActionOption(Icons.Filled.DeleteOutline, "Delete message", MaterialTheme.colorScheme.error) {
                        viewModel.deleteMessage(conversation.id, msg.id)
                        selectedMessage = null
                    }
                }
            }
        }
    }

    // Edit Message Dialog
    editingMessage?.let { msg ->
        var editedText by remember(msg.id) { mutableStateOf(msg.text) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Edit Message") },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it.take(4000) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.editMessage(conversation.id, msg.id, editedText)
                        editingMessage = null
                    },
                    enabled = editedText.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("Cancel") }
            }
        )
    }

    // Forward Message Dialog
    forwardMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { forwardMessage = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text("Forward to...") },
            text = {
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(allConversations.size) { idx ->
                        val targetConv = allConversations[idx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.sendMessage(
                                        conversation = targetConv,
                                        messageType = msg.messageType,
                                        text = msg.text,
                                        mediaBase64 = msg.mediaBase64,
                                        mediaName = msg.mediaName,
                                        mediaDurationSec = msg.mediaDurationSec?.toInt(),
                                        waveform = msg.waveform?.map { it.toInt() },
                                        latitude = msg.latitude,
                                        longitude = msg.longitude,
                                        contactName = msg.contactName,
                                        contactUsername = msg.contactUsername,
                                        forwarded = true
                                    )
                                    viewModel.showStatus("Forwarded to ${targetConv.displayTitle(myUid)}")
                                    forwardMessage = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(
                                name = targetConv.displayTitle(myUid),
                                size = 38.dp,
                                isGroup = targetConv.kind == "group",
                                isSelfChat = targetConv.isSelfChat(myUid)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(targetConv.displayTitle(myUid), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardMessage = null }) { Text("Close") }
            }
        )
    }

    // Group Info & Admin Management Sheet
    if (showGroupInfoSheet && conversation.kind == "group") {
        GroupInfoBottomSheet(
            conversation = conversation,
            myUid = myUid,
            allProfiles = allProfiles,
            onDismiss = { showGroupInfoSheet = false },
            onSave = { title, desc, members, admins, names, usernames ->
                viewModel.updateGroup(conversation, title, desc, members, admins, names, usernames)
                showGroupInfoSheet = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    isMine: Boolean,
    isGroup: Boolean,
    isSelfChat: Boolean,
    showReadReceipts: Boolean,
    playbackState: VoicePlaybackState,
    onPlayVoice: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onLongPress: () -> Unit
) {
    val extended = LocalUChatColors.current
    val bubbleColor = if (isMine) extended.outgoingBubble else extended.incomingBubble
    val bubbleShape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isMine) 18.dp else 4.dp,
        bottomEnd = if (isMine) 4.dp else 18.dp
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier = Modifier
                .widthIn(min = 90.dp, max = 300.dp)
                .clip(bubbleShape)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .testTag("message_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isGroup && !isMine) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                }

                if (message.forwarded && !message.deleted) {
                    Text(
                        text = "Forwarded",
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                }

                // Reply quote box
                if (!message.replyToText.isNullOrBlank() && !message.deleted) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(28.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = message.replyToSender ?: "Reply",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = message.replyToText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Content by message type
                if (message.deleted) {
                    Text(
                        text = "This message was deleted",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    when (message.messageType) {
                        "image" -> {
                            val mediaVal = message.mediaBase64
                            val isUrl = mediaVal != null && (mediaVal.startsWith("https://") || mediaVal.startsWith("http://"))
                            if (isUrl) {
                                AsyncImage(
                                    model = mediaVal,
                                    contentDescription = "Shared photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                val bmp = remember(mediaVal) { MediaUtils.decodeBase64ToImageBitmap(mediaVal) }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "Shared photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(200.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                }
                            }
                            if (message.text.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                            }
                        }
                        "voice" -> {
                            val active = playbackState.activeId == message.id
                            val playing = active && playbackState.playing
                            val progress = if (active) playbackState.progress else 0f
                            val durText = if (active) {
                                formatDurationSec(playbackState.currentSec.toLong())
                            } else {
                                formatDurationSec(message.mediaDurationSec ?: 0L)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.widthIn(min = 210.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                    modifier = Modifier.size(38.dp).clickable(onClick = onPlayVoice)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (playing) "Pause voice note" else "Play voice note",
                                            tint = Color.Black
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    AudioWaveformBars(
                                        amplitudes = message.waveform?.map { it.toInt() } ?: emptyList(),
                                        progress = progress,
                                        activeColor = MaterialTheme.colorScheme.primary,
                                        inactiveColor = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.fillMaxWidth().height(26.dp)
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(durText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(50),
                                            modifier = Modifier.clickable(onClick = onCycleSpeed)
                                        ) {
                                            Text(
                                                text = "${playbackState.speed}x",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        "document", "audio" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (message.messageType == "audio") Icons.Filled.AudioFile else Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = message.mediaName ?: message.text.ifBlank { "File" },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (message.messageType == "audio") "Audio attachment" else "Document attachment",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        "location" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    if (message.latitude != null && message.longitude != null) {
                                        onOpenUrl("https://maps.google.com/?q=${message.latitude},${message.longitude}")
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Shared GPS Location", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                    Text(
                                        text = "${String.format("%.4f", message.latitude ?: 0.0)}, ${String.format("%.4f", message.longitude ?: 0.0)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        "contact" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(name = message.contactName ?: "U", size = 38.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(message.contactName ?: "UChat Contact", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                    Text("@${message.contactUsername ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                            val firstUrl = remember(message.text) { extractFirstUrl(message.text) }
                            if (firstUrl != null) {
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    color = Color.Black.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().clickable { onOpenUrl(firstUrl) }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(8.dp)
                                    ) {
                                        Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = firstUrl,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.starredBy.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Starred",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (message.edited && !message.deleted) {
                        Text("edited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = formatTimeOnly(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMine && !message.deleted) {
                        MessageStatusTicks(
                            deliveredCount = message.deliveredTo.size,
                            readCount = message.readBy.size,
                            showBlueRead = showReadReceipts,
                            isSelfChat = isSelfChat
                        )
                    }
                }
            }
        }

        // Reactions badge strip
        if (message.reactions.isNotEmpty() && !message.deleted) {
            val grouped = message.reactions.values.groupingBy { it }.eachCount()
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .padding(top = 2.dp, start = 8.dp, end = 8.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    grouped.forEach { (emoji, count) ->
                        Text(
                            text = if (count > 1) "$emoji $count" else emoji,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRecorderComposerBar(
    recState: com.example.media.VoiceRecordingState,
    playbackState: VoicePlaybackState,
    onTogglePauseResume: () -> Unit,
    onStopToPreview: () -> Unit,
    onPlayPreview: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.testTag("voice_cancel_button")) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = "Cancel voice recording", tint = MaterialTheme.colorScheme.error)
        }
        if (recState.recording) {
            IconButton(onClick = onTogglePauseResume, modifier = Modifier.testTag("voice_pause_resume_button")) {
                Icon(
                    imageVector = if (recState.paused) Icons.Filled.Mic else Icons.Filled.Pause,
                    contentDescription = if (recState.paused) "Resume recording" else "Pause recording",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onStopToPreview, modifier = Modifier.testTag("voice_stop_preview_button")) {
                Icon(Icons.Filled.Stop, contentDescription = "Preview voice message", tint = Color.White)
            }
        } else {
            val previewPlaying = playbackState.activeId == "voice_preview" && playbackState.playing
            IconButton(onClick = onPlayPreview, modifier = Modifier.testTag("voice_preview_play_button")) {
                Icon(
                    imageVector = if (previewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play voice preview",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
            AudioWaveformBars(
                amplitudes = recState.waveform,
                progress = if (recState.previewReady && playbackState.activeId == "voice_preview") playbackState.progress else 1f,
                activeColor = if (recState.recording && !recState.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                inactiveColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth().height(24.dp)
            )
            Text(
                text = when {
                    recState.previewReady -> "Preview • ${formatDurationSec(recState.durationSec.toLong())}"
                    recState.paused -> "Paused • ${formatDurationSec(recState.durationSec.toLong())}"
                    else -> "Recording • ${formatDurationSec(recState.durationSec.toLong())}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.size(46.dp).clickable(onClick = onSend).testTag("voice_send_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice message", tint = Color.Black)
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Surface(
            color = tint.copy(alpha = 0.18f),
            shape = CircleShape,
            modifier = Modifier.size(54.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun MessageActionOption(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoBottomSheet(
    conversation: Conversation,
    myUid: String,
    allProfiles: List<UserProfile>,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>, List<String>, Map<String, String>, Map<String, String>) -> Unit
) {
    val isAdmin = myUid in conversation.adminUids
    var title by remember { mutableStateOf(conversation.title) }
    var description by remember { mutableStateOf(conversation.description) }
    var members by remember { mutableStateOf(conversation.memberUids) }
    var admins by remember { mutableStateOf(conversation.adminUids) }
    var names by remember { mutableStateOf(conversation.memberNames) }
    var usernames by remember { mutableStateOf(conversation.memberUsernames) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Group Info", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { if (isAdmin) title = it.take(80) },
                enabled = isAdmin,
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { if (isAdmin) description = it.take(300) },
                enabled = isAdmin,
                label = { Text("Group description") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Members (${members.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                items(members.size) { idx ->
                    val uid = members[idx]
                    val name = names[uid] ?: "UChat User"
                    val uname = usernames[uid] ?: ""
                    val memberIsAdmin = uid in admins
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(name = name, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (memberIsAdmin) "@$uname • Admin" else "@$uname",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (memberIsAdmin) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isAdmin && uid != myUid) {
                            TextButton(onClick = {
                                members = members.filterNot { it == uid }
                                admins = admins.filterNot { it == uid }
                            }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (isAdmin) {
                val addable = allProfiles.filter { it.userId !in members }
                if (addable.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Add member", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        addable.take(3).forEach { candidate ->
                            TextButton(onClick = {
                                members = (members + candidate.userId).distinct()
                                names = names + (candidate.userId to candidate.displayName)
                                usernames = usernames + (candidate.userId to candidate.username)
                            }) {
                                Text("+ ${candidate.displayName.take(14)}")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Group Calling Note: Large multi-party audio/video calls require a WebRTC SFU media server (e.g., LiveKit or mediasoup). One-to-one calls work directly in UChat.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onSave(title, description, members, admins, names, usernames) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Group Changes")
                }
            }
        }
    }
}
