package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ChatFilter
import com.example.data.model.ChatPref
import com.example.data.model.ContactEntry
import com.example.data.model.Conversation
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.media.MediaUtils
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.PresenceStatusPill
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatTimeOnly
import com.example.ui.components.formatTimestampShort
import com.example.ui.theme.LocalUChatColors
import com.example.viewmodel.UChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsTabScreen(
    viewModel: UChatViewModel,
    onOpenConversation: (String) -> Unit
) {
    val myUid = viewModel.currentUserId
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val chatPrefs by viewModel.chatPrefsState.collectAsStateWithLifecycle()
    val profilesState by viewModel.discoverableProfilesState.collectAsStateWithLifecycle()
    val contacts by viewModel.contactsState.collectAsStateWithLifecycle()
    val presenceByUid by viewModel.presenceByUidState.collectAsStateWithLifecycle()
    val presenceNowMs by viewModel.presenceClockMs.collectAsStateWithLifecycle()
    val blockedUsers by viewModel.blockedUsersState.collectAsStateWithLifecycle()
    val filter by viewModel.chatFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.chatSearchQuery.collectAsStateWithLifecycle()

    val blockedIds = remember(blockedUsers) { blockedUsers.map { it.blockedUid }.toSet() }
    val allProfiles = (profilesState as? UiState.Success)?.data ?: emptyList()
    val profileByUid = remember(allProfiles, presenceByUid) {
        buildMap {
            allProfiles.forEach { put(it.userId, it) }
            putAll(presenceByUid)
        }
    }

    var showNewChatSheet by remember { mutableStateOf(false) }
    var showNewGroupSheet by remember { mutableStateOf(false) }
    var contextConversation by remember { mutableStateOf<Conversation?>(null) }
    var previewUser by remember { mutableStateOf<UserProfile?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Brand Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "UChat",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        onClick = { viewModel.openSelfChat() },
                        modifier = Modifier.testTag("header_saved_messages_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = "Saved Messages (Message yourself)",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = { showNewGroupSheet = true },
                        modifier = Modifier.testTag("header_new_group_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GroupAdd,
                            contentDescription = "New group chat",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = { showNewChatSheet = true },
                        modifier = Modifier.testTag("header_find_users_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PersonAdd,
                            contentDescription = "Find UChat users",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setChatSearchQuery,
                placeholder = { Text("Search chats or @username", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.setChatSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("chats_search_input")
            )

            // Filter chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val archivedCount = chatPrefs.values.count { it.archived && !it.hidden }
                val chips = listOf(
                    ChatFilter.ALL to "All",
                    ChatFilter.UNREAD to "Unread",
                    ChatFilter.GROUPS to "Groups",
                    ChatFilter.ARCHIVED to if (archivedCount > 0) "Archived ($archivedCount)" else "Archived"
                )
                items(chips) { (chipFilter, label) ->
                    val selected = filter == chipFilter
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setChatFilter(chipFilter) },
                        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = MaterialTheme.colorScheme.outline,
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("chat_filter_${chipFilter.name.lowercase()}")
                    )
                }
            }

            // Main content
            when (val state = conversationsState) {
                is UiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UiState.Error -> {
                    EmptyStateCard(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "Unable to sync chats",
                        body = state.message
                    )
                }
                is UiState.Success -> {
                    val q = searchQuery.trim().lowercase()
                    val visibleConversations = remember(state.data, chatPrefs, filter, q, blockedIds) {
                        state.data
                            .filter { conv ->
                                val pref = chatPrefs[conv.id]
                                if (pref?.hidden == true) return@filter false
                                val peer = conv.peerUid(myUid)
                                if (peer != null && peer in blockedIds) return@filter false
                                val archived = pref?.archived == true
                                when (filter) {
                                    ChatFilter.ARCHIVED -> archived
                                    ChatFilter.GROUPS -> !archived && conv.kind == "group"
                                    ChatFilter.UNREAD -> !archived && (pref?.unreadCount ?: 0L) > 0L
                                    ChatFilter.ALL -> !archived
                                }
                            }
                            .filter { conv ->
                                if (q.isBlank()) true
                                else conv.displayTitle(myUid).lowercase().contains(q) ||
                                    conv.peerUsername(myUid).lowercase().contains(q) ||
                                    conv.lastMessageText.lowercase().contains(q)
                            }
                            .sortedWith(
                                compareByDescending<Conversation> { chatPrefs[it.id]?.pinned == true }
                                    .thenByDescending { it.lastMessageAt?.seconds ?: 0L }
                            )
                    }

                    val matchingNewUsers = remember(allProfiles, q, blockedIds) {
                        if (q.isBlank()) emptyList()
                        else allProfiles.filter { p ->
                            p.userId != myUid &&
                                p.userId !in blockedIds &&
                                (p.usernameLower.contains(q.removePrefix("@")) ||
                                    p.displayNameLower.contains(q))
                        }.take(8)
                    }

                    if (visibleConversations.isEmpty() && matchingNewUsers.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            title = if (filter == ChatFilter.ARCHIVED) "No archived chats" else "No conversations yet",
                            body = if (filter == ChatFilter.ARCHIVED) {
                                "Long-press any chat to archive it out of your main inbox."
                            } else {
                                "Search for friends by @username or tap the button below to start messaging."
                            },
                            action = {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { showNewChatSheet = true },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.testTag("empty_start_chat_button")
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Start a chat")
                                    }
                                    if (filter != ChatFilter.ARCHIVED) {
                                        OutlinedButton(
                                            onClick = { viewModel.openSelfChat() },
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.testTag("empty_saved_messages_button")
                                        ) {
                                            Icon(Icons.Filled.Bookmark, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Message yourself (Saved Messages)")
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp)
                        ) {
                            items(visibleConversations, key = { it.id }) { conv ->
                                val pref = chatPrefs[conv.id]
                                val peerProfile = conv.peerUid(myUid)?.let { profileByUid[it] }
                                ConversationRow(
                                    conversation = conv,
                                    myUid = myUid,
                                    pref = pref,
                                    peerProfile = peerProfile,
                                    presenceNowMs = presenceNowMs,
                                    onClick = { onOpenConversation(conv.id) },
                                    onLongClick = { contextConversation = conv }
                                )
                                HorizontalDivider(
                                    color = LocalUChatColors.current.divider.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(start = 82.dp, end = 16.dp)
                                )
                            }

                            if (matchingNewUsers.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Global UChat Users",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                    )
                                }
                                items(matchingNewUsers, key = { "u_${it.userId}" }) { profile ->
                                    UserDirectoryRow(
                                        profile = profile,
                                        presenceNowMs = presenceNowMs,
                                        onClick = { previewUser = profile }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Primary Compose FAB
        FloatingActionButton(
            onClick = { showNewChatSheet = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("fab_new_chat")
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Start new conversation")
        }
    }

    // Long-press conversation actions sheet
    contextConversation?.let { conv ->
        val pref = chatPrefs[conv.id]
        val pinned = pref?.pinned == true
        val archived = pref?.archived == true
        val muted = pref?.muted == true
        ModalBottomSheet(
            onDismissRequest = { contextConversation = null },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(bottom = 28.dp)) {
                Text(
                    text = conv.displayTitle(myUid),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                SheetActionRow(
                    icon = Icons.Filled.PushPin,
                    label = if (pinned) "Unpin chat" else "Pin chat",
                    onClick = {
                        viewModel.togglePin(conv.id, pinned)
                        contextConversation = null
                    }
                )
                SheetActionRow(
                    icon = if (archived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                    label = if (archived) "Unarchive chat" else "Archive chat",
                    onClick = {
                        viewModel.toggleArchive(conv.id, archived)
                        contextConversation = null
                    }
                )
                SheetActionRow(
                    icon = if (muted) Icons.Filled.NotificationsActive else Icons.AutoMirrored.Filled.VolumeOff,
                    label = if (muted) "Unmute notifications" else "Mute notifications",
                    onClick = {
                        viewModel.toggleMute(conv.id, muted)
                        contextConversation = null
                    }
                )
                SheetActionRow(
                    icon = Icons.Filled.DeleteOutline,
                    label = "Delete chat from my list",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        viewModel.hideConversation(conv.id)
                        contextConversation = null
                    }
                )
            }
        }
    }

    // New Chat & Contacts Directory Sheet (with real-time Online/Offline presence)
    if (showNewChatSheet) {
        UserDirectoryBottomSheet(
            myUid = myUid,
            contacts = contacts.filter { it.contactUid !in blockedIds },
            profiles = allProfiles.filter { it.userId !in blockedIds },
            presenceByUid = profileByUid,
            presenceNowMs = presenceNowMs,
            onDismiss = { showNewChatSheet = false },
            onOpenSelfChat = {
                showNewChatSheet = false
                viewModel.openSelfChat()
            },
            onCreateGroup = {
                showNewChatSheet = false
                showNewGroupSheet = true
            },
            onSelectUser = { selected ->
                showNewChatSheet = false
                if (selected.userId == myUid) {
                    viewModel.openSelfChat()
                } else {
                    previewUser = selected
                }
            }
        )
    }

    // User Profile Preview Sheet (Message / Audio / Video / Add Contact / Block / Report)
    previewUser?.let { target ->
        val liveTarget = profileByUid[target.userId] ?: target
        UserProfilePreviewSheet(
            profile = liveTarget,
            isSelf = liveTarget.userId == myUid,
            presenceNowMs = presenceNowMs,
            onDismiss = { previewUser = null },
            onStartChat = {
                previewUser = null
                viewModel.startDirectChat(liveTarget)
            },
            onAudioCall = {
                previewUser = null
                viewModel.initiateCall(liveTarget, "audio")
            },
            onVideoCall = {
                previewUser = null
                viewModel.initiateCall(liveTarget, "video")
            },
            onAddContact = {
                viewModel.addContact(liveTarget)
                previewUser = null
            },
            onBlock = {
                viewModel.blockUser(liveTarget)
                previewUser = null
            },
            onReport = {
                viewModel.submitReport("user_report", "Reported user @${liveTarget.username}", liveTarget.userId)
                previewUser = null
            }
        )
    }

    // Create Group Sheet
    if (showNewGroupSheet) {
        CreateGroupBottomSheet(
            candidates = allProfiles.filter { it.userId != myUid && it.userId !in blockedIds },
            onDismiss = { showNewGroupSheet = false },
            onCreate = { title, desc, avatar, selectedMembers ->
                viewModel.createGroup(title, desc, avatar, selectedMembers) {
                    showNewGroupSheet = false
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    myUid: String,
    pref: ChatPref?,
    peerProfile: UserProfile?,
    presenceNowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isSelfChat = conversation.isSelfChat(myUid)
    val isOnline = !isSelfChat && conversation.kind == "direct" && peerProfile?.isCurrentlyOnline(presenceNowMs) == true
    val isTyping = !isSelfChat && !conversation.typingUid.isNullOrBlank() && conversation.typingUid != myUid
    val avatar = if (conversation.kind == "group") conversation.avatarBase64 else peerProfile?.avatarBase64
    val unread = pref?.unreadCount ?: 0L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("conversation_row_${conversation.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = conversation.displayTitle(myUid),
            avatarBase64 = avatar,
            size = 54.dp,
            online = isOnline,
            showOfflineDot = !isSelfChat && conversation.kind == "direct" && peerProfile != null,
            isGroup = conversation.kind == "group",
            isSelfChat = isSelfChat
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.displayTitle(myUid),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTimestampShort(conversation.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread > 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when {
                        isTyping -> "typing..."
                        conversation.lastMessageText.isNotBlank() -> conversation.lastMessageText
                        else -> conversation.peerUsername(myUid)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (pref?.muted == true) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (pref?.pinned == true) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    if (unread > 0L) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ) {
                            Text(
                                text = if (unread > 99) "99+" else unread.toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
fun UserDirectoryRow(
    profile: UserProfile,
    presenceNowMs: Long = System.currentTimeMillis(),
    showPresenceBadge: Boolean = true,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    val isOnline = profile.isCurrentlyOnline(presenceNowMs)
    val lastSeenShort = if (!isOnline && profile.showLastSeen && profile.lastSeen != null) {
        formatTimeOnly(profile.lastSeen)
    } else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("contact_row_${profile.userId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = profile.displayName.ifBlank { profile.username },
            avatarBase64 = profile.avatarBase64,
            size = 46.dp,
            online = isOnline,
            showOfflineDot = true
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "@${profile.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            trailing()
        } else if (showPresenceBadge) {
            PresenceStatusPill(
                online = isOnline,
                lastSeenText = lastSeenShort,
                modifier = Modifier.testTag("contact_presence_${profile.userId}")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserDirectoryBottomSheet(
    myUid: String,
    contacts: List<ContactEntry>,
    profiles: List<UserProfile>,
    presenceByUid: Map<String, UserProfile>,
    presenceNowMs: Long,
    onDismiss: () -> Unit,
    onOpenSelfChat: () -> Unit,
    onCreateGroup: () -> Unit,
    onSelectUser: (UserProfile) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase().removePrefix("@")

    val contactProfiles = remember(contacts, presenceByUid, q, presenceNowMs) {
        contacts.map { entry ->
            presenceByUid[entry.contactUid] ?: UserProfile(
                id = entry.contactUid,
                userId = entry.contactUid,
                username = entry.username,
                usernameLower = entry.username.lowercase(),
                displayName = entry.displayName,
                displayNameLower = entry.displayName.lowercase(),
                avatarBase64 = entry.avatarBase64,
                online = false
            )
        }.filter { p ->
            p.userId != myUid &&
                (q.isBlank() || p.usernameLower.contains(q) || p.displayNameLower.contains(q))
        }.sortedWith(
            compareByDescending<UserProfile> { it.isCurrentlyOnline(presenceNowMs) }
                .thenBy { it.displayNameLower }
        )
    }

    val contactUidSet = remember(contactProfiles) { contactProfiles.map { it.userId }.toSet() }
    val onlineContactsCount = remember(contactProfiles, presenceNowMs) {
        contactProfiles.count { it.isCurrentlyOnline(presenceNowMs) }
    }

    val directoryProfiles = remember(profiles, contactUidSet, q, presenceNowMs) {
        profiles.filter { p ->
            p.userId != myUid &&
                p.userId !in contactUidSet &&
                (q.isBlank() || p.usernameLower.contains(q) || p.displayNameLower.contains(q))
        }.sortedWith(
            compareByDescending<UserProfile> { it.isCurrentlyOnline(presenceNowMs) }
                .thenBy { it.displayNameLower }
        )
    }
    val onlineDirectoryCount = remember(directoryProfiles, presenceNowMs) {
        directoryProfiles.count { it.isCurrentlyOnline(presenceNowMs) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Contacts & New Chat",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search by @username or display name") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("directory_search_input")
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSelfChat)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("directory_saved_messages_row"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(
                    name = "Saved Messages",
                    size = 44.dp,
                    isSelfChat = true
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Saved Messages (You)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Message yourself • Notes, media & links",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            SheetActionRow(
                icon = Icons.Filled.GroupAdd,
                label = "Create New Group",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onCreateGroup
            )
            HorizontalDivider(color = LocalUChatColors.current.divider)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            ) {
                if (contactProfiles.isNotEmpty()) {
                    item {
                        Text(
                            text = "Saved Contacts • $onlineContactsCount online",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    items(contactProfiles, key = { "contact_${it.userId}" }) { profile ->
                        UserDirectoryRow(
                            profile = profile,
                            presenceNowMs = presenceNowMs,
                            onClick = { onSelectUser(profile) }
                        )
                    }
                }
                if (directoryProfiles.isNotEmpty()) {
                    item {
                        Text(
                            text = "UChat Directory • $onlineDirectoryCount online",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    items(directoryProfiles, key = { "dir_${it.userId}" }) { profile ->
                        UserDirectoryRow(
                            profile = profile,
                            presenceNowMs = presenceNowMs,
                            onClick = { onSelectUser(profile) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfilePreviewSheet(
    profile: UserProfile,
    isSelf: Boolean,
    presenceNowMs: Long = System.currentTimeMillis(),
    onDismiss: () -> Unit,
    onStartChat: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onAddContact: () -> Unit,
    onBlock: () -> Unit,
    onReport: () -> Unit
) {
    val isOnline = profile.isCurrentlyOnline(presenceNowMs)
    val lastSeenLabel = if (!isOnline && profile.showLastSeen && profile.lastSeen != null) {
        "Last seen ${formatTimeOnly(profile.lastSeen)}"
    } else null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UserAvatar(
                name = profile.displayName,
                avatarBase64 = profile.avatarBase64,
                size = 82.dp,
                online = isOnline,
                showOfflineDot = !isSelf
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "@${profile.username}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (!isSelf) {
                Spacer(Modifier.height(8.dp))
                PresenceStatusPill(
                    online = isOnline,
                    lastSeenText = lastSeenLabel
                )
            }
            if (profile.showAbout && profile.about.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = profile.about,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartChat,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("preview_message_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSelf) "Message Self" else "Chat")
                }
                if (!isSelf) {
                    OutlinedButton(
                        onClick = onAudioCall,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).testTag("preview_audio_call_button")
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Audio")
                    }
                    OutlinedButton(
                        onClick = onVideoCall,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).testTag("preview_video_call_button")
                    ) {
                        Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Video")
                    }
                }
            }

            if (!isSelf) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = onAddContact) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Contact")
                    }
                    TextButton(onClick = onBlock) {
                        Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Block", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onReport) {
                        Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Report", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupBottomSheet(
    candidates: List<UserProfile>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String?, List<UserProfile>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var avatarBase64 by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<UserProfile>() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                avatarBase64 = MediaUtils.compressUriToBase64(context, uri, 320, 140_000)
            }
        }
    }

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
            Text("Create Group", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clickable {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                ) {
                    UserAvatar(
                        name = title.ifBlank { "G" },
                        avatarBase64 = avatarBase64,
                        size = 58.dp,
                        isGroup = true
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomEnd).size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = "Group photo", tint = Color.Black, modifier = Modifier.size(12.dp))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = { Text("Group name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("create_group_name_input")
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(300) },
                label = { Text("Description (optional)") },
                maxLines = 2,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Add members (${selected.size} selected)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            ) {
                items(candidates, key = { it.userId }) { user ->
                    val checked = selected.any { it.userId == user.userId }
                    UserDirectoryRow(
                        profile = user,
                        onClick = {
                            if (checked) selected.removeAll { it.userId == user.userId }
                            else selected.add(user)
                        },
                        trailing = {
                            if (checked) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onCreate(title, description, avatarBase64, selected.toList()) },
                enabled = title.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("create_group_submit_button")
            ) {
                Text("Create Group", fontWeight = FontWeight.Bold)
            }
        }
    }
}
