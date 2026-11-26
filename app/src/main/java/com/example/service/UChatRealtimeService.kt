package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.cache.LocalChatCache
import com.example.data.model.AppTimestamp
import com.example.data.supabase.SupabaseClient
import com.example.data.supabase.SupabaseConfig
import com.example.data.supabase.UChatAuthManager
import com.example.media.CallAudioController
import com.example.media.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

private const val SVC_TAG = "UChatRealtimeSvc"
private const val FOREGROUND_NOTIFICATION_ID = 42001

/**
 * Tracks whether the UChat UI is currently foregrounded and which conversation is open
 * so background notifications never duplicate while the user is actively reading that chat.
 */
object UChatAppVisibility {
    @Volatile
    var isForeground: Boolean = false

    @Volatile
    var activeConversationId: String? = null

    @Volatile
    var activeCallId: String? = null
}

/**
 * Lightweight remoteMessaging foreground service that keeps UChat connected over
 * Supabase Realtime so incoming voice/video calls wake even a closed screen and
 * incoming messages trigger instant WhatsApp-style push notifications.
 */
class UChatRealtimeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var authManager: UChatAuthManager
    private lateinit var supabase: SupabaseClient
    private lateinit var localCache: LocalChatCache

    private var monitorJob: Job? = null
    private val activeNotifiedCallIds = ConcurrentHashMap.newKeySet<String>()
    private val lastNotifiedConvMsgMs = ConcurrentHashMap<String, Long>()
    @Volatile
    private var baselineInitialized = false
    @Volatile
    private var baselineTimestampMs = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        authManager = UChatAuthManager(applicationContext)
        supabase = SupabaseClient(authManager)
        localCache = LocalChatCache(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DECLINE_CALL -> {
                val callId = intent.getStringExtra(NotificationHelper.EXTRA_CALL_ID).orEmpty()
                if (callId.isNotBlank()) {
                    handleDeclineFromNotification(callId)
                }
                return START_STICKY
            }
        }

        if (authManager.currentUserId().isNullOrBlank() || !SupabaseConfig.isConfigured) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        ensureMonitoring()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = NotificationHelper.buildBackgroundServiceNotification(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notif)
            }
        }.onFailure { e ->
            Log.w(SVC_TAG, "Unable to promote foreground service: ${e.message}")
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun ensureMonitoring() {
        if (monitorJob?.isActive == true) return
        supabase.ensureRealtimeConnected()
        baselineTimestampMs = System.currentTimeMillis()
        baselineInitialized = false

        monitorJob = serviceScope.launch {
            while (isActive) {
                val uid = authManager.currentUserId()
                if (uid.isNullOrBlank() || !SupabaseConfig.isConfigured) {
                    stopForegroundCompat()
                    stopSelf()
                    break
                }

                runCatching { pollIncomingCalls(uid) }
                    .onFailure { Log.d(SVC_TAG, "Call check skipped: ${it.message}") }

                runCatching { pollIncomingMessages(uid) }
                    .onFailure { Log.d(SVC_TAG, "Message check skipped: ${it.message}") }

                // Poll quickly when backgrounded or locked so calls wake the screen within ~2s
                delay(if (UChatAppVisibility.isForeground) 3_500L else 2_000L)
            }
        }
    }

    private suspend fun pollIncomingCalls(myUid: String) {
        val rows = supabase.restGet(
            table = "calls",
            queryParams = listOf(
                "callee_id" to "eq.$myUid",
                "status" to "eq.ringing",
                "order" to "created_at.desc",
                "limit" to "5"
            )
        )

        val stillRingingIds = mutableSetOf<String>()
        val settings = localCache.getSettings(myUid)
        val allowCallNotif = settings?.callNotif ?: true
        val soundEnabled = settings?.soundEnabled ?: true

        for (i in 0 until rows.length()) {
            val obj = rows.getJSONObject(i)
            val callId = obj.optString("id", "")
            if (callId.isBlank()) continue
            stillRingingIds.add(callId)

            if (activeNotifiedCallIds.add(callId)) {
                val callerName = obj.optString("caller_name", "UChat User")
                val callerUsername = obj.optString("caller_username", "")
                val callType = obj.optString("call_type", "audio")

                // Only post lockscreen/heads-up notification if user is not already on the active call screen
                val screenInteractive = isDeviceScreenOn()
                val alreadyInForegroundUi = UChatAppVisibility.isForeground && screenInteractive
                if (allowCallNotif && (!alreadyInForegroundUi || UChatAppVisibility.activeCallId != callId)) {
                    wakeScreenBriefly()
                    NotificationHelper.notifyIncomingCall(
                        context = applicationContext,
                        callId = callId,
                        callerName = callerName,
                        callerUsername = callerUsername,
                        callType = callType,
                        soundEnabled = soundEnabled
                    )
                    if (!alreadyInForegroundUi && soundEnabled) {
                        CallAudioController.startIncomingRingtone(
                            applicationContext,
                            callId,
                            settings?.ringtoneUri
                        )
                    }
                }
            }
        }

        // Cancel notifications for calls that were answered, missed, or cancelled
        val endedCalls = activeNotifiedCallIds.filter { it !in stillRingingIds }
        endedCalls.forEach { oldId ->
            activeNotifiedCallIds.remove(oldId)
            NotificationHelper.cancelCallNotification(applicationContext, oldId)
            if (!UChatAppVisibility.isForeground) {
                CallAudioController.stopAllTones()
            }
        }
    }

    private suspend fun pollIncomingMessages(myUid: String) {
        val rows = supabase.restGet(
            table = "conversations",
            queryParams = listOf(
                "member_uids" to "cs.{\"$myUid\"}",
                "order" to "last_message_at.desc",
                "limit" to "25"
            )
        )

        val settings = localCache.getSettings(myUid)
        val prefsMap = localCache.getChatPrefs(myUid).orEmpty()

        for (i in 0 until rows.length()) {
            val obj = rows.getJSONObject(i)
            val convId = obj.optString("id", "")
            if (convId.isBlank()) continue

            val lastMsgAtMs = AppTimestamp.parseIso(
                SupabaseClient.optNullableString(obj, "last_message_at")
            )?.epochMillis ?: continue

            val prevSeenMs = lastNotifiedConvMsgMs[convId]
            if (!baselineInitialized) {
                lastNotifiedConvMsgMs[convId] = lastMsgAtMs
                continue
            }

            if (prevSeenMs != null && lastMsgAtMs <= prevSeenMs) continue
            lastNotifiedConvMsgMs[convId] = lastMsgAtMs

            if (lastMsgAtMs <= baselineTimestampMs) continue

            val senderId = obj.optString("last_message_sender_id", "")
            if (senderId.isBlank() || senderId == myUid) continue

            // Respect muted chat and active foreground conversation
            val chatPref = prefsMap[convId]
            if (chatPref?.muted == true || chatPref?.hidden == true) continue
            val isWatchingThisChat = UChatAppVisibility.isForeground &&
                isDeviceScreenOn() &&
                UChatAppVisibility.activeConversationId == convId
            if (isWatchingThisChat) continue

            val kind = obj.optString("kind", "direct")
            val isGroup = kind == "group"
            val allowed = if (isGroup) (settings?.groupNotif ?: true) else (settings?.msgNotif ?: true)
            if (!allowed) continue

            val memberNames = SupabaseClient.jsonObjectToStringMap(obj.optJSONObject("member_names"))
            val memberUsernames = SupabaseClient.jsonObjectToStringMap(obj.optJSONObject("member_usernames"))
            val senderName = memberNames[senderId]?.takeIf { it.isNotBlank() }
                ?: memberUsernames[senderId]?.let { "@$it" }
                ?: "UChat User"
            val title = if (isGroup) {
                obj.optString("title", "Group Chat").ifBlank { "Group Chat" }
            } else {
                senderName
            }
            val preview = obj.optString("last_message_text", "").ifBlank { "New message" }

            NotificationHelper.notifyIncomingMessage(
                context = applicationContext,
                conversationId = convId,
                conversationTitle = title,
                senderName = senderName,
                messagePreview = preview,
                isGroup = isGroup,
                soundEnabled = settings?.soundEnabled ?: true
            )
        }
        baselineInitialized = true
    }

    private fun handleDeclineFromNotification(callId: String) {
        activeNotifiedCallIds.remove(callId)
        NotificationHelper.cancelCallNotification(applicationContext, callId)
        CallAudioController.stopAllTones()
        serviceScope.launch {
            runCatching {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())
                val body = JSONObject()
                    .put("status", "rejected")
                    .put("duration_sec", 0L)
                    .put("updated_at", now)
                supabase.restPatch("calls", listOf("id" to "eq.$callId"), body)
            }
        }
    }

    private fun isDeviceScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "UChat:IncomingCallWake"
                )
                wl.acquire(10_000L)
            }
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        activeNotifiedCallIds.forEach { NotificationHelper.cancelCallNotification(applicationContext, it) }
        activeNotifiedCallIds.clear()
        supabase.disconnectRealtime()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val ACTION_DECLINE_CALL = "com.example.service.ACTION_DECLINE_CALL"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context.applicationContext, UChatRealtimeService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { e ->
                Log.w(SVC_TAG, "Could not start realtime service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context.applicationContext, UChatRealtimeService::class.java).apply {
                    action = ACTION_STOP
                }
                context.applicationContext.startService(intent)
            }
        }
    }
}
