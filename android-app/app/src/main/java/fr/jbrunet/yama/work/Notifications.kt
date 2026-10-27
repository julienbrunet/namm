package fr.jbrunet.yama.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.jbrunet.yama.MainActivity
import fr.jbrunet.yama.R

object Notifications {
    private const val CHANNEL_RAIN = "rain"
    private const val CHANNEL_UPDATE = "update"
    private const val ID_RAIN = 1
    private const val ID_UPDATE = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RAIN, context.getString(R.string.channel_rain), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun rain(context: Context, place: String, minutes: Int) =
        post(
            context, CHANNEL_RAIN, ID_RAIN,
            context.getString(R.string.notification_rain_title, minutes),
            context.getString(R.string.notification_rain_text, place, minutes),
        )

    fun update(context: Context, version: String) =
        post(
            context, CHANNEL_UPDATE, ID_UPDATE,
            context.getString(R.string.notification_update_title),
            context.getString(R.string.notification_update_text, version),
        )

    private fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
