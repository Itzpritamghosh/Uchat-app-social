package com.example.data.cache

import android.content.Context
import android.util.Log
import com.example.data.model.AppTimestamp
import com.example.data.model.CallRecord
import com.example.data.model.Channel
import com.example.data.model.ChatMessage
import com.example.data.model.ChatPref
import com.example.data.model.Conversation
import com.example.data.model.UserProfile
import com.example.data.model.UserSettings
import com.example.data.supabase.SupabaseClient.Companion.jsonArrayToLongList
import com.example.data.supabase.SupabaseClient.Companion.jsonArrayToStringList
import com.example.data.supabase.SupabaseClient.Companion.jsonObjectToStringMap
import com.example.data.supabase.SupabaseClient.Companion.listToJsonArray
import com.example.data.supabase.SupabaseClient.Companion.mapToJsonObject
import com.example.data.supabase.SupabaseClient.Companion.optNullableString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val CACHE_TAG = "LocalChatCache"
private const val MAX_CACHED_MESSAGES_PER_CHAT = 120
private const val MAX_INLINE_MEDIA_CHARS_FOR_DISK = 80_000

/**
 * Thread-safe RAM + atomic disk JSON cache for instant WhatsApp-style startup and chat switching.
 * Cleared completely on sign-out so no private user data persists after logout.
 */
