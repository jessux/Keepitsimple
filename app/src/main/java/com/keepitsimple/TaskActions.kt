package com.keepitsimple

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime

object TaskActions {
    /**
     * Termine une tâche. Si elle est récurrente, l'occurrence est archivée dans « Terminées »
     * et la tâche reste à faire avec l'échéance suivante (même numéro). Renvoie la tâche suivante éventuelle.
     */
    fun complete(c: Context, task: Task): Task? {
        val store = TaskStore(c)
        Reminders.cancel(c, task)
        val repeat = task.repeat
        val due = task.due
        if (repeat == null || due == null) {
            store.patch(task.id) { it.copy(done = true) }
            GoogleTasks.push(c, task.copy(done = true))
            return null
        }
        // L'archive garde le lien Google (marquée terminée là-bas), l'occurrence suivante est une nouvelle tâche Google
        val archived = store.add(task.copy(id = store.newId(), done = true, repeat = null))
        val nextDue = Repeat.next(due, repeat).let { if (it.isAfter(LocalDateTime.now())) it else Repeat.nextAfter(it, repeat, LocalDateTime.now()) }
        store.patch(task.id) { it.copy(due = nextDue, gid = null) }
        val next = store.all().first { it.id == task.id }
        Reminders.schedule(c, next)
        GoogleTasks.push(c, archived)
        GoogleTasks.push(c, next)
        return next
    }
}

/** Toutes les heures, même application fermée : synchronisation Google Tasks → KIS et mise à jour de l'application. */
object PeriodicSync {
    fun schedule(c: Context) {
        val pi = PendingIntent.getBroadcast(
            c, 1, Intent(c, SyncReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        c.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, pi
        )
    }
}

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { GoogleTasks.pull(context) } catch (e: Exception) { Log.e("Sync", "Échec", e) }
            // Pas d'installation pendant que l'application est à l'écran (elle serait fermée)
            try { if (!MainActivity.visible) Updater.check(context) } catch (e: Exception) { Log.e("Updater", "Échec", e) } finally { pending.finish() }
        }.start()
    }
}
