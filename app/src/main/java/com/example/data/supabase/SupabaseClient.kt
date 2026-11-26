package com.example.data.supabase

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val SB_TAG = "SupabaseClient"
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MEDIA_BUCKET = "uchat-media"
private const val REALTIME_TOPIC = "realtime:uchat-sync"

object SupabaseConfig {
    private fun sanitize(raw: String): String =
        raw.trim().trim('"', '\'').trim()

    val baseUrl: String
        get() {
            val cleaned = sanitize(BuildConfig.SUPABASE_URL).trimEnd('/')
            if (cleaned.isBlank()) return ""
            return when {
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
                cleaned.startsWith("http://", ignoreCase = true) -> cleaned
                cleaned.contains(".supabase.") -> "https://$cleaned"
                else -> cleaned
            }
        }

    val anonKey: String
        get() = sanitize(BuildConfig.SUPABASE_ANON_KEY)

    val hostDisplay: String
        get() = runCatching { URI(baseUrl).host.orEmpty() }.getOrDefault("")

    val isConfigured: Boolean
        get() = (baseUrl.startsWith("https://", ignoreCase = true) || baseUrl.startsWith("http://", ignoreCase = true)) &&
            !baseUrl.contains("YOUR_PROJECT_REF", ignoreCase = true) &&
            anonKey.isNotBlank() &&
            !anonKey.contains("YOUR_SUPABASE_ANON_KEY", ignoreCase = true)

    fun requireConfigured() {
        if (!isConfigured) {
            throw IllegalStateException(
                "Supabase is not configured in this build. Add SUPABASE_URL and SUPABASE_ANON_KEY in AI Studio Secrets (or GitHub Actions Secrets) and rebuild."
            )
        }
    }
}

/**
 * Supabase client for real-time messaging, Postgres data sync, WebRTC signaling, and media storage.
 * Authenticates every Postgres request with a cryptographically random server-verified session token
 * via the x-uchat-token header enforced by Postgres Row Level Security.
 */
