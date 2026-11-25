package com.example.data.model

import androidx.annotation.Keep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AppTimestamp(val epochMillis: Long) : Comparable<AppTimestamp> {
    val seconds: Long
        get() = epochMillis / 1000L

    fun toDate(): Date = Date(epochMillis)

    override fun compareTo(other: AppTimestamp): Int = epochMillis.compareTo(other.epochMillis)

    companion object {
        fun now(): AppTimestamp = AppTimestamp(System.currentTimeMillis())

        fun parseIso(raw: String?): AppTimestamp? {
            if (raw.isNullOrBlank() || raw == "null") return null
            val trimmed = raw.trim()
            // Normalize fractional seconds & timezone for SimpleDateFormat across API 24+
            val normalized = trimmed
                .replace(Regex("\\.(\\d{3})\\d+"), ".$1")
                .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
                .replace(Regex("Z$"), "+0000")
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss"
            )
            for (pattern in patterns) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val parsed = sdf.parse(normalized)
                    if (parsed != null) return AppTimestamp(parsed.time)
                } catch (_: Exception) {
                }
            }
            return trimmed.toLongOrNull()?.let { AppTimestamp(it) }
        }
    }
}

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@Keep
data class UserProfile(
    val id: String = "",
    val userId: String = "",
    val username: String = "",
    val usernameLower: String = "",
    val displayName: String = "",
    val displayNameLower: String = "",
    val about: String = "Hey there! I am using UChat.",
    val avatarBase64: String? = null,
    val online: Boolean = false,
    val lastSeen: AppTimestamp? = null,
    val showLastSeen: Boolean = true,
    val showOnline: Boolean = true,
    val showAbout: Boolean = true,
    val readReceipts: Boolean = true,
    val discoverable: Boolean = true,
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
) {
    fun isCurrentlyOnline(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!online || !showOnline) return false
        val lastSeenMs = lastSeen?.toDate()?.time ?: return true
        return (nowMillis - lastSeenMs) <= PRESENCE_STALE_MS
    }

    companion object {
        const val PRESENCE_STALE_MS = 120_000L
    }
}

@Keep
data class UserSettings(
    val id: String = "",
    val accentHex: String = "#25D366",
    val wallpaperStyle: String = "doodle",
    val fontScale: Double = 1.0,
    val msgNotif: Boolean = true,
    val groupNotif: Boolean = true,
    val callNotif: Boolean = true,
    val soundEnabled: Boolean = true,
    val ringtoneName: String = "Default Ringtone",
    val ringtoneUri: String? = null,
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
)

@Keep
data class ContactEntry(
    val id: String = "",
    val contactUid: String = "",
    val username: String = "",
    val displayName: String = "",
    val avatarBase64: String? = null,
    val createdAt: AppTimestamp? = null
)

@Keep
data class BlockedUser(
    val id: String = "",
    val blockedUid: String = "",
    val username: String = "",
    val displayName: String = "",
    val createdAt: AppTimestamp? = null
)

@Keep
data class ChatPref(
    val id: String = "",
    val conversationId: String = "",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val hidden: Boolean = false,
    val unreadCount: Long = 0L,
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
)

@Keep
data class FollowedChannel(
    val id: String = "",
    val channelId: String = "",
    val channelName: String = "",
    val createdAt: AppTimestamp? = null
)

@Keep
data class Conversation(
    val id: String = "",
    val kind: String = "direct",
    val memberUids: List<String> = emptyList(),
    val adminUids: List<String> = emptyList(),
    val title: String = "",
    val description: String = "",
    val avatarBase64: String? = null,
    val memberNames: Map<String, String> = emptyMap(),
    val memberUsernames: Map<String, String> = emptyMap(),
    val lastMessageText: String = "",
    val lastMessageType: String = "text",
    val lastMessageSenderId: String = "",
    val lastMessageAt: AppTimestamp? = null,
    val typingUid: String? = null,
    val typingAt: AppTimestamp? = null,
    val createdBy: String = "",
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
) {
    fun isSelfChat(myUid: String): Boolean =
        kind == "direct" && memberUids.size == 1 && memberUids.firstOrNull() == myUid

    fun peerUid(myUid: String): String? =
        if (kind == "direct") memberUids.firstOrNull { it != myUid } else null

    fun displayTitle(myUid: String): String {
        if (kind == "group") return title.ifBlank { "Group Chat" }
        if (isSelfChat(myUid)) return "Saved Messages (You)"
        val peer = peerUid(myUid) ?: return title.ifBlank { "Saved Messages (You)" }
        return memberNames[peer]?.takeIf { it.isNotBlank() }
            ?: memberUsernames[peer]?.let { "@$it" }
            ?: "UChat User"
    }

    fun peerUsername(myUid: String): String {
        if (kind == "group") return "${memberUids.size} members"
        if (isSelfChat(myUid)) return "Message yourself"
        val peer = peerUid(myUid) ?: return "Message yourself"
        return memberUsernames[peer]?.let { "@$it" } ?: ""
    }
}

@Keep
data class ChatMessage(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val memberUids: List<String> = emptyList(),
    val messageType: String = "text",
    val text: String = "",
    val mediaBase64: String? = null,
    val mediaName: String? = null,
    val mediaDurationSec: Long? = null,
    val waveform: List<Long>? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val contactName: String? = null,
    val contactUsername: String? = null,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToSender: String? = null,
    val forwarded: Boolean = false,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val starredBy: List<String> = emptyList(),
    val reactions: Map<String, String> = emptyMap(),
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
)

@Keep
data class Channel(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val avatarBase64: String? = null,
    val followerCount: Long = 1L,
    val discoverable: Boolean = true,
    val lastPostText: String = "",
    val lastPostAt: AppTimestamp? = null,
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
)

@Keep
data class ChannelPost(
    val id: String = "",
    val channelId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val postType: String = "text",
    val text: String = "",
    val mediaBase64: String? = null,
    val reactions: Map<String, String> = emptyMap(),
    val discoverable: Boolean = true,
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
)

@Keep
data class CallRecord(
    val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerUsername: String = "",
    val calleeId: String = "",
    val calleeName: String = "",
    val calleeUsername: String = "",
    val participantUids: List<String> = emptyList(),
    val callType: String = "audio",
    val status: String = "ringing",
    val sdpOffer: String? = null,
    val sdpAnswer: String? = null,
    val durationSec: Long = 0L,
    val createdAt: AppTimestamp? = null,
    val updatedAt: AppTimestamp? = null
) {
    fun peerName(myUid: String): String =
        if (callerId == myUid) calleeName else callerName

    fun peerUsername(myUid: String): String =
        if (callerId == myUid) calleeUsername else callerUsername

    fun peerUid(myUid: String): String =
        if (callerId == myUid) calleeId else callerId

    fun outgoing(myUid: String): Boolean = callerId == myUid

    fun activeCall(): Boolean = status == "ringing" || status == "connecting" || status == "connected"
}

@Keep
data class IceCandidateRecord(
    val id: String = "",
    val callId: String = "",
    val senderId: String = "",
    val participantUids: List<String> = emptyList(),
    val candidate: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Long = 0L,
    val createdAt: AppTimestamp? = null
)

enum class MainTab {
    CHATS, CHANNELS, CALLS, SETTINGS
}

enum class ChatFilter {
    ALL, UNREAD, GROUPS, ARCHIVED
}
