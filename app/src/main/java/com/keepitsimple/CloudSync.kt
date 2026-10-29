package com.keepitsimple

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZoneOffset

class HttpException(val code: Int, val body: String) : Exception("HTTP $code : $body")

/** Envoi des tâches vers Google Tasks et Microsoft To Do (si connectés). */
object CloudSync {
    private const val TAG = "CloudSync"

    fun prefs(c: Context) = c.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    fun push(activity: Activity, task: Task, onError: (String) -> Unit) {
        GoogleTasks.push(activity, task, onError)
        if (MicrosoftTodo.configured() && prefs(activity).contains("ms_refresh")) MicrosoftTodo.push(activity, task, onError)
    }

    internal fun http(method: String, url: String, token: String?, body: String?, form: Boolean = false): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        // HttpURLConnection ne connaît pas PATCH : surcharge acceptée par les API Google
        if (method == "PATCH") {
            con.requestMethod = "POST"
            con.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else con.requestMethod = method
        token?.let { con.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            con.doOutput = true
            con.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            con.outputStream.use { it.write(body.toByteArray()) }
        }
        val ok = con.responseCode in 200..299
        val text = (if (ok) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        if (!ok) throw HttpException(con.responseCode, text)
        return JSONObject(text.ifBlank { "{}" })
    }
}

/**
 * Google Tasks via Google Identity Services.
 * Prérequis : client OAuth « Android » (package + SHA-1) dans Google Cloud, API Tasks activée.
 * Note : Google Tasks ne conserve que la date d'échéance, l'heure est mise dans les notes.
 */
object GoogleTasks {
    // tasks : Google Tasks · gmail.send + email : récapitulatif des tâches en retard (voir Recap)
    internal val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(
            Scope("https://www.googleapis.com/auth/tasks"),
            Scope("https://www.googleapis.com/auth/gmail.send"),
            Scope("email")
        ))
        .build()

    /** Lance la connexion ; [onConsent] reçoit l'écran de consentement à afficher si besoin. */
    /** [onDone] reçoit null si connecté, sinon le code d'erreur Google. */
    fun connect(activity: Activity, onConsent: (android.content.IntentSender) -> Unit, onDone: (Int?) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { r ->
                if (r.hasResolution()) onConsent(r.pendingIntent!!.intentSender)
                else { CloudSync.prefs(activity).edit().putBoolean("google", true).apply(); onDone(null) }
            }
            .addOnFailureListener { e ->
                Log.e("CloudSync", "Google connect", e)
                onDone((e as? ApiException)?.statusCode ?: -1)
            }
    }

    /** Cause lisible d'un échec d'envoi (API désactivée, autorisation, réseau…). */
    fun failure(e: Exception): String = when {
        e is IllegalStateException && e.message == "Reconnectez Google" -> "Reconnectez Google"
        e is java.util.concurrent.ExecutionException && e.cause is ApiException -> errorMessage((e.cause as ApiException).statusCode)
        e is HttpException && e.code == 403 && ("has not been used" in e.body || "disabled" in e.body || "SERVICE_DISABLED" in e.body) ->
            "Google Tasks API non activée dans Google Cloud"
        e is HttpException && e.code == 403 -> "Google Tasks refuse l'accès, reconnectez Google"
        e is HttpException && e.code == 401 -> "Session Google expirée, reconnectez Google"
        e is HttpException -> "Échec Google Tasks, code ${e.code}"
        e is java.io.IOException -> "Google Tasks injoignable, vérifiez le réseau"
        else -> "Échec d'envoi vers Google Tasks"
    }

