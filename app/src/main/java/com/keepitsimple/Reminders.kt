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
        try {
            when {
                task.alarm && exact -> am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), pi)
                exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
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
        val id = intent.getLongExtra("id", 0)
        val title = intent.getStringExtra("title") ?: ""
        if (!intent.getBooleanExtra("alarm", false)) return Reminders.notify(context, id, title)
        try {
            context.startForegroundService(
                Intent(context, AlarmService::class.java).putExtra("id", id).putExtra("title", title)
            )
        } catch (e: Exception) {
            // Démarrage refusé (alarme inexacte, restrictions constructeur) : notification sonore en boucle
            Reminders.notify(context, id, title, alarm = true)
        }
    }
}

/** Sonnerie en boucle sur le flux « alarme » (sonne en silencieux / Ne pas déranger) jusqu'à « Arrêter ». */
class AlarmService : android.app.Service() {
    private var player: android.media.MediaPlayer? = null
    private var vibrator: android.os.Vibrator? = null
    private var stopAt: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val id = intent?.getLongExtra("id", 0) ?: 0
        val title = intent?.getStringExtra("title") ?: ""
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("alarm_ring", "Alarme en cours", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)  // le son vient du MediaPlayer
                setBypassDnd(true)
            }
        )
        val screen = PendingIntent.getActivity(
            this, 2, Intent(this, AlarmActivity::class.java).putExtra("title", title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AlarmService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(this, "alarm_ring")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ KIS").setContentText(title)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen).setFullScreenIntent(screen, true)
            .addAction(android.app.Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        if (player == null) ring()
        // Sécurité : s'arrête seul après 10 min
        stopAt?.let(handler::removeCallbacks)
        stopAt = Runnable { stopSelf() }.also { handler.postDelayed(it, 10 * 60_000L) }
        return START_NOT_STICKY
    }

    private fun ring() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        // Certains téléphones n'ont pas de son d'alarme par défaut
        val uri = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .firstNotNullOfOrNull { RingtoneManager.getActualDefaultRingtoneUri(this, it) ?: RingtoneManager.getDefaultUri(it) }
        player = try {
            android.media.MediaPlayer().apply {
                setAudioAttributes(attrs)
                setWakeMode(this@AlarmService, android.os.PowerManager.PARTIAL_WAKE_LOCK)  // CPU éveillé pendant la sonnerie
                setDataSource(this@AlarmService, uri!!)
                isLooping = true
                prepare(); start()
            }
        } catch (e: Exception) { null }
        vibrator = getSystemService(android.os.Vibrator::class.java).also {
            it.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0), attrs)
        }
    }

    override fun onDestroy() {
        stopAt?.let(handler::removeCallbacks)
        player?.release(); player = null
        vibrator?.cancel()
        sendBroadcast(Intent(STOP).setPackage(packageName))  // ferme l'écran d'alarme
        super.onDestroy()
    }

    companion object {
        const val STOP = "com.keepitsimple.ALARM_STOP"
        private const val NOTIF_ID = 424242
    }
}

/** Écran plein écran, par-dessus l'écran verrouillé, avec un gros bouton « Arrêter ». */
class AlarmActivity : android.app.Activity() {
    private val closer = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")  // showWhenLocked/turnScreenOn du manifeste n'existent qu'à partir d'Android 8.1
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(android.widget.TextView(this@AlarmActivity).apply {
                text = "⏰\n" + (intent.getStringExtra("title") ?: "")
                textSize = 32f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, pad * 2)
            })
            addView(android.widget.Button(this@AlarmActivity).apply {
                text = "Arrêter"
                textSize = 24f
                setOnClickListener {
                    startService(Intent(this@AlarmActivity, AlarmService::class.java).setAction(AlarmService.STOP))
                    finish()
                }
            })
        })
        registerReceiver(closer, android.content.IntentFilter(AlarmService.STOP), RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(closer)
        super.onDestroy()
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
