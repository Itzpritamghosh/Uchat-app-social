package com.example.data.repository

import com.example.data.cache.LocalChatCache
import com.example.data.model.AppTimestamp
import com.example.data.model.Channel
import com.example.data.model.ChannelPost
import com.example.data.supabase.SupabaseClient
import com.example.data.supabase.SupabaseClient.Companion.jsonObjectToStringMap
import com.example.data.supabase.SupabaseClient.Companion.mapToJsonObject
import com.example.data.supabase.SupabaseClient.Companion.optNullableString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ChannelRepository(
    private val supabase: SupabaseClient,
    private val cache: LocalChatCache? = null
) {
    private fun requireUserId(): String = supabase.requireUserId()

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    fun observePublicChannels(): Flow<List<Channel>> =
        supabase.observeTable(
            tables = setOf("channels"),
            fallbackPollMs = 12_000L,
            initialCached = { supabase.currentUserId()?.let { cache?.getChannels(it) } }
        ) {
            val rows = supabase.restGet(
                table = "channels",
                queryParams = listOf("discoverable" to "eq.true", "order" to "created_at.desc")
            )
            List(rows.length()) { idx -> parseChannel(rows.getJSONObject(idx)) }
                .sortedByDescending { it.lastPostAt ?: it.createdAt ?: AppTimestamp(0L) }
                .also { list ->
                    supabase.currentUserId()?.let { cache?.putChannels(it, list) }
                }
        }

    fun observeFollowedChannels(userId: String): Flow<Set<String>> =
        supabase.observeTable(setOf("followed_channels"), fallbackPollMs = 15_000L) {
            val rows = supabase.restGet(
                table = "followed_channels",
                queryParams = listOf("owner_id" to "eq.$userId")
            )
            buildSet {
                for (i in 0 until rows.length()) {
                    val cid = rows.getJSONObject(i).optString("channel_id", "")
                    if (cid.isNotBlank()) add(cid)
                }
            }
        }

    fun observeChannelPosts(channelId: String): Flow<List<ChannelPost>> =
        supabase.observeTable(setOf("channel_posts"), fallbackPollMs = 6_000L) {
            val rows = supabase.restGet(
                table = "channel_posts",
                queryParams = listOf(
                    "channel_id" to "eq.$channelId",
                    "discoverable" to "eq.true",
                    "order" to "created_at.desc"
                )
            )
            List(rows.length()) { idx -> parsePost(rows.getJSONObject(idx)) }
        }

    suspend fun createChannel(
        name: String,
        description: String,
        ownerName: String,
        avatarBase64: String?
    ): Result<String> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val cleanName = name.trim().take(80)
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Channel name is required."))
        return try {
            val storedAvatar = supabase.uploadBase64ToStorageOrFallback(
                ownerUid = uid,
                folder = "channels",
                mediaValue = avatarBase64,
                extension = "jpg",
                contentType = "image/jpeg"
            )
            val now = nowIso()
            val channelPayload = JSONObject().apply {
                put("name", cleanName)
                put("description", description.trim().take(300))
                put("owner_id", uid)
                put("owner_name", ownerName.trim().take(60).ifBlank { "UChat Creator" })
                if (!storedAvatar.isNullOrBlank()) put("avatar_url", storedAvatar)
                put("follower_count", 1L)
                put("discoverable", true)
                put("last_post_text", "")
                put("updated_at", now)
            }
            val created = supabase.restPost("channels", channelPayload)
            val channelId = if (created.length() > 0) created.getJSONObject(0).optString("id", "") else ""
            if (channelId.isBlank()) {
                return Result.failure(IllegalStateException("Failed to create channel."))
            }
            val followPayload = JSONObject().apply {
                put("owner_id", uid)
                put("channel_id", channelId)
                put("channel_name", cleanName)
            }
            supabase.restPost("followed_channels", followPayload, upsert = true, onConflict = "owner_id,channel_id")
            Result.success(channelId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleFollowChannel(channel: Channel, currentlyFollowing: Boolean): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val nextCount = (channel.followerCount + if (currentlyFollowing) -1L else 1L).coerceAtLeast(0L)
            val chanUpdate = JSONObject().apply {
                put("follower_count", nextCount)
                put("updated_at", nowIso())
            }
            supabase.restPatch("channels", listOf("id" to "eq.${channel.id}"), chanUpdate)

            if (currentlyFollowing) {
                supabase.restDelete(
                    "followed_channels",
                    listOf("owner_id" to "eq.$uid", "channel_id" to "eq.${channel.id}")
                )
            } else {
                val followPayload = JSONObject().apply {
                    put("owner_id", uid)
                    put("channel_id", channel.id)
                    put("channel_name", channel.name.take(80))
                }
                supabase.restPost("followed_channels", followPayload, upsert = true, onConflict = "owner_id,channel_id")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun publishPost(
        channel: Channel,
        authorName: String,
        text: String,
        imageBase64: String?
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        if (channel.ownerId != uid) {
            return Result.failure(IllegalAccessException("Only the channel owner can publish updates."))
        }
        val cleanText = text.trim().take(2000)
        if (cleanText.isBlank() && imageBase64.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Write a post or attach an image."))
        }
        return try {
            val storedImage = supabase.uploadBase64ToStorageOrFallback(
                ownerUid = uid,
                folder = "channel_posts",
                mediaValue = imageBase64,
                extension = "jpg",
                contentType = "image/jpeg"
            )
            val postType = if (!storedImage.isNullOrBlank()) "image" else "text"
            val preview = if (cleanText.isNotBlank()) cleanText.take(200) else "📷 Image broadcast"
            val now = nowIso()

            val postPayload = JSONObject().apply {
                put("channel_id", channel.id)
                put("author_id", uid)
                put("author_name", authorName.trim().take(60).ifBlank { "Channel Admin" })
                put("post_type", postType)
                put("text", cleanText)
                if (!storedImage.isNullOrBlank()) put("media_url", storedImage)
                put("reactions", JSONObject())
                put("discoverable", true)
                put("updated_at", now)
            }
            supabase.restPost("channel_posts", postPayload)

            val chanUpdate = JSONObject().apply {
                put("last_post_text", preview)
                put("last_post_at", now)
                put("updated_at", now)
            }
            supabase.restPatch("channels", listOf("id" to "eq.${channel.id}"), chanUpdate)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun reactToPost(channelId: String, post: ChannelPost, emoji: String): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val next = post.reactions.toMutableMap()
            if (next[uid] == emoji) next.remove(uid) else next[uid] = emoji
            val body = JSONObject().apply {
                put("reactions", mapToJsonObject(next))
                put("updated_at", nowIso())
            }
            supabase.restPatch(
                "channel_posts",
                listOf("id" to "eq.${post.id}", "channel_id" to "eq.$channelId"),
                body
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseChannel(obj: JSONObject): Channel =
        Channel(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            description = obj.optString("description", ""),
            ownerId = obj.optString("owner_id", ""),
            ownerName = obj.optString("owner_name", ""),
            avatarBase64 = optNullableString(obj, "avatar_url"),
            followerCount = obj.optLong("follower_count", 1L),
            discoverable = obj.optBoolean("discoverable", true),
            lastPostText = obj.optString("last_post_text", ""),
            lastPostAt = AppTimestamp.parseIso(optNullableString(obj, "last_post_at")),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )

    private fun parsePost(obj: JSONObject): ChannelPost =
        ChannelPost(
            id = obj.optString("id", ""),
            channelId = obj.optString("channel_id", ""),
            authorId = obj.optString("author_id", ""),
            authorName = obj.optString("author_name", ""),
            postType = obj.optString("post_type", "text"),
            text = obj.optString("text", ""),
            mediaBase64 = optNullableString(obj, "media_url"),
            reactions = jsonObjectToStringMap(obj.optJSONObject("reactions")),
            discoverable = obj.optBoolean("discoverable", true),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )
}
