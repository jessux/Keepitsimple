package com.keepitsimple

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Récapitulatif par e-mail (Gmail) des tâches dont l'échéance est dépassée.
 * Envoyé chaque jour à 6h à l'adresse du compte Google connecté, seulement s'il y a des tâches en retard.
 */
object Recap {
    private val HOUR = LocalTime.of(6, 0)
    private val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH)

    fun scheduleDaily(context: Context) {
        var first = LocalDateTime.now().with(HOUR)
        if (first.isBefore(LocalDateTime.now())) first = first.plusDays(1)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, RecapReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Alarme exacte à usage unique, reprogrammée à chaque déclenchement (plus précis qu'une alarme répétée)
        val am = context.getSystemService(AlarmManager::class.java)
        val at = first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Envoie le récapitulatif (bloquant : à appeler hors thread UI). Renvoie le message à annoncer. */
    fun send(context: Context): String {
        if (!CloudSync.prefs(context).getBoolean("google", false)) return "Connectez d'abord Google"
        val late = TaskStore(context).all().filter { !it.done && it.due?.isBefore(LocalDateTime.now()) == true }
        if (late.isEmpty()) return "Aucune tâche en retard"
        val auth = Tasks.await(Identity.getAuthorizationClient(context).authorize(GoogleTasks.request))
        val token = auth.accessToken ?: return "Reconnectez Google pour autoriser l'envoi d'e-mails"
        val email = CloudSync.http("GET", "https://www.googleapis.com/oauth2/v3/userinfo", token, null).getString("email")

        val body = buildString {
            append("${late.size} tâche(s) en retard :\n\n")
            late.forEach { append("• ${it.title} — prévue ${it.due!!.format(fmt)}\n") }
            append("\n— KIS · Keep It Simple")
        }
        val subject = "=?UTF-8?B?" + b64("KIS : ${late.size} tâche(s) en retard".toByteArray(), false) + "?="
        val mime = "To: $email\r\nSubject: $subject\r\nContent-Type: text/plain; charset=UTF-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" + b64(body.toByteArray(), false)
        CloudSync.http("POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", token,
            JSONObject().put("raw", b64(mime.toByteArray(), true)).toString())
        return "Récapitulatif envoyé à $email"
    }

    private fun b64(bytes: ByteArray, urlSafe: Boolean) =
        Base64.encodeToString(bytes, Base64.NO_WRAP or if (urlSafe) Base64.URL_SAFE else 0)
}

class RecapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Recap.scheduleDaily(context) // prochain envoi demain
        val pending = goAsync()
        Thread {
            try {
                val msg = Recap.send(context)
                if (msg.startsWith("Reconnectez")) Reminders.notify(context, 0, msg)
            } catch (e: Exception) {
                Log.e("Recap", "Échec d'envoi", e)
                // L'échec du récap du matin est signalé, avec sa cause
                Reminders.notify(context, 0, "Récap non envoyé : " + GoogleTasks.failure(e, "Gmail"))
            } finally {
                pending.finish()
            }
        }.start()
    }
}