class LocalChatCache(private val appContext: Context) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val profileMem = ConcurrentHashMap<String, UserProfile>()
    private val settingsMem = ConcurrentHashMap<String, UserSettings>()
    private val conversationsMem = ConcurrentHashMap<String, List<Conversation>>()
    private val chatPrefsMem = ConcurrentHashMap<String, Map<String, ChatPref>>()
    private val messagesMem = ConcurrentHashMap<String, List<ChatMessage>>()
    private val callsMem = ConcurrentHashMap<String, List<CallRecord>>()
    @Volatile
    private var channelsMem: List<Channel>? = null
    @Volatile
    private var discoverableProfilesMem: List<UserProfile>? = null

    private fun userDir(userId: String): File {
        val safeUid = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80).ifBlank { "default" }
        return File(appContext.filesDir, "uchat_cache_$safeUid")
    }

    private fun safeFileName(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(96).ifBlank { "item" }

    private fun readFileText(file: File): String? =
        runCatching { if (file.exists() && file.length() > 0L) file.readText() else null }.getOrNull()

    private fun writeFileAtomicAsync(file: File, content: String) {
        ioScope.launch {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(content)
                if (!tmp.renameTo(file)) {
                    file.writeText(content)
                    tmp.delete()
                }
            }.onFailure { e ->
                Log.w(CACHE_TAG, "Disk cache write skipped for ${file.name}: ${e.message}")
            }
        }
    }

    fun getMyProfile(userId: String): UserProfile? {
        profileMem[userId]?.let { return it }
        val text = readFileText(File(userDir(userId), "my_profile.json")) ?: return null
        return runCatching { decodeProfile(JSONObject(text)) }.getOrNull()?.also {
            profileMem[userId] = it
        }
    }

    fun putMyProfile(userId: String, profile: UserProfile) {
        profileMem[userId] = profile
        val json = encodeProfile(profile, forDisk = true).toString()
        writeFileAtomicAsync(File(userDir(userId), "my_profile.json"), json)
    }

    fun getSettings(userId: String): UserSettings? {
        settingsMem[userId]?.let { return it }
        val text = readFileText(File(userDir(userId), "settings.json")) ?: return null
        return runCatching { decodeSettings(JSONObject(text)) }.getOrNull()?.also {
            settingsMem[userId] = it
        }
    }

    fun putSettings(userId: String, settings: UserSettings) {
        settingsMem[userId] = settings
        val json = encodeSettings(settings).toString()
        writeFileAtomicAsync(File(userDir(userId), "settings.json"), json)
    }

    fun getConversations(userId: String): List<Conversation>? {
        conversationsMem[userId]?.let { return it }
        val text = readFileText(File(userDir(userId), "conversations.json")) ?: return null
        return runCatching {
            val arr = JSONArray(text)
            List(arr.length()) { i -> decodeConversation(arr.getJSONObject(i)) }
        }.getOrNull()?.also {
            conversationsMem[userId] = it
        }
    }

    fun putConversations(userId: String, conversations: List<Conversation>) {
        conversationsMem[userId] = conversations
        val arr = JSONArray().apply {
            conversations.forEach { put(encodeConversation(it, forDisk = true)) }
        }
        writeFileAtomicAsync(File(userDir(userId), "conversations.json"), arr.toString())
    }

    fun getChatPrefs(userId: String): Map<String, ChatPref>? {
        chatPrefsMem[userId]?.let { return it }
        val text = readFileText(File(userDir(userId), "chat_prefs.json")) ?: return null
        return runCatching {
            val arr = JSONArray(text)
            buildMap {
                for (i in 0 until arr.length()) {
                    val pref = decodeChatPref(arr.getJSONObject(i))
                    if (pref.conversationId.isNotBlank()) put(pref.conversationId, pref)
                }
            }
        }.getOrNull()?.also {
            chatPrefsMem[userId] = it
        }
    }

    fun putChatPrefs(userId: String, prefs: Map<String, ChatPref>) {
        chatPrefsMem[userId] = prefs
        val arr = JSONArray().apply {
            prefs.values.forEach { put(encodeChatPref(it)) }
        }
        writeFileAtomicAsync(File(userDir(userId), "chat_prefs.json"), arr.toString())
    }

    fun getMessages(userId: String, conversationId: String): List<ChatMessage>? {
        val key = "$userId|$conversationId"
        messagesMem[key]?.let { return it }
        val file = File(File(userDir(userId), "messages"), "${safeFileName(conversationId)}.json")
        val text = readFileText(file) ?: return null
        return runCatching {
            val arr = JSONArray(text)
            List(arr.length()) { i -> decodeMessage(arr.getJSONObject(i)) }
        }.getOrNull()?.also {
            messagesMem[key] = it
        }
    }

    fun putMessages(userId: String, conversationId: String, messages: List<ChatMessage>) {
        val key = "$userId|$conversationId"
        val trimmed = if (messages.size > MAX_CACHED_MESSAGES_PER_CHAT) {
            messages.takeLast(MAX_CACHED_MESSAGES_PER_CHAT)
        } else {
            messages
        }
        messagesMem[key] = trimmed
        val arr = JSONArray().apply {
            trimmed.forEach { put(encodeMessage(it, forDisk = true)) }
        }
        val file = File(File(userDir(userId), "messages"), "${safeFileName(conversationId)}.json")
        writeFileAtomicAsync(file, arr.toString())
    }

    fun getDiscoverableProfiles(userId: String): List<UserProfile>? {
        discoverableProfilesMem?.let { return it }
        val text = readFileText(File(userDir(userId), "discoverable_profiles.json")) ?: return null
        return runCatching {
            val arr = JSONArray(text)
            List(arr.length()) { i -> decodeProfile(arr.getJSONObject(i)) }
        }.getOrNull()?.also {
            discoverableProfilesMem = it
        }
    }

    fun putDiscoverableProfiles(userId: String, profiles: List<UserProfile>) {
        discoverableProfilesMem = profiles
        val arr = JSONArray().apply {
            profiles.take(100).forEach { put(encodeProfile(it, forDisk = true)) }
        }
        writeFileAtomicAsync(File(userDir(userId), "discoverable_profiles.json"), arr.toString())
    }

    fun getCalls(userId: String): List<CallRecord>? {
        callsMem[userId]?.let { return it }
        val text = readFileText(File(userDir(userId), "calls.json")) ?: return null
        return runCatching {
            val arr = JSONArray(text)
            List(arr.length()) { i -> decodeCall(arr.getJSONObject(i)) }
                .filter { !it.activeCall() }
        }.getOrNull()?.also {
            callsMem[userId] = it
        }
    }

    fun putCalls(userId: String, calls: List<CallRecord>) {
        // Cache only completed call history so stale ringing calls never reappear on cold start
        val history = calls.filter { !it.activeCall() }.take(50)
        callsMem[userId] = history
        val arr = JSONArray().apply {
            history.forEach { put(encodeCall(it)) }
        }
        writeFileAtomicAsync(File(userDir(userId), "calls.json"), arr.toString())
    }

    fun getChannels(userId: String): List<Channel>? {
        channelsMem?.let { return it }
        val text = readFileText(File(userDir(userId), "channels.json")) ?: return null
        return runCatching {
            val arr = JSONArray(text)
            List(arr.length()) { i -> decodeChannel(arr.getJSONObject(i)) }
        }.getOrNull()?.also {
            channelsMem = it
        }
    }

    fun putChannels(userId: String, channels: List<Channel>) {
        channelsMem = channels
        val arr = JSONArray().apply {
            channels.take(80).forEach { put(encodeChannel(it, forDisk = true)) }
        }
        writeFileAtomicAsync(File(userDir(userId), "channels.json"), arr.toString())
    }

    fun clearUserCache(userId: String) {
        profileMem.remove(userId)
        settingsMem.remove(userId)
        conversationsMem.remove(userId)
        chatPrefsMem.remove(userId)
        callsMem.remove(userId)
        channelsMem = null
        discoverableProfilesMem = null
        val prefix = "$userId|"
        messagesMem.keys.filter { it.startsWith(prefix) }.forEach { messagesMem.remove(it) }
        runCatching { userDir(userId).deleteRecursively() }
    }

    private fun trimMediaForDisk(value: String?, forDisk: Boolean): String? {
        if (value.isNullOrBlank()) return null
        if (!forDisk) return value
        if (value.startsWith("https://") || value.startsWith("http://")) return value
        return if (value.length <= MAX_INLINE_MEDIA_CHARS_FOR_DISK) value else null
    }

    private fun encodeProfile(p: UserProfile, forDisk: Boolean): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("userId", p.userId)
        put("username", p.username)
        put("usernameLower", p.usernameLower)
        put("displayName", p.displayName)
        put("displayNameLower", p.displayNameLower)
        put("about", p.about)
        trimMediaForDisk(p.avatarBase64, forDisk)?.let { put("avatarBase64", it) }
        put("online", p.online)
        p.lastSeen?.epochMillis?.let { put("lastSeen", it) }
        put("showLastSeen", p.showLastSeen)
        put("showOnline", p.showOnline)
        put("showAbout", p.showAbout)
        put("readReceipts", p.readReceipts)
        put("discoverable", p.discoverable)
        p.createdAt?.epochMillis?.let { put("createdAt", it) }
        p.updatedAt?.epochMillis?.let { put("updatedAt", it) }
    }

    private fun decodeProfile(o: JSONObject): UserProfile = UserProfile(
        id = o.optString("id", ""),
        userId = o.optString("userId", o.optString("id", "")),
        username = o.optString("username", ""),
        usernameLower = o.optString("usernameLower", ""),
        displayName = o.optString("displayName", ""),
        displayNameLower = o.optString("displayNameLower", ""),
        about = o.optString("about", "Hey there! I am using UChat."),
        avatarBase64 = optNullableString(o, "avatarBase64"),
        online = o.optBoolean("online", false),
        lastSeen = if (o.has("lastSeen") && !o.isNull("lastSeen")) AppTimestamp(o.optLong("lastSeen")) else null,
        showLastSeen = o.optBoolean("showLastSeen", true),
        showOnline = o.optBoolean("showOnline", true),
        showAbout = o.optBoolean("showAbout", true),
        readReceipts = o.optBoolean("readReceipts", true),
        discoverable = o.optBoolean("discoverable", true),
        createdAt = if (o.has("createdAt") && !o.isNull("createdAt")) AppTimestamp(o.optLong("createdAt")) else null,
        updatedAt = if (o.has("updatedAt") && !o.isNull("updatedAt")) AppTimestamp(o.optLong("updatedAt")) else null
    )

    private fun encodeSettings(s: UserSettings): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("accentHex", s.accentHex)
        put("wallpaperStyle", s.wallpaperStyle)
        put("fontScale", s.fontScale)
        put("msgNotif", s.msgNotif)
        put("groupNotif", s.groupNotif)
        put("callNotif", s.callNotif)
        put("soundEnabled", s.soundEnabled)
        put("ringtoneName", s.ringtoneName)
        s.ringtoneUri?.let { put("ringtoneUri", it) }
    }

    private fun decodeSettings(o: JSONObject): UserSettings = UserSettings(
        id = o.optString("id", ""),
        accentHex = o.optString("accentHex", "#25D366"),
        wallpaperStyle = o.optString("wallpaperStyle", "doodle"),
        fontScale = o.optDouble("fontScale", 1.0),
        msgNotif = o.optBoolean("msgNotif", true),
        groupNotif = o.optBoolean("groupNotif", true),
        callNotif = o.optBoolean("callNotif", true),
        soundEnabled = o.optBoolean("soundEnabled", true),
        ringtoneName = o.optString("ringtoneName", "Default Ringtone"),
        ringtoneUri = optNullableString(o, "ringtoneUri")
    )

    private fun encodeConversation(c: Conversation, forDisk: Boolean): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("kind", c.kind)
        put("memberUids", listToJsonArray(c.memberUids))
        put("adminUids", listToJsonArray(c.adminUids))
        put("title", c.title)
        put("description", c.description)
        trimMediaForDisk(c.avatarBase64, forDisk)?.let { put("avatarBase64", it) }
        put("memberNames", mapToJsonObject(c.memberNames))
        put("memberUsernames", mapToJsonObject(c.memberUsernames))
        put("lastMessageText", c.lastMessageText)
        put("lastMessageType", c.lastMessageType)
        put("lastMessageSenderId", c.lastMessageSenderId)
        c.lastMessageAt?.epochMillis?.let { put("lastMessageAt", it) }
        c.typingUid?.let { put("typingUid", it) }
        c.typingAt?.epochMillis?.let { put("typingAt", it) }
        put("createdBy", c.createdBy)
        c.createdAt?.epochMillis?.let { put("createdAt", it) }
        c.updatedAt?.epochMillis?.let { put("updatedAt", it) }
    }

    private fun decodeConversation(o: JSONObject): Conversation = Conversation(
        id = o.optString("id", ""),
        kind = o.optString("kind", "direct"),
        memberUids = jsonArrayToStringList(o.optJSONArray("memberUids")),
        adminUids = jsonArrayToStringList(o.optJSONArray("adminUids")),
        title = o.optString("title", ""),
        description = o.optString("description", ""),
        avatarBase64 = optNullableString(o, "avatarBase64"),
        memberNames = jsonObjectToStringMap(o.optJSONObject("memberNames")),
        memberUsernames = jsonObjectToStringMap(o.optJSONObject("memberUsernames")),
        lastMessageText = o.optString("lastMessageText", ""),
        lastMessageType = o.optString("lastMessageType", "text"),
        lastMessageSenderId = o.optString("lastMessageSenderId", ""),
        lastMessageAt = if (o.has("lastMessageAt") && !o.isNull("lastMessageAt")) AppTimestamp(o.optLong("lastMessageAt")) else null,
        typingUid = optNullableString(o, "typingUid"),
        typingAt = if (o.has("typingAt") && !o.isNull("typingAt")) AppTimestamp(o.optLong("typingAt")) else null,
        createdBy = o.optString("createdBy", ""),
        createdAt = if (o.has("createdAt") && !o.isNull("createdAt")) AppTimestamp(o.optLong("createdAt")) else null,
        updatedAt = if (o.has("updatedAt") && !o.isNull("updatedAt")) AppTimestamp(o.optLong("updatedAt")) else null
    )

    private fun encodeChatPref(p: ChatPref): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("conversationId", p.conversationId)
        put("pinned", p.pinned)
        put("archived", p.archived)
        put("muted", p.muted)
        put("hidden", p.hidden)
        put("unreadCount", p.unreadCount)
    }

    private fun decodeChatPref(o: JSONObject): ChatPref = ChatPref(
        id = o.optString("id", ""),
        conversationId = o.optString("conversationId", ""),
        pinned = o.optBoolean("pinned", false),
        archived = o.optBoolean("archived", false),
        muted = o.optBoolean("muted", false),
        hidden = o.optBoolean("hidden", false),
        unreadCount = o.optLong("unreadCount", 0L)
    )

    private fun encodeMessage(m: ChatMessage, forDisk: Boolean): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("conversationId", m.conversationId)
        put("senderId", m.senderId)
        put("senderName", m.senderName)
        put("memberUids", listToJsonArray(m.memberUids))
        put("messageType", m.messageType)
        put("text", m.text)
        trimMediaForDisk(m.mediaBase64, forDisk)?.let { put("mediaBase64", it) }
        m.mediaName?.let { put("mediaName", it) }
        m.mediaDurationSec?.let { put("mediaDurationSec", it) }
        m.waveform?.let { put("waveform", listToJsonArray(it)) }
        m.latitude?.let { put("latitude", it) }
        m.longitude?.let { put("longitude", it) }
        m.contactName?.let { put("contactName", it) }
        m.contactUsername?.let { put("contactUsername", it) }
        m.replyToId?.let { put("replyToId", it) }
        m.replyToText?.let { put("replyToText", it) }
        m.replyToSender?.let { put("replyToSender", it) }
        put("forwarded", m.forwarded)
        put("edited", m.edited)
        put("deleted", m.deleted)
        put("deliveredTo", listToJsonArray(m.deliveredTo))
        put("readBy", listToJsonArray(m.readBy))
        put("starredBy", listToJsonArray(m.starredBy))
        put("reactions", mapToJsonObject(m.reactions))
        m.createdAt?.epochMillis?.let { put("createdAt", it) }
        m.updatedAt?.epochMillis?.let { put("updatedAt", it) }
    }

    private fun decodeMessage(o: JSONObject): ChatMessage = ChatMessage(
        id = o.optString("id", ""),
        conversationId = o.optString("conversationId", ""),
        senderId = o.optString("senderId", ""),
        senderName = o.optString("senderName", ""),
        memberUids = jsonArrayToStringList(o.optJSONArray("memberUids")),
        messageType = o.optString("messageType", "text"),
        text = o.optString("text", ""),
        mediaBase64 = optNullableString(o, "mediaBase64"),
        mediaName = optNullableString(o, "mediaName"),
        mediaDurationSec = if (o.has("mediaDurationSec") && !o.isNull("mediaDurationSec")) o.optLong("mediaDurationSec") else null,
        waveform = jsonArrayToLongList(o.optJSONArray("waveform")),
        latitude = if (o.has("latitude") && !o.isNull("latitude")) o.optDouble("latitude") else null,
        longitude = if (o.has("longitude") && !o.isNull("longitude")) o.optDouble("longitude") else null,
        contactName = optNullableString(o, "contactName"),
        contactUsername = optNullableString(o, "contactUsername"),
        replyToId = optNullableString(o, "replyToId"),
        replyToText = optNullableString(o, "replyToText"),
        replyToSender = optNullableString(o, "replyToSender"),
        forwarded = o.optBoolean("forwarded", false),
        edited = o.optBoolean("edited", false),
        deleted = o.optBoolean("deleted", false),
        deliveredTo = jsonArrayToStringList(o.optJSONArray("deliveredTo")),
        readBy = jsonArrayToStringList(o.optJSONArray("readBy")),
        starredBy = jsonArrayToStringList(o.optJSONArray("starredBy")),
        reactions = jsonObjectToStringMap(o.optJSONObject("reactions")),
        createdAt = if (o.has("createdAt") && !o.isNull("createdAt")) AppTimestamp(o.optLong("createdAt")) else null,
        updatedAt = if (o.has("updatedAt") && !o.isNull("updatedAt")) AppTimestamp(o.optLong("updatedAt")) else null
    )

    private fun encodeCall(c: CallRecord): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("callerId", c.callerId)
        put("callerName", c.callerName)
        put("callerUsername", c.callerUsername)
        put("calleeId", c.calleeId)
        put("calleeName", c.calleeName)
        put("calleeUsername", c.calleeUsername)
        put("participantUids", listToJsonArray(c.participantUids))
        put("callType", c.callType)
        put("status", c.status)
        put("durationSec", c.durationSec)
        c.createdAt?.epochMillis?.let { put("createdAt", it) }
        c.updatedAt?.epochMillis?.let { put("updatedAt", it) }
    }

    private fun decodeCall(o: JSONObject): CallRecord = CallRecord(
        id = o.optString("id", ""),
        callerId = o.optString("callerId", ""),
        callerName = o.optString("callerName", ""),
        callerUsername = o.optString("callerUsername", ""),
        calleeId = o.optString("calleeId", ""),
        calleeName = o.optString("calleeName", ""),
        calleeUsername = o.optString("calleeUsername", ""),
        participantUids = jsonArrayToStringList(o.optJSONArray("participantUids")),
        callType = o.optString("callType", "audio"),
        status = o.optString("status", "ended"),
        durationSec = o.optLong("durationSec", 0L),
        createdAt = if (o.has("createdAt") && !o.isNull("createdAt")) AppTimestamp(o.optLong("createdAt")) else null,
        updatedAt = if (o.has("updatedAt") && !o.isNull("updatedAt")) AppTimestamp(o.optLong("updatedAt")) else null
    )

    private fun encodeChannel(c: Channel, forDisk: Boolean): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("description", c.description)
        put("ownerId", c.ownerId)
        put("ownerName", c.ownerName)
        trimMediaForDisk(c.avatarBase64, forDisk)?.let { put("avatarBase64", it) }
        put("followerCount", c.followerCount)
        put("discoverable", c.discoverable)
        put("lastPostText", c.lastPostText)
        c.lastPostAt?.epochMillis?.let { put("lastPostAt", it) }
        c.createdAt?.epochMillis?.let { put("createdAt", it) }
        c.updatedAt?.epochMillis?.let { put("updatedAt", it) }
    }

    private fun decodeChannel(o: JSONObject): Channel = Channel(
        id = o.optString("id", ""),
        name = o.optString("name", ""),
        description = o.optString("description", ""),
        ownerId = o.optString("ownerId", ""),
        ownerName = o.optString("ownerName", ""),
        avatarBase64 = optNullableString(o, "avatarBase64"),
        followerCount = o.optLong("followerCount", 1L),
        discoverable = o.optBoolean("discoverable", true),
        lastPostText = o.optString("lastPostText", ""),
        lastPostAt = if (o.has("lastPostAt") && !o.isNull("lastPostAt")) AppTimestamp(o.optLong("lastPostAt")) else null,
        createdAt = if (o.has("createdAt") && !o.isNull("createdAt")) AppTimestamp(o.optLong("createdAt")) else null,
        updatedAt = if (o.has("updatedAt") && !o.isNull("updatedAt")) AppTimestamp(o.optLong("updatedAt")) else null
    )
}
