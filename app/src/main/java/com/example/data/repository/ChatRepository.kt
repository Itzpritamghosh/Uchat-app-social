package com.example.data.repository

import com.example.data.model.AppTimestamp
import com.example.data.model.ChatMessage
import com.example.data.model.ChatPref
import com.example.data.model.Conversation
import com.example.data.model.UserProfile
import com.example.data.supabase.SupabaseClient
import com.example.data.supabase.SupabaseClient.Companion.jsonArrayToLongList
import com.example.data.supabase.SupabaseClient.Companion.jsonArrayToStringList
import com.example.data.supabase.SupabaseClient.Companion.jsonObjectToStringMap
import com.example.data.supabase.SupabaseClient.Companion.listToJsonArray
import com.example.data.supabase.SupabaseClient.Companion.mapToJsonObject
import com.example.data.supabase.SupabaseClient.Companion.optNullableString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class ChatRepository(
    private val supabase: SupabaseClient
) {
    private fun requireUserId(): String = supabase.requireUserId()

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    fun observeConversations(userId: String): Flow<List<Conversation>> =
        supabase.observeTable(setOf("conversations"), fallbackPollMs = 8_000L) {
            val rows = supabase.restGet(
                table = "conversations",
                queryParams = listOf(
                    "member_uids" to "cs.{\"$userId\"}",
                    "order" to "last_message_at.desc"
                )
            )
            List(rows.length()) { idx -> parseConversation(rows.getJSONObject(idx)) }
                .sortedByDescending { it.lastMessageAt ?: it.updatedAt ?: AppTimestamp(0L) }
        }

    fun observeChatPrefs(userId: String): Flow<Map<String, ChatPref>> =
        supabase.observeTable(setOf("chat_prefs"), fallbackPollMs = 15_000L) {
            val rows = supabase.restGet(
                table = "chat_prefs",
                queryParams = listOf("owner_id" to "eq.$userId")
            )
            buildMap {
                for (i in 0 until rows.length()) {
                    val pref = parseChatPref(rows.getJSONObject(i))
                    if (pref.conversationId.isNotBlank()) put(pref.conversationId, pref)
                }
            }
        }

    fun observeMessages(conversationId: String, userId: String): Flow<List<ChatMessage>> =
        supabase.observeTable(setOf("messages"), fallbackPollMs = 4_000L) {
            val rows = supabase.restGet(
                table = "messages",
                queryParams = listOf(
                    "conversation_id" to "eq.$conversationId",
                    "member_uids" to "cs.{\"$userId\"}",
                    "order" to "created_at.asc"
                )
            )
            List(rows.length()) { idx -> parseMessage(rows.getJSONObject(idx)) }
        }

    suspend fun getOrCreateDirectConversation(
        myProfile: UserProfile,
        peerProfile: UserProfile
    ): Result<String> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val sortedIds = listOf(uid, peerProfile.userId).sorted()
        val convId = "dm_${sortedIds[0].take(20)}_${sortedIds[1].take(20)}"
        return try {
            val isSelf = uid == peerProfile.userId
            val existing = supabase.restGet(
                table = "conversations",
                queryParams = listOf("id" to "eq.$convId", "limit" to "1")
            )
            if (existing.length() == 0) {
                val members = if (isSelf) listOf(uid) else listOf(uid, peerProfile.userId)
                val memberNames = if (isSelf) {
                    mapOf(uid to myProfile.displayName.ifBlank { myProfile.username })
                } else {
                    mapOf(
                        uid to myProfile.displayName.ifBlank { myProfile.username },
                        peerProfile.userId to peerProfile.displayName.ifBlank { peerProfile.username }
                    )
                }
                val memberUsernames = if (isSelf) {
                    mapOf(uid to myProfile.username)
                } else {
                    mapOf(
                        uid to myProfile.username,
                        peerProfile.userId to peerProfile.username
                    )
                }
                val now = nowIso()
                val payload = JSONObject().apply {
                    put("id", convId)
                    put("kind", "direct")
                    put("member_uids", listToJsonArray(members))
                    put("admin_uids", listToJsonArray(listOf(uid)))
                    put("title", if (isSelf) "Saved Messages (You)" else "")
                    put("description", if (isSelf) "Personal cloud notes & saved messages" else "")
                    put("member_names", mapToJsonObject(memberNames))
                    put("member_usernames", mapToJsonObject(memberUsernames))
                    put("last_message_text", "")
                    put("last_message_type", "text")
                    put("last_message_sender_id", uid)
                    put("last_message_at", now)
                    put("created_by", uid)
                    put("updated_at", now)
                }
                supabase.restPost("conversations", payload, upsert = true, onConflict = "id")
            } else {
                updateChatPref(convId, hidden = false, archived = if (isSelf) false else null)
            }
            Result.success(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createGroupConversation(
        myProfile: UserProfile,
        title: String,
        description: String,
        avatarBase64: String?,
        members: List<UserProfile>
    ): Result<String> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val cleanTitle = title.trim().take(80)
        if (cleanTitle.isBlank()) return Result.failure(IllegalArgumentException("Group name is required."))
        val allProfiles = (listOf(myProfile) + members).distinctBy { it.userId }.take(50)
        val convId = "grp_${UUID.randomUUID()}"
        return try {
            val storedAvatar = supabase.uploadBase64ToStorageOrFallback(
                ownerUid = uid,
                folder = "groups",
                mediaValue = avatarBase64,
                extension = "jpg",
                contentType = "image/jpeg"
            )
            val now = nowIso()
            val payload = JSONObject().apply {
                put("id", convId)
                put("kind", "group")
                put("member_uids", listToJsonArray(allProfiles.map { it.userId }))
                put("admin_uids", listToJsonArray(listOf(uid)))
                put("title", cleanTitle)
                put("description", description.trim().take(300))
                if (!storedAvatar.isNullOrBlank()) put("avatar_url", storedAvatar)
                put("member_names", mapToJsonObject(allProfiles.associate { it.userId to it.displayName.ifBlank { it.username } }))
                put("member_usernames", mapToJsonObject(allProfiles.associate { it.userId to it.username }))
                put("last_message_text", "${myProfile.displayName} created group \"$cleanTitle\"")
                put("last_message_type", "text")
                put("last_message_sender_id", uid)
                put("last_message_at", now)
                put("created_by", uid)
                put("updated_at", now)
            }
            supabase.restPost("conversations", payload)
            Result.success(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateGroupInfo(
        conversation: Conversation,
        title: String,
        description: String,
        memberUids: List<String>,
        adminUids: List<String>,
        memberNames: Map<String, String>,
        memberUsernames: Map<String, String>
    ): Result<Unit> {
        return try {
            requireUserId()
            val body = JSONObject().apply {
                put("title", title.trim().take(80).ifBlank { "Group Chat" })
                put("description", description.trim().take(300))
                put("member_uids", listToJsonArray(memberUids.distinct().take(50)))
                put("admin_uids", listToJsonArray(adminUids.distinct().take(20)))
                put("member_names", mapToJsonObject(memberNames))
                put("member_usernames", mapToJsonObject(memberUsernames))
                put("updated_at", nowIso())
            }
            supabase.restPatch("conversations", listOf("id" to "eq.${conversation.id}"), body)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        conversation: Conversation,
        senderName: String,
        messageType: String,
        text: String,
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
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val cleanText = text.trim().take(4000)
        if (messageType == "text" && cleanText.isBlank()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        if (mediaBase64 != null && mediaBase64.length > 4_000_000) {
            return Result.failure(IllegalArgumentException("Attachment exceeds 3MB upload limit."))
        }

        return try {
            val storedMediaUrl = if (!mediaBase64.isNullOrBlank()) {
                val (folder, ext, mime) = when (messageType) {
                    "image" -> Triple("images", "jpg", "image/jpeg")
                    "voice" -> Triple("voice", "m4a", "audio/mp4")
                    "audio" -> Triple("audio", mediaName?.substringAfterLast('.', "m4a") ?: "m4a", "audio/mpeg")
                    else -> Triple("documents", mediaName?.substringAfterLast('.', "bin") ?: "bin", "application/octet-stream")
                }
                supabase.uploadBase64ToStorageOrFallback(
                    ownerUid = uid,
                    folder = folder,
                    mediaValue = mediaBase64,
                    extension = ext,
                    contentType = mime
                )
            } else {
                null
            }

            val preview = when (messageType) {
                "image" -> if (cleanText.isNotBlank()) "📷 $cleanText" else "📷 Photo"
                "voice" -> "🎤 Voice message (${mediaDurationSec ?: 0}s)"
                "document" -> "📄 ${mediaName ?: "Document"}"
                "audio" -> "🎵 ${mediaName ?: "Audio clip"}"
                "location" -> "📍 Shared location"
                "contact" -> "👤 Contact: ${contactName ?: "UChat User"}"
                else -> cleanText
            }.take(300)

            val now = nowIso()
            val msgPayload = JSONObject().apply {
                put("conversation_id", conversation.id)
                put("sender_id", uid)
                put("sender_name", senderName.trim().take(60).ifBlank { "UChat User" })
                put("member_uids", listToJsonArray(conversation.memberUids))
                put("message_type", messageType)
                put("text", cleanText)
                if (!storedMediaUrl.isNullOrBlank()) put("media_url", storedMediaUrl)
                if (!mediaName.isNullOrBlank()) put("media_name", mediaName.take(160))
                if (mediaDurationSec != null) put("media_duration_sec", mediaDurationSec.coerceIn(0, 3600))
                if (!waveform.isNullOrEmpty()) put("waveform", listToJsonArray(waveform.take(64)))
                if (latitude != null) put("latitude", latitude)
                if (longitude != null) put("longitude", longitude)
                if (!contactName.isNullOrBlank()) put("contact_name", contactName.take(80))
                if (!contactUsername.isNullOrBlank()) put("contact_username", contactUsername.take(30))
                if (replyTo != null) {
                    put("reply_to_id", replyTo.id.take(128))
                    put("reply_to_text", replyTo.text.ifBlank { "[${replyTo.messageType}]" }.take(200))
                    put("reply_to_sender", replyTo.senderName.take(60))
                }
                put("forwarded", forwarded)
                put("edited", false)
                put("deleted", false)
                put("delivered_to", listToJsonArray(listOf(uid)))
                put("read_by", listToJsonArray(listOf(uid)))
                put("starred_by", listToJsonArray(emptyList()))
                put("reactions", JSONObject())
                put("updated_at", now)
            }
            supabase.restPost("messages", msgPayload)

            val convUpdate = JSONObject().apply {
                put("last_message_text", preview)
                put("last_message_type", messageType)
                put("last_message_sender_id", uid)
                put("last_message_at", now)
                put("typing_uid", "")
                put("updated_at", now)
            }
            supabase.restPatch("conversations", listOf("id" to "eq.${conversation.id}"), convUpdate)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setTyping(conversationId: String, typing: Boolean): Result<Unit> {
        val uid = supabase.auth.currentUserId() ?: return Result.success(Unit)
        return try {
            val now = nowIso()
            val body = JSONObject().apply {
                put("typing_uid", if (typing) uid else "")
                put("typing_at", now)
                put("updated_at", now)
            }
            supabase.restPatch("conversations", listOf("id" to "eq.$conversationId"), body)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editMessage(conversationId: String, messageId: String, newText: String): Result<Unit> {
        val clean = newText.trim().take(4000)
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Message cannot be empty."))
        return try {
            requireUserId()
            val body = JSONObject().apply {
                put("text", clean)
                put("edited", true)
                put("updated_at", nowIso())
            }
            supabase.restPatch(
                "messages",
                listOf("id" to "eq.$messageId", "conversation_id" to "eq.$conversationId"),
                body
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun softDeleteMessage(conversationId: String, messageId: String): Result<Unit> {
        return try {
            requireUserId()
            val body = JSONObject().apply {
                put("text", "This message was deleted")
                put("deleted", true)
                put("media_url", JSONObject.NULL)
                put("updated_at", nowIso())
            }
            supabase.restPatch(
                "messages",
                listOf("id" to "eq.$messageId", "conversation_id" to "eq.$conversationId"),
                body
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reactToMessage(
        conversationId: String,
        message: ChatMessage,
        emoji: String
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val nextReactions = message.reactions.toMutableMap()
            if (nextReactions[uid] == emoji) nextReactions.remove(uid) else nextReactions[uid] = emoji
            val body = JSONObject().apply {
                put("reactions", mapToJsonObject(nextReactions))
                put("updated_at", nowIso())
            }
            supabase.restPatch(
                "messages",
                listOf("id" to "eq.${message.id}", "conversation_id" to "eq.$conversationId"),
                body
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleStarMessage(conversationId: String, message: ChatMessage): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val nextStarred = message.starredBy.toMutableList()
            if (uid in nextStarred) nextStarred.remove(uid) else nextStarred.add(uid)
            val body = JSONObject().apply {
                put("starred_by", listToJsonArray(nextStarred.distinct().take(50)))
                put("updated_at", nowIso())
            }
            supabase.restPatch(
                "messages",
                listOf("id" to "eq.${message.id}", "conversation_id" to "eq.$conversationId"),
                body
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markMessagesDeliveredAndRead(
        conversationId: String,
        messages: List<ChatMessage>,
        sendReadReceipts: Boolean
    ): Result<Unit> {
        val uid = supabase.auth.currentUserId() ?: return Result.success(Unit)
        val targets = messages.filter { msg ->
            msg.senderId != uid && (uid !in msg.deliveredTo || (sendReadReceipts && uid !in msg.readBy))
        }.take(20)
        if (targets.isEmpty()) return Result.success(Unit)
        return try {
            val now = nowIso()
            targets.forEach { msg ->
                val delivered = (msg.deliveredTo + uid).distinct().take(50)
                val body = JSONObject().apply {
                    put("delivered_to", listToJsonArray(delivered))
                    if (sendReadReceipts) {
                        put("read_by", listToJsonArray((msg.readBy + uid).distinct().take(50)))
                    }
                    put("updated_at", now)
                }
                supabase.restPatch(
                    "messages",
                    listOf("id" to "eq.${msg.id}", "conversation_id" to "eq.$conversationId"),
                    body
                )
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateChatPref(
        conversationId: String,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        muted: Boolean? = null,
        hidden: Boolean? = null,
        unreadCount: Long? = null
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val existing = supabase.restGet(
                table = "chat_prefs",
                queryParams = listOf(
                    "owner_id" to "eq.$uid",
                    "conversation_id" to "eq.$conversationId",
                    "limit" to "1"
                )
            )
            if (existing.length() > 0) {
                val updates = JSONObject().apply {
                    if (pinned != null) put("pinned", pinned)
                    if (archived != null) put("archived", archived)
                    if (muted != null) put("muted", muted)
                    if (hidden != null) put("hidden", hidden)
                    if (unreadCount != null) put("unread_count", unreadCount.coerceAtLeast(0L))
                    put("updated_at", nowIso())
                }
                supabase.restPatch(
                    "chat_prefs",
                    listOf("owner_id" to "eq.$uid", "conversation_id" to "eq.$conversationId"),
                    updates
                )
            } else {
                val payload = JSONObject().apply {
                    put("owner_id", uid)
                    put("conversation_id", conversationId)
                    put("pinned", pinned ?: false)
                    put("archived", archived ?: false)
                    put("muted", muted ?: false)
                    put("hidden", hidden ?: false)
                    put("unread_count", (unreadCount ?: 0L).coerceAtLeast(0L))
                    put("updated_at", nowIso())
                }
                supabase.restPost("chat_prefs", payload, upsert = true, onConflict = "owner_id,conversation_id")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseConversation(obj: JSONObject): Conversation =
        Conversation(
            id = obj.optString("id", ""),
            kind = obj.optString("kind", "direct"),
            memberUids = jsonArrayToStringList(obj.optJSONArray("member_uids")),
            adminUids = jsonArrayToStringList(obj.optJSONArray("admin_uids")),
            title = obj.optString("title", ""),
            description = obj.optString("description", ""),
            avatarBase64 = optNullableString(obj, "avatar_url"),
            memberNames = jsonObjectToStringMap(obj.optJSONObject("member_names")),
            memberUsernames = jsonObjectToStringMap(obj.optJSONObject("member_usernames")),
            lastMessageText = obj.optString("last_message_text", ""),
            lastMessageType = obj.optString("last_message_type", "text"),
            lastMessageSenderId = obj.optString("last_message_sender_id", ""),
            lastMessageAt = AppTimestamp.parseIso(optNullableString(obj, "last_message_at")),
            typingUid = optNullableString(obj, "typing_uid"),
            typingAt = AppTimestamp.parseIso(optNullableString(obj, "typing_at")),
            createdBy = obj.optString("created_by", ""),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )

    private fun parseChatPref(obj: JSONObject): ChatPref =
        ChatPref(
            id = obj.optString("conversation_id", ""),
            conversationId = obj.optString("conversation_id", ""),
            pinned = obj.optBoolean("pinned", false),
            archived = obj.optBoolean("archived", false),
            muted = obj.optBoolean("muted", false),
            hidden = obj.optBoolean("hidden", false),
            unreadCount = obj.optLong("unread_count", 0L),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )

    private fun parseMessage(obj: JSONObject): ChatMessage =
        ChatMessage(
            id = obj.optString("id", ""),
            conversationId = obj.optString("conversation_id", ""),
            senderId = obj.optString("sender_id", ""),
            senderName = obj.optString("sender_name", ""),
            memberUids = jsonArrayToStringList(obj.optJSONArray("member_uids")),
            messageType = obj.optString("message_type", "text"),
            text = obj.optString("text", ""),
            mediaBase64 = optNullableString(obj, "media_url"),
            mediaName = optNullableString(obj, "media_name"),
            mediaDurationSec = if (obj.isNull("media_duration_sec")) null else obj.optLong("media_duration_sec"),
            waveform = jsonArrayToLongList(obj.optJSONArray("waveform")),
            latitude = if (obj.isNull("latitude")) null else obj.optDouble("latitude"),
            longitude = if (obj.isNull("longitude")) null else obj.optDouble("longitude"),
            contactName = optNullableString(obj, "contact_name"),
            contactUsername = optNullableString(obj, "contact_username"),
            replyToId = optNullableString(obj, "reply_to_id"),
            replyToText = optNullableString(obj, "reply_to_text"),
            replyToSender = optNullableString(obj, "reply_to_sender"),
            forwarded = obj.optBoolean("forwarded", false),
            edited = obj.optBoolean("edited", false),
            deleted = obj.optBoolean("deleted", false),
            deliveredTo = jsonArrayToStringList(obj.optJSONArray("delivered_to")),
            readBy = jsonArrayToStringList(obj.optJSONArray("read_by")),
            starredBy = jsonArrayToStringList(obj.optJSONArray("starred_by")),
            reactions = jsonObjectToStringMap(obj.optJSONObject("reactions")),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )
}