class SupabaseClient(
    val auth: UChatAuthManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refCounter = AtomicInteger(1)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val _tableChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    @Volatile
    private var realtimeSocket: WebSocket? = null

    @Volatile
    private var realtimeStarted = false

    fun currentUserId(): String? = auth.currentUserId()

    fun requireUserId(): String =
        currentUserId()
            ?: throw IllegalStateException("Please sign in to your UChat account first.")

    private fun requireSessionToken(): String =
        auth.currentSessionToken()
            ?: throw IllegalStateException("Please sign in to your UChat account first.")

    fun notifyLocalChange(vararg tables: String) {
        tables.forEach { table ->
            _tableChanges.tryEmit(table)
            broadcastTableChange(table)
        }
    }

    private fun broadcastTableChange(table: String) {
        val ws = realtimeSocket ?: return
        runCatching {
            val frame = JSONObject()
                .put("topic", REALTIME_TOPIC)
                .put("event", "broadcast")
                .put(
                    "payload",
                    JSONObject()
                        .put("type", "broadcast")
                        .put("event", "table_changed")
                        .put("payload", JSONObject().put("table", table))
                )
                .put("ref", refCounter.getAndIncrement().toString())
            ws.send(frame.toString())
        }
    }

    fun disconnectRealtime() {
        runCatching { realtimeSocket?.close(1000, "Session changed") }
        realtimeSocket = null
    }

    fun ensureRealtimeConnected() {
        if (realtimeStarted || !SupabaseConfig.isConfigured) return
        synchronized(this) {
            if (realtimeStarted) return
            realtimeStarted = true
        }
        scope.launch {
            var backoffMs = 1500L
            while (isActive) {
                if (currentUserId() == null || !SupabaseConfig.isConfigured) {
                    delay(2500L)
                    continue
                }
                val connected = connectRealtimeOnce()
                backoffMs = if (connected) 1500L else (backoffMs * 2L).coerceAtMost(20_000L)
                delay(backoffMs)
            }
        }
    }

    private suspend fun connectRealtimeOnce(): Boolean {
        val base = SupabaseConfig.baseUrl
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> return false
        }
        val encodedKey = URLEncoder.encode(SupabaseConfig.anonKey, "UTF-8")
        val wsUrl = "$wsBase/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0"

        var opened = false
        val closedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                realtimeSocket = webSocket
                val joinPayload = JSONObject().apply {
                    put("config", JSONObject().apply {
                        put("broadcast", JSONObject().put("self", false).put("ack", false))
                        put("presence", JSONObject().put("key", ""))
                        put(
                            "postgres_changes",
                            JSONArray().put(
                                JSONObject()
                                    .put("event", "*")
                                    .put("schema", "public")
                            )
                        )
                    })
                    put("access_token", SupabaseConfig.anonKey)
                }
                val joinMsg = JSONObject()
                    .put("topic", REALTIME_TOPIC)
                    .put("event", "phx_join")
                    .put("payload", joinPayload)
                    .put("ref", refCounter.getAndIncrement().toString())
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val event = msg.optString("event", "")
                    when (event) {
                        "broadcast" -> {
                            val outer = msg.optJSONObject("payload")
                            val inner = outer?.optJSONObject("payload")
                            val table = inner?.optString("table", "").orEmpty()
                            _tableChanges.tryEmit(table.ifBlank { "*" })
                        }
                        "postgres_changes" -> {
                            val data = msg.optJSONObject("payload")?.optJSONObject("data")
                            val table = data?.optString("table", "").orEmpty()
                            _tableChanges.tryEmit(table.ifBlank { "*" })
                        }
                        "INSERT", "UPDATE", "DELETE" -> {
                            val table = msg.optJSONObject("payload")?.optString("table", "").orEmpty()
                            _tableChanges.tryEmit(table.ifBlank { "*" })
                        }
                    }
                } catch (_: Exception) {
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closedSignal.complete(Unit)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                realtimeSocket = null
                closedSignal.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(SB_TAG, "Realtime socket disconnected: ${t.message}")
                realtimeSocket = null
                closedSignal.complete(Unit)
            }
        }

        val request = Request.Builder().url(wsUrl).build()
        val ws = httpClient.newWebSocket(request, listener)
        val heartbeatJob = scope.launch {
            while (isActive && !closedSignal.isCompleted) {
                delay(25_000L)
                val hb = JSONObject()
                    .put("topic", "phoenix")
                    .put("event", "heartbeat")
                    .put("payload", JSONObject())
                    .put("ref", refCounter.getAndIncrement().toString())
                ws.send(hb.toString())
            }
        }
        closedSignal.await()
        heartbeatJob.cancel()
        return opened
    }

    fun <T> observeTable(
        tables: Set<String>,
        fallbackPollMs: Long = 10_000L,
        fetcher: suspend () -> T
    ): Flow<T> = channelFlow {
        ensureRealtimeConnected()
        val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val collectorJob = launch {
            _tableChanges
                .filter { changed -> changed == "*" || changed in tables }
                .collect { refreshTrigger.tryEmit(Unit) }
        }
        val pollJob = launch {
            while (isActive) {
                delay(fallbackPollMs)
                refreshTrigger.tryEmit(Unit)
            }
        }

        suspend fun emitLatest() {
            try {
                send(fetcher())
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(SB_TAG, "Sync warning for $tables: ${e.message}")
                throw e
            }
        }

        emitLatest()
        refreshTrigger.collect {
            try {
                send(fetcher())
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(SB_TAG, "Background refresh skipped for $tables: ${e.message}")
            }
        }

        collectorJob.cancel()
        pollJob.cancel()
    }

    private suspend fun executeAuthedRequest(
        requestBuilder: (sessionToken: String) -> Request
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        SupabaseConfig.requireConfigured()
        val token = requireSessionToken()
        try {
            httpClient.newCall(requestBuilder(token)).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        } catch (e: UnknownHostException) {
            throw IllegalStateException(
                "Cannot reach Supabase (${SupabaseConfig.hostDisplay.ifBlank { "check SUPABASE_URL" }}). Check your internet connection."
            )
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException("Connection to Supabase timed out. Tap Retry.")
        } catch (e: ConnectException) {
            throw IllegalStateException("Unable to connect to Supabase right now. Check your network.")
        } catch (e: IOException) {
            throw IllegalStateException("Network error talking to Supabase: ${e.message ?: "please retry"}.")
        }
    }

    suspend fun restGet(
        table: String,
        queryParams: List<Pair<String, String>> = emptyList()
    ): JSONArray {
        val url = buildRestUrl(table, queryParams)
        val (code, text) = executeAuthedRequest { token ->
            Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("x-uchat-token", token)
                .addHeader("Accept", "application/json")
                .get()
                .build()
        }
        if (code !in 200..299) {
            throw IllegalStateException(extractRestError(text, code))
        }
        return if (text.isBlank()) JSONArray() else JSONArray(text)
    }

    suspend fun restPost(
        table: String,
        body: JSONObject,
        upsert: Boolean = false,
        onConflict: String? = null
    ): JSONArray {
        val params = if (!onConflict.isNullOrBlank()) listOf("on_conflict" to onConflict) else emptyList()
        val url = buildRestUrl(table, params)
        val prefer = if (upsert) "resolution=merge-duplicates,return=representation" else "return=representation"
        val jsonBody = body.toString()
        val (code, text) = executeAuthedRequest { token ->
            Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("x-uchat-token", token)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", prefer)
                .post(jsonBody.toRequestBody(JSON_TYPE))
                .build()
        }
        if (code !in 200..299) {
            throw IllegalStateException(extractRestError(text, code))
        }
        notifyLocalChange(table)
        return if (text.isBlank()) JSONArray() else JSONArray(text)
    }

    suspend fun restPatch(
        table: String,
        queryParams: List<Pair<String, String>>,
        body: JSONObject
    ): JSONArray {
        val url = buildRestUrl(table, queryParams)
        val jsonBody = body.toString()
        val (code, text) = executeAuthedRequest { token ->
            Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("x-uchat-token", token)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .patch(jsonBody.toRequestBody(JSON_TYPE))
                .build()
        }
        if (code !in 200..299) {
            throw IllegalStateException(extractRestError(text, code))
        }
        notifyLocalChange(table)
        return if (text.isBlank()) JSONArray() else JSONArray(text)
    }

    suspend fun restDelete(
        table: String,
        queryParams: List<Pair<String, String>>
    ) {
        val url = buildRestUrl(table, queryParams)
        val (code, text) = executeAuthedRequest { token ->
            Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                .addHeader("x-uchat-token", token)
                .delete()
                .build()
        }
        if (code !in 200..299) {
            throw IllegalStateException(extractRestError(text, code))
        }
        notifyLocalChange(table)
    }

    suspend fun uploadBase64ToStorageOrFallback(
        ownerUid: String,
        folder: String,
        mediaValue: String?,
        extension: String,
        contentType: String
    ): String? {
        if (mediaValue.isNullOrBlank()) return null
        if (mediaValue.startsWith("https://") || mediaValue.startsWith("http://")) {
            return mediaValue
        }
        return withContext(Dispatchers.IO) {
            try {
                SupabaseConfig.requireConfigured()
                if (currentUserId() == null) return@withContext mediaValue
                val bytes = Base64.decode(mediaValue, Base64.DEFAULT)
                val safeOwner = ownerUid.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "user" }
                val safeFolder = folder.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "files" }
                val safeExt = extension.replace(Regex("[^a-zA-Z0-9]"), "").ifBlank { "bin" }
                val objectPath = "$safeOwner/$safeFolder/${UUID.randomUUID()}.$safeExt"
                val uploadUrl = "${SupabaseConfig.baseUrl}/storage/v1/object/$MEDIA_BUCKET/$objectPath"
                val req = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("apikey", SupabaseConfig.anonKey)
                    .addHeader("Authorization", "Bearer ${SupabaseConfig.anonKey}")
                    .addHeader("x-upsert", "true")
                    .addHeader("Content-Type", contentType)
                    .post(bytes.toRequestBody(contentType.toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        "${SupabaseConfig.baseUrl}/storage/v1/object/public/$MEDIA_BUCKET/$objectPath"
                    } else {
                        Log.w(SB_TAG, "Supabase Storage upload fallback (HTTP ${resp.code})")
                        mediaValue
                    }
                }
            } catch (e: Exception) {
                Log.w(SB_TAG, "Supabase Storage upload warning: ${e.message}")
                mediaValue
            }
        }
    }

    private fun buildRestUrl(table: String, queryParams: List<Pair<String, String>>): String {
        val base = "${SupabaseConfig.baseUrl}/rest/v1/$table"
        if (queryParams.isEmpty()) return base
        val encoded = queryParams.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base?$encoded"
    }

    private fun extractRestError(body: String, code: Int): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val msg = parsed?.optString("message")
            ?.ifBlank { parsed.optString("details") }
            ?.ifBlank { parsed.optString("hint") }
            .orEmpty()
        val lower = msg.lowercase()
        return when {
            lower.contains("invalid input syntax for type uuid") ->
                "Your Supabase tables are still using the old UUID format. Run the updated supabase_schema.sql in Supabase SQL Editor and tap Retry."
            (lower.contains("relation") && lower.contains("does not exist")) ||
                lower.contains("could not find the table") ->
                "Supabase tables have not been created yet. Run supabase_schema.sql in your Supabase Dashboard → SQL Editor, then tap Retry."
            code == 401 ->
                "Supabase rejected the API key (HTTP 401). Check SUPABASE_ANON_KEY in Secrets."
            lower.contains("row-level security") ->
                "Session expired or security policy updated. Tap Sign Out and sign back in, or run the latest supabase_schema.sql in Supabase SQL Editor."
            msg.isNotBlank() -> msg
            else -> "Supabase request failed (HTTP $code)"
        }
    }

    companion object {
        fun jsonArrayToStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return List(arr.length()) { idx -> arr.optString(idx, "") }.filter { it.isNotBlank() }
        }

        fun jsonArrayToLongList(arr: JSONArray?): List<Long>? {
            if (arr == null || arr.length() == 0) return null
            return List(arr.length()) { idx -> arr.optLong(idx, 0L) }
        }

        fun jsonObjectToStringMap(obj: JSONObject?): Map<String, String> {
            if (obj == null) return emptyMap()
            val map = linkedMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "")
            }
            return map
        }

        fun listToJsonArray(items: List<Any>): JSONArray = JSONArray().apply {
            items.forEach { put(it) }
        }

        fun mapToJsonObject(map: Map<String, String>): JSONObject = JSONObject().apply {
            map.forEach { (k, v) -> put(k, v) }
        }

        fun optNullableString(obj: JSONObject, key: String): String? {
            if (obj.isNull(key)) return null
            return obj.optString(key, "").takeIf { it.isNotBlank() }
        }
    }
}
