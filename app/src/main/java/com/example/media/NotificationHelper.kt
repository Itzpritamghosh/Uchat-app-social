package com.example.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.service.UChatRealtimeService

object NotificationHelper {
    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_ACCEPT_CALL_ID = "extra_accept_call_id"

    private const val CHANNEL_SERVICE = "uchat_service_status"
    private const val CHANNEL_MESSAGES = "uchat_messages_v2"
    private const val CHANNEL_CALLS = "uchat_calls_v2"
    private const val CHANNEL_UPDATES = "uchat_channels"

    private const val MESSAGE_GROUP_KEY = "com.aistudio.uchat.MESSAGES"

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Real-Time Connection",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps UChat ready for instant incoming calls and messages"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val msgChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages & Groups",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Instant message and group chat alerts"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 180L, 90L, 180L)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val callAudioAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val callChannel = NotificationChannel(
            CHANNEL_CALLS,
            "Incoming Voice & Video Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen incoming voice and video call alerts"
            enableVibration(true)
            vibrationPattern = longArrayOf(0L, 600L, 400L, 600L, 400L)
            setSound(ringtoneUri, callAudioAttrs)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        val updatesChannel = NotificationChannel(
            CHANNEL_UPDATES,
            "Channel Broadcasts",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        listOf(serviceChannel, msgChannel, callChannel, updatesChannel).forEach {
            manager.createNotificationChannel(it)
        }
    }

    fun buildBackgroundServiceNotification(context: Context): Notification {
        ensureChannels(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("UChat active")
            .setContentText("Ready for instant calls & messages")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun notifyIncomingMessage(
        context: Context,
        conversationId: String,
        conversationTitle: String,
        senderName: String,
        messagePreview: String,
        isGroup: Boolean,
        soundEnabled: Boolean
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            ("msg_$conversationId").hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mePerson = Person.Builder().setName("You").build()
        val senderPerson = Person.Builder()
            .setName(senderName.ifBlank { conversationTitle })
            .build()

        val style = NotificationCompat.MessagingStyle(mePerson)
            .setGroupConversation(isGroup)
            .also { s ->
                if (isGroup) s.setConversationTitle(conversationTitle)
            }
            .addMessage(messagePreview.ifBlank { "New message" }, System.currentTimeMillis(), senderPerson)

        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(conversationTitle)
            .setContentText(if (isGroup) "$senderName: $messagePreview" else messagePreview)
            .setStyle(style)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(MESSAGE_GROUP_KEY)
            .setAutoCancel(true)

        if (!soundEnabled) {
            builder.setSilent(true)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(
                ("msg_notif_$conversationId").hashCode(),
                builder.build()
            )
        }
    }

    fun notifyIncomingCall(
        context: Context,
        callId: String,
        callerName: String,
        callerUsername: String,
        callType: String,
        soundEnabled: Boolean
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)

        val isVideo = callType == "video"
        val title = if (isVideo) "Incoming video call" else "Incoming voice call"
        val subtitle = if (callerUsername.isNotBlank()) "$callerName (@$callerUsername)" else callerName

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, callId)
        }
        val fullScreenPi = PendingIntent.getActivity(
            context,
            ("call_fs_$callId").hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_ACCEPT_CALL_ID, callId)
        }
        val acceptPi = PendingIntent.getActivity(
            context,
            ("call_accept_$callId").hashCode(),
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, UChatRealtimeService::class.java).apply {
            action = UChatRealtimeService.ACTION_DECLINE_CALL
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePi = PendingIntent.getService(
            context,
            ("call_decline_$callId").hashCode(),
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(45_000L)
            .addAction(0, "Decline", declinePi)
            .addAction(0, if (isVideo) "Accept Video" else "Accept", acceptPi)

        if (!soundEnabled) {
            builder.setSilent(true)
        }

        val notif = builder.build().apply {
            flags = flags or Notification.FLAG_INSISTENT
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(callNotificationId(callId), notif)
        }
    }

    fun cancelCallNotification(context: Context, callId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(callNotificationId(callId))
        }
    }

    fun notifyMessage(context: Context, title: String, body: String, notificationId: Int) {
        notifyIncomingMessage(
            context = context,
            conversationId = notificationId.toString(),
            conversationTitle = title,
            senderName = title,
            messagePreview = body,
            isGroup = false,
            soundEnabled = true
        )
    }

    fun notifyCall(context: Context, title: String, body: String, notificationId: Int) {
        notifyIncomingCall(
            context = context,
            callId = notificationId.toString(),
            callerName = title,
            callerUsername = body,
            callType = if (title.contains("video", ignoreCase = true)) "video" else "audio",
            soundEnabled = true
        )
    }

    fun notifyChannelPost(context: Context, title: String, body: String, notificationId: Int) {
        if (!hasNotificationPermission(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    private fun callNotificationId(callId: String): Int = ("uchat_call_$callId").hashCode()
}
