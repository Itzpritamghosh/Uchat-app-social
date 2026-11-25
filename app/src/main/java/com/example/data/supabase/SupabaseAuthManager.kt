package com.example.data.supabase

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Context.supabaseAuthStore by preferencesDataStore(name = "uchat_supabase_auth")
private const val AUTH_LOG_TAG = "SupabaseAuth"
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

data class SupabaseSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long
)

object SupabaseConfig {
    val baseUrl: String
        get() = BuildConfig.SUPABASE_URL.trim().trimEnd('/')

    val anonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY.trim()

    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") &&
            !baseUrl.contains("YOUR_PROJECT_REF", ignoreCase = true) &&
            anonKey.isNotBlank() &&
            !anonKey.contains("YOUR_SUPABASE_ANON_KEY", ignoreCase = true)

    fun requireConfigured() {
        if (!isConfigured) {
            throw IllegalStateException(
                "Supabase is not configured. Add SUPABASE_URL and SUPABASE_ANON_KEY in Secrets and run supabase_schema.sql in your Supabase SQL Editor."
            )
        }
    }
}

class SupabaseAuthManager(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val keyUserId = stringPreferencesKey("user_id")
    private val keyEmail = stringPreferencesKey("email")
    private val keyAccessToken = stringPreferencesKey("access_token")
    private val keyRefreshToken = stringPreferencesKey("refresh_token")
    private val keyExpiresAt = longPreferencesKey("expires_at")

    private val _session = MutableStateFlow<SupabaseSession?>(null)
    val session: StateFlow<SupabaseSession?> = _session.asStateFlow()

    private val _initializing = MutableStateFlow(true)
    val initializing: StateFlow<Boolean> = _initializing.asStateFlow()

    init {
        scope.launch {
            try {
                val prefs = appContext.supabaseAuthStore.data.first()
                val uid = prefs[keyUserId]
                val access = prefs[keyAccessToken]
                val refresh = prefs[keyRefreshToken]
                val email = prefs[keyEmail].orEmpty()
                val expiresAt = prefs[keyExpiresAt] ?: 0L
                if (!uid.isNullOrBlank() && !access.isNullOrBlank() && !refresh.isNullOrBlank()) {
                    _session.value = SupabaseSession(uid, email, access, refresh, expiresAt)
                    if (SupabaseConfig.isConfigured) {
                        getValidAccessToken()
                    }
                }
            } catch (e: Exception) {
                Log.w(AUTH_LOG_TAG, "Failed restoring saved session: ${e.message}")
            } finally {
                _initializing.value = false
            }
        }
    }

    fun currentUserId(): String? = _session.value?.userId

    suspend fun signUp(rawEmail: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            SupabaseConfig.requireConfigured()
            val email = rawEmail.trim().lowercase()
            if (!EMAIL_REGEX.matches(email)) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a valid email address."))
            }
            if (password.length < 6) {
                return@withContext Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
            }

            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()
                .toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("${SupabaseConfig.baseUrl}/auth/v1/signup")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException(extractAuthError(text, resp.code)))
                }
                val json = JSONObject(text)
                val parsed = parseSessionFromJson(json, email)
                if (parsed != null) {
                    saveSession(parsed)
                    return@withContext Result.success("Account created")
                }
                // Email confirmation may be enabled in Supabase project settings
                Result.failure(
                    IllegalStateException(
                        "Account created! Check your email to confirm your account, or disable 'Confirm email' in Supabase Auth settings for instant sign-in."
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(rawEmail: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseConfig.requireConfigured()
            val email = rawEmail.trim().lowercase()
            if (!EMAIL_REGEX.matches(email)) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a valid email address."))
            }
            if (password.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter your password."))
            }

            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()
                .toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("${SupabaseConfig.baseUrl}/auth/v1/token?grant_type=password")
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException(extractAuthError(text, resp.code)))
                }
                val parsed = parseSessionFromJson(JSONObject(text), email)
                    ?: return@withContext Result.failure(IllegalStateException("Invalid auth response from Supabase."))
                saveSession(parsed)
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getValidAccessToken(): String? = refreshMutex.withLock {
        val current = _session.value ?: return@withLock null
        val nowSec = System.currentTimeMillis() / 1000L
        if (current.expiresAtEpochSec > nowSec + 60L) {
            return@withLock current.accessToken
        }
        if (!SupabaseConfig.isConfigured) return@withLock current.accessToken
        return@withLock withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("refresh_token", current.refreshToken)
                    .toString()
                    .toRequestBody(JSON_MEDIA)
                val req = Request.Builder()
                    .url("${SupabaseConfig.baseUrl}/auth/v1/token?grant_type=refresh_token")
                    .addHeader("apikey", SupabaseConfig.anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        if (resp.code == 400 || resp.code == 401) {
                            clearLocalSession()
                            return@withContext null
                        }
                        return@withContext current.accessToken
                    }
                    val refreshed = parseSessionFromJson(JSONObject(text), current.email)
                    if (refreshed != null) {
                        saveSession(refreshed)
                        refreshed.accessToken
                    } else {
                        current.accessToken
                    }
                }
            } catch (e: Exception) {
                Log.w(AUTH_LOG_TAG, "Session refresh warning: ${e.message}")
                current.accessToken
            }
        }
    }

    suspend fun signOut(beforeSignOut: suspend () -> Unit = {}) {
        runCatching { beforeSignOut() }
        val current = _session.value
        withContext(Dispatchers.IO) {
            if (current != null && SupabaseConfig.isConfigured) {
                runCatching {
                    val req = Request.Builder()
                        .url("${SupabaseConfig.baseUrl}/auth/v1/logout")
                        .addHeader("apikey", SupabaseConfig.anonKey)
                        .addHeader("Authorization", "Bearer ${current.accessToken}")
                        .post("{}".toRequestBody(JSON_MEDIA))
                        .build()
                    http.newCall(req).execute().close()
                }
            }
        }
        clearLocalSession()
    }

    private suspend fun saveSession(session: SupabaseSession) {
        _session.value = session
        appContext.supabaseAuthStore.edit { prefs ->
            prefs[keyUserId] = session.userId
            prefs[keyEmail] = session.email
            prefs[keyAccessToken] = session.accessToken
            prefs[keyRefreshToken] = session.refreshToken
            prefs[keyExpiresAt] = session.expiresAtEpochSec
        }
    }

    private suspend fun clearLocalSession() {
        _session.value = null
        appContext.supabaseAuthStore.edit { it.clear() }
    }

    private fun parseSessionFromJson(json: JSONObject, fallbackEmail: String): SupabaseSession? {
        val access = json.optString("access_token", "")
        val refresh = json.optString("refresh_token", "")
        val userObj = json.optJSONObject("user")
        val uid = userObj?.optString("id", "").orEmpty()
        if (access.isBlank() || refresh.isBlank() || uid.isBlank()) return null
        val email = userObj?.optString("email", fallbackEmail).orEmpty().ifBlank { fallbackEmail }
        val expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0L }
            ?: ((System.currentTimeMillis() / 1000L) + json.optLong("expires_in", 3600L))
        return SupabaseSession(uid, email, access, refresh, expiresAt)
    }

    private fun extractAuthError(body: String, code: Int): String {
        return try {
            val obj = JSONObject(body)
            obj.optString("error_description")
                .ifBlank { obj.optString("msg") }
                .ifBlank { obj.optString("message") }
                .ifBlank { "Authentication failed (HTTP $code)" }
        } catch (_: Exception) {
            "Authentication failed (HTTP $code)"
        }
    }
}