    fun errorMessage(code: Int) = when (code) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Échec Google, code 10 : configuration Google Cloud incorrecte. Vérifiez le nom de package et l'empreinte SHA-1."
        CommonStatusCodes.CANCELED, 12501 -> "Connexion Google annulée, code $code"
        CommonStatusCodes.NETWORK_ERROR -> "Échec Google : pas de réseau"
        else -> "Échec de connexion Google, code $code"
    }

    private const val API = "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks"
    private val main = Handler(Looper.getMainLooper())
    private val noteTime = Regex("Échéance : (\\d{1,2}):(\\d{2})")

    fun enabled(c: Context) = CloudSync.prefs(c).getBoolean("google", false)

    /** Jeton d'accès (bloquant, hors thread UI). */
    private fun token(c: Context): String =
        Tasks.await(Identity.getAuthorizationClient(c).authorize(request)).accessToken
            ?: throw IllegalStateException("Reconnectez Google")

    private fun body(task: Task) = JSONObject()
        .put("title", task.title)
        .put("status", if (task.done) "completed" else "needsAction")
        .put("due", task.due?.let { it.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toString() } ?: JSONObject.NULL)
        .put("notes", task.due?.let { "Échéance : " + it.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "")

    /** Crée (et mémorise son identifiant) ou met à jour la tâche dans Google Tasks. */
    fun push(c: Context, task: Task, onError: (String) -> Unit = {}) = background(c, onError) { token ->
        if (task.gid == null) {
            val gid = CloudSync.http("POST", API, token, body(task).toString()).getString("id")
            TaskStore(c).patch(task.id) { it.copy(gid = gid) }
        } else CloudSync.http("PATCH", "$API/${task.gid}", token, body(task).toString())
    }

    fun delete(c: Context, gid: String?, onError: (String) -> Unit = {}) {
        if (gid != null) background(c, onError) { token -> CloudSync.http("DELETE", "$API/$gid", token, null) }
    }

    private fun background(c: Context, onError: (String) -> Unit, block: (String) -> Unit) {
        if (!enabled(c)) return
        Thread {
            try { block(token(c)) } catch (e: Exception) {
                Log.e("CloudSync", "Google", e)
                main.post { onError(failure(e)) }
            }
        }.start()
    }

    /**
     * Synchronisation Google Tasks → KIS (bloquant) : applique les tâches créées, modifiées, terminées
     * ou supprimées dans Google Tasks depuis la dernière synchronisation. Renvoie le nombre de changements.
     */
    @Synchronized
    fun pull(c: Context): Int {
        if (!enabled(c)) return 0
        val token = token(c)
        val prefs = CloudSync.prefs(c)
        val since = prefs.getString("g_sync", null)
        val start = Instant.now().minusSeconds(60) // marge pour les horloges
        val store = TaskStore(c)
        var changes = 0
        var page: String? = null
        do {
            var url = "$API?showCompleted=true&showHidden=true&showDeleted=true&maxResults=100"
            since?.let { url += "&updatedMin=" + URLEncoder.encode(it, "UTF-8") }
            page?.let { url += "&pageToken=" + URLEncoder.encode(it, "UTF-8") }
            val r = CloudSync.http("GET", url, token, null)
            val items = r.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                if (apply(c, store, items.getJSONObject(i), firstSync = since == null)) changes++
            }
            page = r.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        prefs.edit().putString("g_sync", start.toString()).apply()
        return changes
    }

    private fun apply(c: Context, store: TaskStore, o: JSONObject, firstSync: Boolean): Boolean {
        val gid = o.getString("id")
        val title = o.optString("title").trim()
        val deleted = o.optBoolean("deleted", false)
        val completed = o.optString("status") == "completed"
        val date = o.optString("due").ifEmpty { null }?.let { Instant.parse(it).atZone(ZoneOffset.UTC).toLocalDate() }
        val time = noteTime.find(o.optString("notes"))?.let { m ->
            runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
        }
        val all = store.all()
        var local = all.firstOrNull { it.gid == gid }
        if (local == null && firstSync) {
            // Première synchro : relie les tâches déjà envoyées avant que KIS mémorise les identifiants
            local = all.firstOrNull { it.gid == null && it.title.equals(title, true) && it.due?.toLocalDate() == date }
                ?.also { t -> store.patch(t.id) { it.copy(gid = gid) } }?.copy(gid = gid)
        }
        if (local == null) {
            if (deleted || completed || title.isEmpty()) return false
            val task = store.add(Task(store.newId(), title, date?.atTime(time ?: LocalTime.of(9, 0)), gid = gid))
            Reminders.schedule(c, task)
            return true
        }
        if (deleted) {
            Reminders.cancel(c, local)
            store.remove(local.id)
            return true
        }
        val due = date?.atTime(time ?: local.due?.takeIf { it.toLocalDate() == date }?.toLocalTime() ?: LocalTime.of(9, 0))
        val updated = local.copy(title = title.ifEmpty { local.title }, due = due)
        return when {
            completed && !local.done -> { store.update(updated); TaskActions.complete(c, updated); true }
            !completed && local.done -> {
                store.update(updated.copy(done = false, num = 0))
                Reminders.schedule(c, store.all().first { it.id == local.id })
                true
            }
            updated != local -> {
                Reminders.cancel(c, local)
                store.update(updated)
                Reminders.schedule(c, updated)
                true
            }
            else -> false
        }
    }
}

