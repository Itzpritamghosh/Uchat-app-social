package com.example.data.repository

import com.example.data.model.AppTimestamp
import com.example.data.model.BlockedUser
import com.example.data.model.ContactEntry
import com.example.data.model.UserProfile
import com.example.data.model.UserSettings
import com.example.data.supabase.SupabaseClient
import com.example.data.supabase.SupabaseClient.Companion.optNullableString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,24}$")
private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{4,128}$")

class ProfileRepository(
    private val supabase: SupabaseClient
) {
    private fun requireUserId(): String = supabase.requireUserId()

    private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    fun validateUsernameFormat(raw: String): String? {
        val cleaned = raw.trim().lowercase()
        if (cleaned.length < 3) return "Username must be at least 3 characters."
        if (cleaned.length > 24) return "Username must be 24 characters or fewer."
        if (!USERNAME_REGEX.matches(cleaned)) return "Use only lowercase letters, numbers, and underscores."
        return null
    }

    suspend fun isUsernameAvailable(rawUsername: String): Result<Boolean> {
        val username = rawUsername.trim().lowercase()
        val formatErr = validateUsernameFormat(username)
        if (formatErr != null) return Result.failure(IllegalArgumentException(formatErr))
        return try {
            val uid = requireUserId()
            val rows = supabase.restGet(
                table = "profiles",
                queryParams = listOf(
                    "select" to "id",
                    "username_lower" to "eq.$username",
                    "limit" to "1"
                )
            )
            if (rows.length() == 0) {
                Result.success(true)
            } else {
                val ownerId = rows.getJSONObject(0).optString("id", "")
                Result.success(ownerId == uid)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeMyProfile(userId: String): Flow<UserProfile?> =
        supabase.observeTable(setOf("profiles"), fallbackPollMs = 12_000L) {
            val rows = supabase.restGet(
                table = "profiles",
                queryParams = listOf("id" to "eq.$userId", "limit" to "1")
            )
            if (rows.length() == 0) null else parseProfile(rows.getJSONObject(0))
        }

    fun observeDiscoverableProfiles(): Flow<List<UserProfile>> =
        supabase.observeTable(setOf("profiles"), fallbackPollMs = 15_000L) {
            val rows = supabase.restGet(
                table = "profiles",
                queryParams = listOf(
                    "discoverable" to "eq.true",
                    "order" to "display_name_lower.asc",
                    "limit" to "100"
                )
            )
            List(rows.length()) { idx -> parseProfile(rows.getJSONObject(idx)) }
        }

    fun observeProfilesByIds(userIds: List<String>): Flow<Map<String, UserProfile>> {
        val uniqueIds = userIds.filter { SAFE_ID_REGEX.matches(it) }.distinct().take(30)
        if (uniqueIds.isEmpty()) return flowOf(emptyMap())
        return supabase.observeTable(setOf("profiles"), fallbackPollMs = 12_000L) {
            val rows = supabase.restGet(
                table = "profiles",
                queryParams = listOf(
                    "id" to "in.(${uniqueIds.joinToString(",")})"
                )
            )
            buildMap {
                for (i in 0 until rows.length()) {
                    val profile = parseProfile(rows.getJSONObject(i))
                    if (profile.userId.isNotBlank()) put(profile.userId, profile)
                }
            }
        }
    }

    suspend fun createInitialProfile(
        rawUsername: String,
        displayName: String,
        about: String,
        avatarBase64: String?
    ): Result<Unit> {
        val username = rawUsername.trim().lowercase()
        val formatErr = validateUsernameFormat(username)
        if (formatErr != null) return Result.failure(IllegalArgumentException(formatErr))
        val cleanName = displayName.trim().take(60)
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Display name is required."))
        val cleanAbout = about.trim().take(160).ifBlank { "Hey there! I am using UChat." }
        if (avatarBase64 != null && avatarBase64.length > 2_000_000) {
            return Result.failure(IllegalArgumentException("Profile photo is too large."))
        }

        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val avail = isUsernameAvailable(username).getOrElse { return Result.failure(it) }
            if (!avail) {
                return Result.failure(IllegalStateException("Username @$username is already taken."))
            }
            val storedAvatar = supabase.uploadBase64ToStorageOrFallback(
                ownerUid = uid,
                folder = "avatars",
                mediaValue = avatarBase64,
                extension = "jpg",
                contentType = "image/jpeg"
            )
            val now = nowIso()
            val profileBody = JSONObject().apply {
                put("id", uid)
                put("username", username)
                put("username_lower", username)
                put("display_name", cleanName)
                put("display_name_lower", cleanName.lowercase())
                put("about", cleanAbout)
                if (!storedAvatar.isNullOrBlank()) put("avatar_url", storedAvatar)
                put("online", true)
                put("last_seen", now)
                put("show_last_seen", true)
                put("show_online", true)
                put("show_about", true)
                put("read_receipts", true)
                put("discoverable", true)
                put("updated_at", now)
            }
            supabase.restPost("profiles", profileBody, upsert = true, onConflict = "id")

            val settingsBody = JSONObject().apply {
                put("user_id", uid)
                put("accent_hex", "#25D366")
                put("wallpaper_style", "doodle")
                put("font_scale", 1.0)
                put("msg_notif", true)
                put("group_notif", true)
                put("call_notif", true)
                put("sound_enabled", true)
                put("ringtone_name", "Default Ringtone")
                put("updated_at", now)
            }
            supabase.restPost("user_settings", settingsBody, upsert = true, onConflict = "user_id")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProfile(
        currentUsername: String,
        newRawUsername: String,
        displayName: String,
        about: String,
        avatarBase64: String?
    ): Result<Unit> {
        val newUsername = newRawUsername.trim().lowercase()
        val formatErr = validateUsernameFormat(newUsername)
        if (formatErr != null) return Result.failure(IllegalArgumentException(formatErr))
        val cleanName = displayName.trim().take(60)
        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("Display name cannot be empty."))
        val cleanAbout = about.trim().take(160)
        if (avatarBase64 != null && avatarBase64.length > 2_000_000) {
            return Result.failure(IllegalArgumentException("Profile photo is too large."))
        }

        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            if (newUsername != currentUsername) {
                val avail = isUsernameAvailable(newUsername).getOrElse { return Result.failure(it) }
                if (!avail) {
                    return Result.failure(IllegalStateException("Username @$newUsername is already taken."))
                }
            }
            val storedAvatar = if (avatarBase64 != null) {
                supabase.uploadBase64ToStorageOrFallback(
                    ownerUid = uid,
                    folder = "avatars",
                    mediaValue = avatarBase64,
                    extension = "jpg",
                    contentType = "image/jpeg"
                )
            } else {
                null
            }
            val updates = JSONObject().apply {
                put("username", newUsername)
                put("username_lower", newUsername)
                put("display_name", cleanName)
                put("display_name_lower", cleanName.lowercase())
                put("about", cleanAbout)
                if (storedAvatar != null) put("avatar_url", storedAvatar)
                put("updated_at", nowIso())
            }
            supabase.restPatch("profiles", listOf("id" to "eq.$uid"), updates)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePrivacy(
        showLastSeen: Boolean,
        showOnline: Boolean,
        showAbout: Boolean,
        readReceipts: Boolean,
        discoverable: Boolean
    ): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val updates = JSONObject().apply {
                put("show_last_seen", showLastSeen)
                put("show_online", showOnline)
                put("show_about", showAbout)
                put("read_receipts", readReceipts)
                put("discoverable", discoverable)
                put("updated_at", nowIso())
            }
            supabase.restPatch("profiles", listOf("id" to "eq.$uid"), updates)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setOnlineStatus(online: Boolean): Result<Unit> {
        val uid = supabase.currentUserId() ?: return Result.success(Unit)
        return try {
            val now = nowIso()
            val updates = JSONObject().apply {
                put("online", online)
                put("last_seen", now)
                put("updated_at", now)
            }
            supabase.restPatch("profiles", listOf("id" to "eq.$uid"), updates)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeSettings(userId: String): Flow<UserSettings> =
        supabase.observeTable(setOf("user_settings"), fallbackPollMs = 20_000L) {
            val rows = supabase.restGet(
                table = "user_settings",
                queryParams = listOf("user_id" to "eq.$userId", "limit" to "1")
            )
            if (rows.length() == 0) UserSettings() else parseSettings(rows.getJSONObject(0))
        }

    suspend fun updateSettings(settings: UserSettings): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val cleanScale = settings.fontScale.coerceIn(0.85, 1.35)
            val cleanRingtoneName = settings.ringtoneName.trim().take(80).ifBlank { "Default Ringtone" }
            val body = JSONObject().apply {
                put("user_id", uid)
                put("accent_hex", settings.accentHex.take(16))
                put("wallpaper_style", settings.wallpaperStyle.take(24))
                put("font_scale", cleanScale)
                put("msg_notif", settings.msgNotif)
                put("group_notif", settings.groupNotif)
                put("call_notif", settings.callNotif)
                put("sound_enabled", settings.soundEnabled)
                put("ringtone_name", cleanRingtoneName)
                if (settings.ringtoneUri != null) put("ringtone_uri", settings.ringtoneUri.take(500))
                put("updated_at", nowIso())
            }
            supabase.restPost("user_settings", body, upsert = true, onConflict = "user_id")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeContacts(userId: String): Flow<List<ContactEntry>> =
        supabase.observeTable(setOf("contacts"), fallbackPollMs = 15_000L) {
            val rows = supabase.restGet(
                table = "contacts",
                queryParams = listOf("owner_id" to "eq.$userId", "order" to "display_name.asc")
            )
            List(rows.length()) { idx -> parseContact(rows.getJSONObject(idx)) }
        }

    suspend fun addContact(profile: UserProfile): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val payload = JSONObject().apply {
                put("owner_id", uid)
                put("contact_uid", profile.userId)
                put("username", profile.username)
                put("display_name", profile.displayName.take(60))
                if (!profile.avatarBase64.isNullOrBlank()) put("avatar_url", profile.avatarBase64)
            }
            supabase.restPost("contacts", payload, upsert = true, onConflict = "owner_id,contact_uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeBlockedUsers(userId: String): Flow<List<BlockedUser>> =
        supabase.observeTable(setOf("blocked_users"), fallbackPollMs = 15_000L) {
            val rows = supabase.restGet(
                table = "blocked_users",
                queryParams = listOf("owner_id" to "eq.$userId", "order" to "created_at.desc")
            )
            List(rows.length()) { idx -> parseBlocked(rows.getJSONObject(idx)) }
        }

    suspend fun blockUser(profile: UserProfile): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            val payload = JSONObject().apply {
                put("owner_id", uid)
                put("blocked_uid", profile.userId)
                put("username", profile.username)
                put("display_name", profile.displayName.take(60))
            }
            supabase.restPost("blocked_users", payload, upsert = true, onConflict = "owner_id,blocked_uid")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unblockUser(blockedUid: String): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        return try {
            supabase.restDelete(
                table = "blocked_users",
                queryParams = listOf("owner_id" to "eq.$uid", "blocked_uid" to "eq.$blockedUid")
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitReport(category: String, details: String, targetUid: String? = null): Result<Unit> {
        val uid = try { requireUserId() } catch (e: Exception) { return Result.failure(e) }
        val cleanDetails = details.trim().take(1000)
        if (cleanDetails.isBlank()) return Result.failure(IllegalArgumentException("Please describe the issue."))
        return try {
            val payload = JSONObject().apply {
                put("reporter_id", uid)
                if (!targetUid.isNullOrBlank()) put("target_uid", targetUid.take(128))
                put("category", category.trim().take(40).ifBlank { "general" })
                put("details", cleanDetails)
            }
            supabase.restPost("reports", payload)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseProfile(obj: JSONObject): UserProfile {
        val uid = obj.optString("id", "")
        return UserProfile(
            id = uid,
            userId = uid,
            username = obj.optString("username", ""),
            usernameLower = obj.optString("username_lower", ""),
            displayName = obj.optString("display_name", ""),
            displayNameLower = obj.optString("display_name_lower", ""),
            about = obj.optString("about", "Hey there! I am using UChat."),
            avatarBase64 = optNullableString(obj, "avatar_url"),
            online = obj.optBoolean("online", false),
            lastSeen = AppTimestamp.parseIso(optNullableString(obj, "last_seen")),
            showLastSeen = obj.optBoolean("show_last_seen", true),
            showOnline = obj.optBoolean("show_online", true),
            showAbout = obj.optBoolean("show_about", true),
            readReceipts = obj.optBoolean("read_receipts", true),
            discoverable = obj.optBoolean("discoverable", true),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )
    }

    private fun parseSettings(obj: JSONObject): UserSettings =
        UserSettings(
            id = obj.optString("user_id", ""),
            accentHex = obj.optString("accent_hex", "#25D366"),
            wallpaperStyle = obj.optString("wallpaper_style", "doodle"),
            fontScale = obj.optDouble("font_scale", 1.0),
            msgNotif = obj.optBoolean("msg_notif", true),
            groupNotif = obj.optBoolean("group_notif", true),
            callNotif = obj.optBoolean("call_notif", true),
            soundEnabled = obj.optBoolean("sound_enabled", true),
            ringtoneName = obj.optString("ringtone_name", "Default Ringtone"),
            ringtoneUri = optNullableString(obj, "ringtone_uri"),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at")),
            updatedAt = AppTimestamp.parseIso(optNullableString(obj, "updated_at"))
        )

    private fun parseContact(obj: JSONObject): ContactEntry =
        ContactEntry(
            id = obj.optString("contact_uid", ""),
            contactUid = obj.optString("contact_uid", ""),
            username = obj.optString("username", ""),
            displayName = obj.optString("display_name", ""),
            avatarBase64 = optNullableString(obj, "avatar_url"),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at"))
        )

    private fun parseBlocked(obj: JSONObject): BlockedUser =
        BlockedUser(
            id = obj.optString("blocked_uid", ""),
            blockedUid = obj.optString("blocked_uid", ""),
            username = obj.optString("username", ""),
            displayName = obj.optString("display_name", ""),
            createdAt = AppTimestamp.parseIso(optNullableString(obj, "created_at"))
        )
}
