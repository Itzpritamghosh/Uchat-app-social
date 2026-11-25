package com.example.media

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R

object NotificationHelper {
    private const val CHANNEL_MESSAGES = "uchat_messages"
    private const val CHANNEL_CALLS = "uchat_calls"
    private const val CHANNEL_UPDATES = "uchat_channels"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels = listOf(
            NotificationChannel(CHANNEL_MESSAGES, "Messages & Groups", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_CALLS, "Incoming & Missed Calls", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_UPDATES, "Channel Broadcasts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        channels.forEach { manager.createNotificationChannel(it) }
    }

    fun notifyMessage(context: Context, title: String, body: String, notificationId: Int) {
        dispatch(context, CHANNEL_MESSAGES, title, body, notificationId)
    }

    fun notifyCall(context: Context, title: String, body: String, notificationId: Int) {
        dispatch(context, CHANNEL_CALLS, title, body, notificationId)
    }

    fun notifyChannelPost(context: Context, title: String, body: String, notificationId: Int) {
        dispatch(context, CHANNEL_UPDATES, title, body, notificationId)
    }

    private fun dispatch(context: Context, channelId: String, title: String, body: String, id: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
