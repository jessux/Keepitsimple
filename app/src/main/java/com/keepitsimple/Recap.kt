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
import java.time.Duration
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

        val now = LocalDateTime.now()
        late.sortedBy { it.due }.let { sorted ->
            val text = buildString {
                append("${late.size} tâche(s) en retard :\n\n")
                sorted.forEach { append("• ${it.title} — prévue ${it.due!!.format(fmt)}\n") }
                append("\n— KIS · Keep It Simple")
            }
            val subject = "=?UTF-8?B?" + b64("KIS : ${late.size} tâche(s) en retard".toByteArray(), false) + "?="
            val boundary = "kis-" + System.currentTimeMillis()
            val mime = "To: $email\r\nSubject: $subject\r\nMIME-Version: 1.0\r\n" +
                "Content-Type: multipart/alternative; boundary=\"$boundary\"\r\n\r\n" +
                part(boundary, "text/plain", text) + part(boundary, "text/html", html(sorted, now)) + "--$boundary--"
            CloudSync.http("POST", "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", token,
                JSONObject().put("raw", b64(mime.toByteArray(), true)).toString())
        }
        return "Récapitulatif envoyé à $email"
    }

    private fun part(boundary: String, type: String, content: String) =
        "--$boundary\r\nContent-Type: $type; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
            Base64.encodeToString(content.toByteArray(), Base64.DEFAULT) + "\r\n"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Retard lisible + couleur (orange < 1 j, rouge < 7 j, rouge foncé au-delà). */
    private fun lateness(due: LocalDateTime, now: LocalDateTime): Pair<String, String> {
        val mins = Duration.between(due, now).toMinutes()
        val days = mins / 1440
        val label = when {
            days >= 1 -> "$days j"
            mins >= 60 -> "${mins / 60} h"
            else -> "$mins min"
        }
        val color = when { days >= 7 -> "#B71C1C"; days >= 1 -> "#E53935"; else -> "#FB8C00" }
        return label to color
    }

    private fun html(tasks: List<Task>, now: LocalDateTime): String {
        val rows = tasks.mapIndexed { i, t ->
            val (label, color) = lateness(t.due!!, now)
            val bg = if (i % 2 == 0) "#FFFFFF" else "#F7F8FC"
            val repeat = t.repeat?.let { "<div style=\"font-size:12px;color:#7A7F99;margin-top:2px\">&#8635; ${Repeat.label(it)}</div>" } ?: ""
            """<tr style="background:$bg">
<td style="padding:12px 14px;border-bottom:1px solid #ECEEF5;font-size:15px;color:#1C1F2E"><b>${esc(t.title)}</b>$repeat</td>
<td style="padding:12px 14px;border-bottom:1px solid #ECEEF5;font-size:13px;color:#555A70;white-space:nowrap">${t.due.format(fmt)}</td>
<td style="padding:12px 14px;border-bottom:1px solid #ECEEF5;text-align:right"><span style="display:inline-block;background:$color;color:#fff;font-size:12px;font-weight:bold;padding:4px 10px;border-radius:12px;white-space:nowrap">+$label</span></td>
</tr>"""
        }.joinToString("\n")
        val oldest = lateness(tasks.first().due!!, now).first
        val today = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH))
        return """<!doctype html><html><body style="margin:0;padding:0;background:#EEF0F7;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#EEF0F7;padding:24px 12px"><tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:600px;background:#FFFFFF;border-radius:14px;overflow:hidden">
<tr><td style="background:#3D5AFE;padding:24px 24px 20px;color:#FFFFFF">
<div style="font-size:13px;opacity:.85;text-transform:uppercase;letter-spacing:1px">KIS · Récap du matin</div>
<div style="font-size:24px;font-weight:bold;margin-top:6px">${tasks.size} tâche${if (tasks.size > 1) "s" else ""} en retard</div>
<div style="font-size:14px;opacity:.85;margin-top:4px">$today</div>
</td></tr>
<tr><td style="padding:18px 24px 6px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
<td width="50%" style="padding-right:6px"><div style="background:#FDECEA;border-radius:10px;padding:12px 14px"><div style="font-size:12px;color:#B71C1C">En retard</div><div style="font-size:22px;font-weight:bold;color:#B71C1C">${tasks.size}</div></div></td>
<td width="50%" style="padding-left:6px"><div style="background:#FFF4E5;border-radius:10px;padding:12px 14px"><div style="font-size:12px;color:#E65100">Plus ancien retard</div><div style="font-size:22px;font-weight:bold;color:#E65100">$oldest</div></div></td>
</tr></table>
</td></tr>
<tr><td style="padding:12px 24px 8px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border:1px solid #ECEEF5;border-radius:10px;border-collapse:separate;overflow:hidden">
<tr style="background:#F0F2FF"><th align="left" style="padding:10px 14px;font-size:12px;color:#3D5AFE;text-transform:uppercase">Tâche</th><th align="left" style="padding:10px 14px;font-size:12px;color:#3D5AFE;text-transform:uppercase">Échéance</th><th align="right" style="padding:10px 14px;font-size:12px;color:#3D5AFE;text-transform:uppercase">Retard</th></tr>
$rows
</table>
</td></tr>
<tr><td style="padding:16px 24px 24px;font-size:12px;color:#9A9EB5;text-align:center">KIS · Keep It Simple — ouvrez l'app pour cocher ou reporter vos tâches.</td></tr>
</table></td></tr></table></body></html>"""
    }

    private fun b64(bytes: ByteArray, urlSafe: Boolean) =
        Base64.encodeToString(bytes, Base64.NO_WRAP or if (urlSafe) Base64.URL_SAFE else 0)
}

class RecapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        if (attempt == 0) Recap.scheduleDaily(context) // prochain envoi demain
        val pending = goAsync()
        Thread {
            try {
                val msg = Recap.send(context)
                if (msg.startsWith("Reconnectez")) Reminders.notify(context, 0, msg)
            } catch (e: Exception) {
                Log.e("Recap", "Échec d'envoi (tentative ${attempt + 1})", e)
                if (isNetwork(e) && attempt < MAX_RETRIES) {
                    retry(context, attempt + 1) // réseau absent (téléphone en veille…) : nouvel essai plus tard
                } else {
                    // L'échec du récap du matin est signalé, avec sa cause
                    Reminders.notify(context, 0, "Récap non envoyé : " + GoogleTasks.failure(e, "Gmail"))
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun isNetwork(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is java.io.IOException) return true
            if (c is com.google.android.gms.common.api.ApiException &&
                c.statusCode == com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR) return true
            c = c.cause
        }
        return false
    }

    /** Nouvel essai dans 15 min (alarme distincte de l'envoi quotidien). */
    private fun retry(context: Context, attempt: Int) {
        val pi = PendingIntent.getBroadcast(
            context, 1, Intent(context, RecapReceiver::class.java).putExtra(EXTRA_ATTEMPT, attempt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + 15 * 60_000L
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    companion object {
        private const val EXTRA_ATTEMPT = "attempt"
        private const val MAX_RETRIES = 8 // jusqu'à 2 h de nouveaux essais
    }
}
