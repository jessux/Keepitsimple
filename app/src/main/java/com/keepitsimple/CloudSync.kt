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
import org.json.JSONObject
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
        if (prefs(activity).getBoolean("google", false)) GoogleTasks.push(activity, task, onError)
        if (MicrosoftTodo.configured() && prefs(activity).contains("ms_refresh")) MicrosoftTodo.push(activity, task, onError)
    }

    internal fun http(method: String, url: String, token: String?, body: String?, form: Boolean = false): JSONObject {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = method
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
    private fun failure(e: Exception): String = when {
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

    fun push(activity: Activity, task: Task, onError: (String) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { r ->
                val token = r.accessToken
                if (token == null) { onError("Reconnectez Google"); return@addOnSuccessListener }
                Thread {
                    try {
                        val body = JSONObject().put("title", task.title)
                        task.due?.let {
                            body.put("due", it.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toString())
                            body.put("notes", "Échéance : ${it.toLocalTime()}")
                        }
                        CloudSync.http("POST", "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks", token, body.toString())
                    } catch (e: Exception) {
                        Log.e("CloudSync", "Google", e)
                        activity.runOnUiThread { onError(failure(e)) }
                    }
                }.start()
            }
            .addOnFailureListener { e -> onError(errorMessage((e as? ApiException)?.statusCode ?: -1)) }
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
