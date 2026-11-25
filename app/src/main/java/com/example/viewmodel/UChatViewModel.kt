package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BlockedUser
import com.example.data.model.CallRecord
import com.example.data.model.Channel
import com.example.data.model.ChannelPost
import com.example.data.model.ChatFilter
import com.example.data.model.ChatMessage
import com.example.data.model.ChatPref
import com.example.data.model.ContactEntry
import com.example.data.model.Conversation
import com.example.data.model.IceCandidateRecord
import com.example.data.model.MainTab
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.model.UserSettings
import com.example.data.repository.CallRepository
import com.example.data.repository.ChannelRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val VM_TAG = "UChatVM"
private const val STOP_TIMEOUT_MS = 5000L

@OptIn(ExperimentalCoroutinesApi::class)
class UChatViewModel(
    val currentUserId: String,
    private val profileRepo: ProfileRepository,
    private val chatRepo: ChatRepository,
    private val channelRepo: ChannelRepository,
    private val callRepo: CallRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(MainTab.CHATS)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _chatFilter = MutableStateFlow(ChatFilter.ALL)
    val chatFilter: StateFlow<ChatFilter> = _chatFilter.asStateFlow()

    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    private val _channelSearchQuery = MutableStateFlow("")
    val channelSearchQuery: StateFlow<String> = _channelSearchQuery.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _activeChannelId = MutableStateFlow<String?>(null)
    val activeChannelId: StateFlow<String?> = _activeChannelId.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private fun <T> Flow<T>.toUiState(label: String): StateFlow<UiState<T>> =
        this.map<T, UiState<T>> { UiState.Success(it) }
            .catch { err ->
                Log.w(VM_TAG, "Error observing $label", err)
                emit(UiState.Error(err.message ?: "Unable to sync $label"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading
            )

    val myProfileState: StateFlow<UiState<UserProfile?>> =
        profileRepo.observeMyProfile(currentUserId).toUiState("profile")

    val discoverableProfilesState: StateFlow<UiState<List<UserProfile>>> =
        profileRepo.observeDiscoverableProfiles().toUiState("users")

    val settingsState: StateFlow<UserSettings> =
        profileRepo.observeSettings(currentUserId)
            .catch { err ->
                Log.w(VM_TAG, "Settings fallback", err)
                emit(UserSettings())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UserSettings())

    val contactsState: StateFlow<List<ContactEntry>> =
        profileRepo.observeContacts(currentUserId)
            .catch {
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val presenceByUidState: StateFlow<Map<String, UserProfile>> =
        combine(
            discoverableProfilesState.map { (it as? UiState.Success)?.data ?: emptyList() },
            contactsState.flatMapLatest { contacts ->
                profileRepo.observeProfilesByIds(contacts.map { it.contactUid })
            }
        ) { discoverable, contactProfiles ->
            buildMap {
                discoverable.forEach { put(it.userId, it) }
                putAll(contactProfiles)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val presenceClockMs: StateFlow<Long> =
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(30_000L)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), System.currentTimeMillis())

    val blockedUsersState: StateFlow<List<BlockedUser>> =
        profileRepo.observeBlockedUsers(currentUserId)
            .catch {
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val conversationsState: StateFlow<UiState<List<Conversation>>> =
        chatRepo.observeConversations(currentUserId).toUiState("conversations")

    val chatPrefsState: StateFlow<Map<String, ChatPref>> =
        chatRepo.observeChatPrefs(currentUserId)
            .catch {
                emit(emptyMap())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val activeMessagesState: StateFlow<UiState<List<ChatMessage>>> =
        _activeConversationId
            .flatMapLatest { convId ->
                if (convId.isNullOrBlank()) flowOf(emptyList())
                else chatRepo.observeMessages(convId, currentUserId)
            }
            .toUiState("messages")

    val channelsState: StateFlow<UiState<List<Channel>>> =
        channelRepo.observePublicChannels().toUiState("channels")

    val followedChannelIdsState: StateFlow<Set<String>> =
        channelRepo.observeFollowedChannels(currentUserId)
            .catch {
                emit(emptySet())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    val activeChannelPostsState: StateFlow<UiState<List<ChannelPost>>> =
        _activeChannelId
            .flatMapLatest { chanId ->
                if (chanId.isNullOrBlank()) flowOf(emptyList())
                else channelRepo.observeChannelPosts(chanId)
            }
            .toUiState("channel posts")

    val callsState: StateFlow<UiState<List<CallRecord>>> =
        callRepo.observeCalls(currentUserId).toUiState("calls")

    init {
        viewModelScope.launch {
            profileRepo.setOnlineStatus(true)
        }
    }

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
    }

    fun setChatFilter(filter: ChatFilter) {
        _chatFilter.value = filter
    }

    fun setChatSearchQuery(q: String) {
        _chatSearchQuery.value = q
    }

    fun setChannelSearchQuery(q: String) {
        _channelSearchQuery.value = q
    }

    fun openConversation(conversationId: String?) {
        _activeConversationId.value = conversationId
        if (!conversationId.isNullOrBlank()) {
            viewModelScope.launch {
                chatRepo.updateChatPref(conversationId, unreadCount = 0L, hidden = false)
            }
        }
    }

    fun openChannel(channelId: String?) {
        _activeChannelId.value = channelId
    }

    fun showStatus(message: String) {
        _statusBanner.value = message
    }

    fun clearStatus() {
        _statusBanner.value = null
    }

    fun validateUsername(raw: String): String? = profileRepo.validateUsernameFormat(raw)

    suspend fun checkUsernameAvailable(raw: String): Result<Boolean> =
        profileRepo.isUsernameAvailable(raw)

    suspend fun completeProfileSetup(
        username: String,
        displayName: String,
        about: String,
        avatarBase64: String?
    ): Result<Unit> = profileRepo.createInitialProfile(username, displayName, about, avatarBase64)

    fun updateMyProfile(
        currentUsername: String,
        newUsername: String,
        displayName: String,
        about: String,
        avatarBase64: String?,
        onDone: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val res = profileRepo.updateProfile(currentUsername, newUsername, displayName, about, avatarBase64)
            onDone(res)
        }
    }

    fun updatePrivacy(
        showLastSeen: Boolean,
        showOnline: Boolean,
        showAbout: Boolean,
        readReceipts: Boolean,
        discoverable: Boolean
    ) {
        viewModelScope.launch {
            profileRepo.updatePrivacy(showLastSeen, showOnline, showAbout, readReceipts, discoverable)
                .onFailure { showStatus(it.message ?: "Failed to save privacy settings") }
        }
    }

    fun updateSettings(updated: UserSettings) {
        viewModelScope.launch {
            profileRepo.updateSettings(updated)
                .onFailure { showStatus(it.message ?: "Failed to save settings") }
        }
    }

    fun setPresenceOnline(online: Boolean) {
        viewModelScope.launch {
            profileRepo.setOnlineStatus(online)
        }
    }

    suspend fun markOfflineBeforeSignOut() {
        profileRepo.setOnlineStatus(false)
    }

    fun addContact(profile: UserProfile) {
        viewModelScope.launch {
            profileRepo.addContact(profile)
                .onSuccess { showStatus("Added ${profile.displayName} to contacts") }
                .onFailure { showStatus(it.message ?: "Could not add contact") }
        }
    }

    fun blockUser(profile: UserProfile) {
        viewModelScope.launch {
            profileRepo.blockUser(profile)
                .onSuccess { showStatus("Blocked @${profile.username}") }
                .onFailure { showStatus(it.message ?: "Could not block user") }
        }
    }

    fun unblockUser(blockedUid: String) {
        viewModelScope.launch {
            profileRepo.unblockUser(blockedUid)
                .onSuccess { showStatus("User unblocked") }
                .onFailure { showStatus(it.message ?: "Could not unblock user") }
        }
    }

    fun submitReport(category: String, details: String, targetUid: String? = null) {
        viewModelScope.launch {
            profileRepo.submitReport(category, details, targetUid)
                .onSuccess { showStatus("Report submitted. Thank you.") }
                .onFailure { showStatus(it.message ?: "Could not submit report") }
        }
    }

    fun startDirectChat(peer: UserProfile) {
        val me = (myProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            chatRepo.getOrCreateDirectConversation(me, peer)
                .onSuccess { convId ->
                    _selectedTab.value = MainTab.CHATS
                    _activeConversationId.value = convId
                }
                .onFailure { showStatus(it.message ?: "Unable to start chat") }
        }
    }

    fun openSelfChat() {
        val me = (myProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            chatRepo.getOrCreateDirectConversation(me, me)
                .onSuccess { convId ->
                    _selectedTab.value = MainTab.CHATS
                    _activeConversationId.value = convId
                }
                .onFailure { showStatus(it.message ?: "Unable to open Saved Messages") }
        }
    }

    fun createGroup(
        title: String,
        description: String,
        avatarBase64: String?,
        members: List<UserProfile>,
        onCreated: () -> Unit
    ) {
        val me = (myProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            chatRepo.createGroupConversation(me, title, description, avatarBase64, members)
                .onSuccess { convId ->
                    _selectedTab.value = MainTab.CHATS
                    _activeConversationId.value = convId
                    onCreated()
                }
                .onFailure { showStatus(it.message ?: "Unable to create group") }
        }
    }

    fun updateGroup(
        conversation: Conversation,
        title: String,
        description: String,
        memberUids: List<String>,
        adminUids: List<String>,
        memberNames: Map<String, String>,
        memberUsernames: Map<String, String>
    ) {
        viewModelScope.launch {
            chatRepo.updateGroupInfo(
                conversation, title, description, memberUids, adminUids, memberNames, memberUsernames
            ).onSuccess {
                showStatus("Group updated")
            }.onFailure {
                showStatus(it.message ?: "Failed to update group")
            }
        }
    }

    fun sendMessage(
        conversation: Conversation,
        messageType: String = "text",
        text: String = "",
        mediaBase64: String? = null,
        mediaName: String? = null,
        mediaDurationSec: Int? = null,
        waveform: List<Int>? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        contactName: String? = null,
        contactUsername: String? = null,
        replyTo: ChatMessage? = null,
        forwarded: Boolean = false
    ) {
        val me = (myProfileState.value as? UiState.Success)?.data
        val senderName = me?.displayName?.ifBlank { me.username } ?: "UChat User"
        viewModelScope.launch {
            chatRepo.sendMessage(
                conversation = conversation,
                senderName = senderName,
                messageType = messageType,
                text = text,
                mediaBase64 = mediaBase64,
                mediaName = mediaName,
                mediaDurationSec = mediaDurationSec,
                waveform = waveform,
                latitude = latitude,
                longitude = longitude,
                contactName = contactName,
                contactUsername = contactUsername,
                replyTo = replyTo,
                forwarded = forwarded
            ).onFailure { showStatus(it.message ?: "Failed to send message") }
        }
    }

    fun setTyping(conversationId: String, typing: Boolean) {
        viewModelScope.launch {
            chatRepo.setTyping(conversationId, typing)
        }
    }

    fun editMessage(conversationId: String, messageId: String, newText: String) {
        viewModelScope.launch {
            chatRepo.editMessage(conversationId, messageId, newText)
                .onFailure { showStatus(it.message ?: "Could not edit message") }
        }
    }

    fun deleteMessage(conversationId: String, messageId: String) {
        viewModelScope.launch {
            chatRepo.softDeleteMessage(conversationId, messageId)
                .onFailure { showStatus(it.message ?: "Could not delete message") }
        }
    }

    fun reactToMessage(conversationId: String, message: ChatMessage, emoji: String) {
        viewModelScope.launch {
            chatRepo.reactToMessage(conversationId, message, emoji)
        }
    }

    fun toggleStarMessage(conversationId: String, message: ChatMessage) {
        viewModelScope.launch {
            chatRepo.toggleStarMessage(conversationId, message)
        }
    }

    fun markConversationRead(conversationId: String, messages: List<ChatMessage>) {
        val me = (myProfileState.value as? UiState.Success)?.data
        val readReceipts = me?.readReceipts ?: true
        viewModelScope.launch {
            chatRepo.markMessagesDeliveredAndRead(conversationId, messages, readReceipts)
        }
    }

    fun togglePin(conversationId: String, current: Boolean) {
        viewModelScope.launch {
            chatRepo.updateChatPref(conversationId, pinned = !current)
        }
    }

    fun toggleArchive(conversationId: String, current: Boolean) {
        viewModelScope.launch {
            chatRepo.updateChatPref(conversationId, archived = !current)
        }
    }

    fun toggleMute(conversationId: String, current: Boolean) {
        viewModelScope.launch {
            chatRepo.updateChatPref(conversationId, muted = !current)
        }
    }

    fun hideConversation(conversationId: String) {
        viewModelScope.launch {
            chatRepo.updateChatPref(conversationId, hidden = true)
            if (_activeConversationId.value == conversationId) {
                _activeConversationId.value = null
            }
            showStatus("Conversation removed from your chat list")
        }
    }

    fun createChannel(name: String, description: String, avatarBase64: String?, onDone: () -> Unit) {
        val me = (myProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            channelRepo.createChannel(name, description, me.displayName.ifBlank { me.username }, avatarBase64)
                .onSuccess { chanId ->
                    _activeChannelId.value = chanId
                    onDone()
                }
                .onFailure { showStatus(it.message ?: "Unable to create channel") }
        }
    }

    fun toggleFollowChannel(channel: Channel, currentlyFollowing: Boolean) {
        viewModelScope.launch {
            channelRepo.toggleFollowChannel(channel, currentlyFollowing)
                .onFailure { showStatus(it.message ?: "Unable to update follow status") }
        }
    }

    fun publishChannelPost(channel: Channel, text: String, imageBase64: String?) {
        val me = (myProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            channelRepo.publishPost(channel, me.displayName.ifBlank { me.username }, text, imageBase64)
                .onFailure { showStatus(it.message ?: "Unable to publish post") }
        }
    }

    fun reactToChannelPost(channelId: String, post: ChannelPost, emoji: String) {
        viewModelScope.launch {
            channelRepo.reactToPost(channelId, post, emoji)
        }
    }

    fun observeCallCandidates(callId: String): Flow<List<IceCandidateRecord>> =
        callRepo.observeIceCandidates(callId, currentUserId)
            .catch {
                emit(emptyList())
            }

    fun initiateCall(peer: UserProfile, callType: String) {
        val me = (myProfileState.value as? UiState.Success)?.data ?: return
        if (peer.userId == currentUserId) {
            showStatus("Cannot start a call with yourself")
            return
        }
        viewModelScope.launch {
            callRepo.startCall(me, peer, callType)
                .onFailure { showStatus(it.message ?: "Unable to place call") }
        }
    }

    fun publishSdpOffer(callId: String, sdpOffer: String) {
        viewModelScope.launch {
            callRepo.publishSdpOffer(callId, sdpOffer)
                .onFailure { showStatus(it.message ?: "Unable to send WebRTC offer") }
        }
    }

    fun acceptCall(call: CallRecord, sdpAnswer: String) {
        viewModelScope.launch {
            callRepo.acceptCall(call, sdpAnswer)
                .onFailure { showStatus(it.message ?: "Unable to send WebRTC answer") }
        }
    }

    fun markCallConnected(callId: String) {
        viewModelScope.launch {
            callRepo.markCallConnected(callId)
        }
    }

    fun sendIceCandidate(call: CallRecord, sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        viewModelScope.launch {
            callRepo.addIceCandidate(call, sdp, sdpMid, sdpMLineIndex)
        }
    }

    fun finishCall(callId: String, status: String, durationSec: Long) {
        viewModelScope.launch {
            callRepo.finishCall(callId, status, durationSec)
        }
    }

    fun clearCallHistory() {
        val calls = (callsState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            callRepo.clearCallHistory(calls)
                .onSuccess { showStatus("Call history cleared") }
                .onFailure { showStatus(it.message ?: "Could not clear call history") }
        }
    }
}
