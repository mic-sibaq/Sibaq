package org.sibaq.screening

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_USER_PRESENT
        ) {
            val prefs = context.getSharedPreferences("sibaq_settings", Context.MODE_PRIVATE)
            val streamShouldRun = prefs.getBoolean("stream_should_run", false)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)

            // If user clicked start previously, or autoStart is enabled, ensure stream is running
            if (streamShouldRun || autoStart) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("auto_started_on_boot", true)
                    putExtra("auto_resume_stream", true)
                }

                // 1. Direct activity launch
                try {
                    context.startActivity(launchIntent)
                } catch (_: Exception) {}

                // 2. Accessibility service launch fallback (bypasses Android 10+ background activity restrictions)
                try {
                    LabAccessibilityService.instance?.startActivity(launchIntent)
                } catch (_: Exception) {}

                // 3. Full-screen intent notification fallback for high-security lockscreen / Android 10+ restrictions
                try {
                    triggerBootWakeupNotification(context, launchIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun triggerBootWakeupNotification(context: Context, launchIntent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            2001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "sibaq_boot_recovery_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "SIBAQ Device Screening Recovery",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Auto-restarts screen screening after device reboot or startup"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle("SIBAQ Screen Monitor Resuming")
            .setContentText("Restoring active lab screen streaming...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(8802, notification)
    }
}
