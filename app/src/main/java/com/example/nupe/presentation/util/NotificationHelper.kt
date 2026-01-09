package com.example.nupe.presentation.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.nupe.core.data.Verse
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "nupe_risk_channel"
        const val CHANNEL_NAME = "Nupe Alerts"
        const val NOTIFICATION_ID = 101
        const val SUGGESTIVE_NOTIFICATION_ID = 999 // OBJECTIVE 3: ID for softcore notifications
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for detected content"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showRiskNotification(verse: Verse) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Replace with app icon later
            .setContentTitle("A Reminder")
            .setContentText(verse.info)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${verse.info}\n\n${verse.reference}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * OBJECTIVE 3: Show notification for softcore/suggestive content (bikini, sexy but not explicit)
     */
    fun showSuggestiveContentNotification() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Suggestive Content Detected")
            .setContentText("Be mindful of what you view. Lower your gaze.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(SUGGESTIVE_NOTIFICATION_ID, notification)
    }
}