/**
 * Microsoft To Do via Microsoft Graph (OAuth 2 + PKCE, sans bibliothèque).
 * Prérequis : application enregistrée dans Azure (comptes personnels + pro),
 * plateforme « Mobile et bureau » avec l'URI de redirection [REDIRECT],
 * client ID dans local.properties : MS_CLIENT_ID=...
 */
object MicrosoftTodo {
    /** Microsoft désactivé pour le moment : passer à true pour le réactiver. */
    private const val ENABLED = false
    const val REDIRECT = "com.keepitsimple://auth"
    private const val AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val SCOPE = "Tasks.ReadWrite offline_access"
    private const val GRAPH = "https://graph.microsoft.com/v1.0/me/todo/lists"

    fun configured() = ENABLED && BuildConfig.MS_CLIENT_ID.isNotEmpty()

    fun connect(activity: Activity) {
        val verifier = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        CloudSync.prefs(activity).edit().putString("ms_verifier", verifier).apply()
        val url = "$AUTH/authorize?client_id=${BuildConfig.MS_CLIENT_ID}&response_type=code" +
            "&redirect_uri=${enc(REDIRECT)}&scope=${enc(SCOPE)}" +
            "&code_challenge=$challenge&code_challenge_method=S256"
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Appelé (hors thread UI) au retour du navigateur avec le code d'autorisation. */
    fun exchangeCode(c: Context, code: String) {
        val verifier = CloudSync.prefs(c).getString("ms_verifier", "")!!
        token(c, "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT)}&code_verifier=$verifier")
    }

    fun push(activity: Activity, task: Task, onError: (String) -> Unit) = Thread {
        try {
            val prefs = CloudSync.prefs(activity)
            val access = token(activity, "grant_type=refresh_token&refresh_token=${enc(prefs.getString("ms_refresh", "")!!)}")
            val listId = prefs.getString("ms_list", null) ?: run {
                val lists = CloudSync.http("GET", GRAPH, access, null).getJSONArray("value")
                val id = (0 until lists.length()).map { lists.getJSONObject(it) }
                    .first { it.optString("wellknownListName") == "defaultList" }.getString("id")
                prefs.edit().putString("ms_list", id).apply()
                id
            }
            val body = JSONObject().put("title", task.title)
            task.due?.let {
                val dt = JSONObject().put("dateTime", it.toString()).put("timeZone", ZoneId.systemDefault().id)
                body.put("dueDateTime", dt).put("reminderDateTime", dt).put("isReminderOn", true)
            }
            CloudSync.http("POST", "$GRAPH/$listId/tasks", access, body.toString())
        } catch (e: Exception) {
            Log.e("CloudSync", "Microsoft", e)
            activity.runOnUiThread { onError("Échec d'envoi vers Microsoft To Do") }
        }
    }.start()

    /** Récupère un jeton d'accès et mémorise le nouveau refresh token. */
    private fun token(c: Context, grant: String): String {
        val r = CloudSync.http("POST", "$AUTH/token", null,
            "client_id=${BuildConfig.MS_CLIENT_ID}&scope=${enc(SCOPE)}&$grant", form = true)
        CloudSync.prefs(c).edit().putString("ms_refresh", r.getString("refresh_token")).apply()
        return r.getString("access_token")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
