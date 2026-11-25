package com.example.data.supabase

import android.util.Base64
import android.util.Log
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
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val SB_TAG = "SupabaseClient"
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MEDIA_BUCKET = "uchat-media"

class SupabaseClient(
    val auth: SupabaseAuthManager
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

    fun requireUserId(): String =
        auth.currentUserId()
            ?: throw IllegalStateException("Please sign in to your UChat account first.")

    fun notifyLocalChange(vararg tables: String) {
        tables.forEach { _tableChanges.tryEmit(it) }
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
                val token = auth.getValidAccessToken()
                if (token.isNullOrBlank() || !SupabaseConfig.isConfigured) {
                    delay(3000L)
                    continue
                }
                val connected = connectRealtimeOnce(token)
                backoffMs = if (connected) 1500L else (backoffMs * 2L).coerceAtMost(20_000L)
                delay(backoffMs)
            }
        }
    }

    private suspend fun connectRealtimeOnce(accessToken: String): Boolean {
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
                        put("broadcast", JSONObject().put("self", false))
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
                    put("access_token", accessToken)
                }
                val joinMsg = JSONObject()
                    .put("topic", "realtime:public")
                    .put("event", "phx_join")
                    .put("payload", joinPayload)
                    .put("ref", refCounter.getAndIncrement().toString())
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val event = msg.optString("event", "")
                    if (event == "postgres_changes") {
                        val data = msg.optJSONObject("payload")?.optJSONObject("data")
                        val table = data?.optString("table", "").orEmpty()
                        if (table.isNotBlank()) {
                            _tableChanges.tryEmit(table)
                        } else {
                            _tableChanges.tryEmit("*")
                        }
                    } else if (event == "INSERT" || event == "UPDATE" || event == "DELETE") {
                        val table = msg.optJSONObject("payload")?.optString("table", "").orEmpty()
                        _tableChanges.tryEmit(table.ifBlank { "*" })
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

    suspend fun restGet(
        table: String,
        queryParams: List<Pair<String, String>> = emptyList()
    ): JSONArray = withContext(Dispatchers.IO) {
        SupabaseConfig.requireConfigured()
        val token = auth.getValidAccessToken()
            ?: throw IllegalStateException("Session expired. Please sign in again.")
        val url = buildRestUrl(table, queryParams)
        val req = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.anonKey)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractRestError(text, resp.code))
            }
            if (text.isBlank()) JSONArray() else JSONArray(text)
        }
    }

    suspend fun restPost(
        table: String,
        body: JSONObject,
        upsert: Boolean = false,
        onConflict: String? = null
    ): JSONArray = withContext(Dispatchers.IO) {
        SupabaseConfig.requireConfigured()
        val token = auth.getValidAccessToken()
            ?: throw IllegalStateException("Session expired. Please sign in again.")
        val params = if (!onConflict.isNullOrBlank()) listOf("on_conflict" to onConflict) else emptyList()
        val url = buildRestUrl(table, params)
        val prefer = if (upsert) "resolution=merge-duplicates,return=representation" else "return=representation"
        val req = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.anonKey)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", prefer)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractRestError(text, resp.code))
            }
            notifyLocalChange(table)
            if (text.isBlank()) JSONArray() else JSONArray(text)
        }
    }

    suspend fun restPatch(
        table: String,
        queryParams: List<Pair<String, String>>,
        body: JSONObject
    ): JSONArray = withContext(Dispatchers.IO) {
        SupabaseConfig.requireConfigured()
        val token = auth.getValidAccessToken()
            ?: throw IllegalStateException("Session expired. Please sign in again.")
        val url = buildRestUrl(table, queryParams)
        val req = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.anonKey)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=representation")
            .patch(body.toString().toRequestBody(JSON_TYPE))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractRestError(text, resp.code))
            }
            notifyLocalChange(table)
            if (text.isBlank()) JSONArray() else JSONArray(text)
        }
    }

    suspend fun restDelete(
        table: String,
        queryParams: List<Pair<String, String>>
    ) = withContext(Dispatchers.IO) {
        SupabaseConfig.requireConfigured()
        val token = auth.getValidAccessToken()
            ?: throw IllegalStateException("Session expired. Please sign in again.")
        val url = buildRestUrl(table, queryParams)
        val req = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.anonKey)
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(extractRestError(text, resp.code))
            }
            notifyLocalChange(table)
        }
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
                val token = auth.getValidAccessToken() ?: return@withContext mediaValue
                val bytes = Base64.decode(mediaValue, Base64.DEFAULT)
                val safeFolder = folder.replace(Regex("[^a-zA-Z0-9_-]"), "")
                val safeExt = extension.replace(Regex("[^a-zA-Z0-9]"), "").ifBlank { "bin" }
                val objectPath = "$ownerUid/$safeFolder/${UUID.randomUUID()}.$safeExt"
                val uploadUrl = "${SupabaseConfig.baseUrl}/storage/v1/object/$MEDIA_BUCKET/$objectPath"
                val req = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("apikey", SupabaseConfig.anonKey)
                    .addHeader("Authorization", "Bearer $token")
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
        return try {
            val obj = JSONObject(body)
            obj.optString("message")
                .ifBlank { obj.optString("details") }
                .ifBlank { obj.optString("hint") }
                .ifBlank { "Supabase request failed (HTTP $code)" }
        } catch (_: Exception) {
            "Supabase request failed (HTTP $code)"
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
