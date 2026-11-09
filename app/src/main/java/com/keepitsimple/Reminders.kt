package com.keepitsimple

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import java.time.ZoneId

object Reminders {
    private const val CHANNEL = "reminders"
    private const val ALARM_CHANNEL = "alarms"

    fun schedule(context: Context, task: Task) {
        if (task.done) return
        val due = task.due ?: return
        val at = due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at < System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, task)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (task.alarm && exact) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi)
        } else if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, task: Task) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, task))
    }

    private fun pending(context: Context, task: Task): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("id", task.id).putExtra("title", task.title).putExtra("alarm", task.alarm)
        return PendingIntent.getBroadcast(
            context, task.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun notify(context: Context, id: Long, title: String, alarm: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = if (alarm) ALARM_CHANNEL else CHANNEL
        nm.createNotificationChannel(
            if (alarm) NotificationChannel(ALARM_CHANNEL, "Réveils", NotificationManager.IMPORTANCE_HIGH).apply {
                // Son d'alarme sur le flux « alarme » : sonne même en mode silencieux/vibreur
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                enableVibration(true)
                setBypassDnd(true)
            } else NotificationChannel(CHANNEL, "Rappels", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = android.app.Notification.Builder(context, channel)
            .setSmallIcon(if (alarm) android.R.drawable.ic_lock_idle_alarm else android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (alarm) "⏰ KIS" else "KIS")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (alarm) b.setCategory(android.app.Notification.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .addAction(android.app.Notification.Action.Builder(null, "Arrêter", open).build())
        val n = b.build()
        // Le son se répète jusqu'à ce qu'on touche ou balaye la notification
        if (alarm) n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        nm.notify(id.toInt(), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.notify(context, intent.getLongExtra("id", 0), intent.getStringExtra("title") ?: "", intent.getBooleanExtra("alarm", false))
    }
}

/** Les alarmes sont effacées au redémarrage : on les reprogramme. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            TaskStore(context).all().forEach { Reminders.schedule(context, it) }
            Recap.scheduleDaily(context)
            PeriodicSync.schedule(context)
        }
    }
}
