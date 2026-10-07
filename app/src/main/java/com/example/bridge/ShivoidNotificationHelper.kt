package com.example.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

class ShivoidNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "shivoid_native_channel"
        const val CHANNEL_NAME = "SHIVOID Native Notifications"
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications dispatched by SHIVOID web applications"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showNotification(title: String, message: String, id: Int, customChannelId: String? = null): Boolean {
        val targetChannel = if (!customChannelId.isNullOrBlank() && customChannelId != "default") {
            customChannelId
        } else {
            CHANNEL_ID
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, targetChannel)
            .setSmallIcon(R.drawable.ic_launcher_shivoid_1791360295670)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        return try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                manager.notify(id, builder.build())
                true
            } else {
                false
            }
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancelNotification(id: Int): Boolean {
        return try {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(id)
            true
        } catch (_: Exception) {
            false
        }
    }
}
