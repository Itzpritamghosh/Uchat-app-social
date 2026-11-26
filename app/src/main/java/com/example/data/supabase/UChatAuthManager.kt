package com.example.data.supabase

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private val Context.uchatSessionStore by preferencesDataStore(name = "uchat_session_v2")
private const val AUTH_TAG = "UChatAuth"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,24}$")

data class UChatSession(
    val userId: String,
    val username: String,
    val displayName: String,
    val sessionToken: String
)

/**
 * Keystore-independent authentication manager backed by Supabase Postgres RPC functions
 * (pgcrypto bcrypt password hashing + 256-bit SHA-256 hashed server sessions).
 * Works identically in the emulator and on any GitHub-built APK with zero SHA-1 or email-rate-limit issues.
 */
class UChatAuthManager(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val keyUserId = stringPreferencesKey("user_id")
    private val keyUsername = stringPreferencesKey("username")
    private val keyDisplayName = stringPreferencesKey("display_name")
    private val keySessionToken = stringPreferencesKey("session_token")

    private val fastPrefs by lazy {
        appContext.getSharedPreferences("uchat_session_fast", Context.MODE_PRIVATE)
    }

    private val _session = MutableStateFlow<UChatSession?>(readFastSession())
    val session: StateFlow<UChatSession?> = _session.asStateFlow()

    private val _initializing = MutableStateFlow(_session.value == null)
    val initializing: StateFlow<Boolean> = _initializing.asStateFlow()

    init {
        val fastRestored = _session.value
        if (fastRestored != null) {
            scope.launch { verifyRestoredSession(fastRestored.sessionToken) }
        } else {
            scope.launch {
                try {
                    val prefs = appContext.uchatSessionStore.data.first()
                    val uid = prefs[keyUserId].orEmpty()
                    val username = prefs[keyUsername].orEmpty()
                    val displayName = prefs[keyDisplayName].orEmpty()
                    val token = prefs[keySessionToken].orEmpty()
                    if (uid.isNotBlank() && token.isNotBlank()) {
                        val migrated = UChatSession(uid, username, displayName, token)
                        _session.value = migrated
                        writeFastSession(migrated)
                        scope.launch { verifyRestoredSession(token) }
                    }
                } catch (e: Exception) {
                    Log.w(AUTH_TAG, "Failed restoring local session: ${e.message}")
                } finally {
                    _initializing.value = false
                }
            }
        }
    }

    private fun readFastSession(): UChatSession? {
        return runCatching {
            val uid = fastPrefs.getString("user_id", "").orEmpty()
            val token = fastPrefs.getString("session_token", "").orEmpty()
            if (uid.isBlank() || token.isBlank()) return null
            val username = fastPrefs.getString("username", "").orEmpty()
            val displayName = fastPrefs.getString("display_name", username).orEmpty()
            UChatSession(uid, username, displayName, token)
        }.getOrNull()
    }

    private fun writeFastSession(session: UChatSession) {
        fastPrefs.edit()
            .putString("user_id", session.userId)
            .putString("username", session.username)
            .putString("display_name", session.displayName)
            .putString("session_token", session.sessionToken)
            .apply()
    }

    private suspend fun verifyRestoredSession(token: String) {
        if (!SupabaseConfig.isConfigured) return
        runCatching {
            val req = Request.Builder()
                .url("${SupabaseConfig.baseUrl}/rest/v1/rpc/uchat_validate_session")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("x-uchat-token", token)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().trim()
                    if (body == "null" || body == "\"\"") {
                        if (_session.value?.sessionToken == token) {
                            clearLocalSession()
                        }
                    }
                }
            }
        }
    }

    fun currentUserId(): String? = _session.value?.userId?.takeIf { it.isNotBlank() }

    fun currentSessionToken(): String? = _session.value?.sessionToken?.takeIf { it.isNotBlank() }

    fun validateUsernameFormat(raw: String): String? {
        val cleaned = raw.trim().lowercase().removePrefix("@")
        if (cleaned.length < 3) return "Username must be at least 3 characters."
        if (cleaned.length > 24) return "Username must be 24 characters or fewer."
        if (!USERNAME_REGEX.matches(cleaned)) return "Use only lowercase letters, numbers, and underscores."
        return null
    }

    suspend fun checkUsernameAvailable(rawUsername: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleaned = rawUsername.trim().lowercase().removePrefix("@")
        val formatErr = validateUsernameFormat(cleaned)
        if (formatErr != null) return@withContext Result.failure(IllegalArgumentException(formatErr))
        try {
            SupabaseConfig.requireConfigured()
            val payload = JSONObject().put("p_username", cleaned).toString()
            val req = Request.Builder()
                .url("${SupabaseConfig.baseUrl}/rest/v1/rpc/uchat_username_available")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty().trim()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException(extractRpcError(text, resp.code)))
                }
                Result.success(text.equals("true", ignoreCase = true))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(mapNetworkError(e)))
        }
    }

    suspend fun signUp(
        rawUsername: String,
        rawDisplayName: String,
        password: String
    ): Result<UChatSession> = withContext(Dispatchers.IO) {
        try {
            SupabaseConfig.requireConfigured()
            val username = rawUsername.trim().lowercase().removePrefix("@")
            val formatErr = validateUsernameFormat(username)
            if (formatErr != null) {
                return@withContext Result.failure(IllegalArgumentException(formatErr))
            }
            val displayName = rawDisplayName.trim().take(60)
            if (displayName.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter your display name."))
            }
            if (password.length < 6) {
                return@withContext Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
            }

            val payload = JSONObject()
                .put("p_username", username)
                .put("p_display_name", displayName)
                .put("p_password", password)
                .toString()

            val req = Request.Builder()
                .url("${SupabaseConfig.baseUrl}/rest/v1/rpc/uchat_signup")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException(extractRpcError(text, resp.code)))
                }
                val session = parseSession(JSONObject(text))
                    ?: return@withContext Result.failure(IllegalStateException("Invalid sign-up response from Supabase."))
                saveSession(session)
                Result.success(session)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(mapNetworkError(e)))
        }
    }

    suspend fun signIn(
        rawUsername: String,
        password: String
    ): Result<UChatSession> = withContext(Dispatchers.IO) {
        try {
            SupabaseConfig.requireConfigured()
            val username = rawUsername.trim().lowercase().removePrefix("@")
            if (username.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter your @username."))
            }
            if (password.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter your password."))
            }

            val payload = JSONObject()
                .put("p_username", username)
                .put("p_password", password)
                .toString()

            val req = Request.Builder()
                .url("${SupabaseConfig.baseUrl}/rest/v1/rpc/uchat_signin")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException(extractRpcError(text, resp.code)))
                }
                val session = parseSession(JSONObject(text))
                    ?: return@withContext Result.failure(IllegalStateException("Invalid sign-in response from Supabase."))
                saveSession(session)
                Result.success(session)
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(mapNetworkError(e)))
        }
    }

    suspend fun signOut(beforeSignOut: suspend () -> Unit = {}) {
        withContext(NonCancellable) {
            runCatching { beforeSignOut() }
            val token = _session.value?.sessionToken
            if (!token.isNullOrBlank() && SupabaseConfig.isConfigured) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val req = Request.Builder()
                            .url("${SupabaseConfig.baseUrl}/rest/v1/rpc/uchat_signout")
                            .addHeader("apikey", SupabaseConfig.anonKey)
                            .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                            .addHeader("x-uchat-token", token)
                            .addHeader("Content-Type", "application/json")
                            .post("{}".toRequestBody(JSON_MEDIA))
                            .build()
                        http.newCall(req).execute().close()
                    }
                }
            }
            clearLocalSession()
        }
    }

    suspend fun clearLocalSession() {
        _session.value = null
        fastPrefs.edit().clear().apply()
        appContext.uchatSessionStore.edit { it.clear() }
    }

    private suspend fun saveSession(session: UChatSession) {
        _session.value = session
        writeFastSession(session)
        appContext.uchatSessionStore.edit { prefs ->
            prefs[keyUserId] = session.userId
            prefs[keyUsername] = session.username
            prefs[keyDisplayName] = session.displayName
            prefs[keySessionToken] = session.sessionToken
        }
    }

    private fun parseSession(obj: JSONObject): UChatSession? {
        val uid = obj.optString("user_id", "")
        val token = obj.optString("session_token", "")
        if (uid.isBlank() || token.isBlank()) return null
        val username = obj.optString("username", "")
        val displayName = obj.optString("display_name", username)
        return UChatSession(uid, username, displayName, token)
    }

    private fun extractRpcError(body: String, code: Int): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val msg = parsed?.optString("message")
            ?.ifBlank { parsed.optString("details") }
            ?.ifBlank { parsed.optString("hint") }
            .orEmpty()
        val lower = msg.lowercase()
        return when {
            lower.contains("could not find the function") ||
                (lower.contains("function") && lower.contains("does not exist")) ||
                (lower.contains("relation") && lower.contains("does not exist")) ||
                lower.contains("could not find the table") ->
                "Supabase database needs setup. Copy and run supabase_schema.sql in your Supabase Dashboard → SQL Editor, then try again."
            code == 401 ->
                "Invalid Supabase API key (HTTP 401). Check SUPABASE_ANON_KEY in Secrets."
            msg.isNotBlank() -> msg
            else -> "Request failed (HTTP $code)"
        }
    }

    private fun mapNetworkError(e: Exception): String = when (e) {
        is UnknownHostException ->
            "Cannot reach Supabase (${SupabaseConfig.hostDisplay.ifBlank { "check SUPABASE_URL" }}). Check your internet connection."
        is SocketTimeoutException ->
            "Connection to Supabase timed out. Please try again."
        is ConnectException, is IOException ->
            "Network error connecting to Supabase: ${e.message ?: "please check internet connection"}."
        else -> e.message ?: "Authentication failed. Please try again."
    }
}
