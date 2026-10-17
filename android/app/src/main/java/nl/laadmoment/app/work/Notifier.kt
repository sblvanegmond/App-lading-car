package nl.laadmoment.app.work

import android.Manifest
import android.annotation.SuppressLint
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
import nl.laadmoment.app.MainActivity
import nl.laadmoment.app.R

object Notifier {
    const val CHANNEL_MORNING = "ochtend"
    const val CHANNEL_REMINDER = "seintje"
    private const val ID_MORNING = 1
    private const val ID_REMINDER = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MORNING, "Ochtendoverzicht", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Elke ochtend de beste laaduren van de dag."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Seintje bij het beste moment", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Een kwartier voordat het beste laadmoment begint."
            },
        )
    }

    fun permitted(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun morning(context: Context, title: String, body: String) = show(context, CHANNEL_MORNING, ID_MORNING, title, body)

    fun reminder(context: Context, title: String, body: String) = show(context, CHANNEL_REMINDER, ID_REMINDER, title, body)

    @SuppressLint("MissingPermission") // checked in permitted()
    private fun show(context: Context, channel: String, id: Int, title: String, body: String) {
        if (!permitted(context)) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setColor(0xFF34D399.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
