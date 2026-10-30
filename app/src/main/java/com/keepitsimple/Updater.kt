package com.keepitsimple

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.net.URL

/**
 * Mise à jour automatique depuis les releases GitHub (dépôt public).
 * Android ≥ 12 : installation silencieuse une fois que KIS s'est déjà mis à jour lui-même une fois ;
 * sinon une notification demande de confirmer l'installation.
 * Prérequis : autoriser KIS à « installer des applications inconnues ».
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/jessux/Keepitsimple/releases/latest"
    private const val MIN_INTERVAL_MS = 60 * 60 * 1000L

    enum class Result { UP_TO_DATE, AVAILABLE, INSTALLING, NEED_PERMISSION, SKIPPED }

    /**
     * Bloquant (hors thread UI). [force] ignore l'intervalle minimal entre deux vérifications ;
     * [install] = false se contente de signaler une version disponible.
     */
    fun check(c: Context, force: Boolean = false, install: Boolean = true): Pair<Result, String?> {
        val prefs = CloudSync.prefs(c)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("upd_check", 0) < MIN_INTERVAL_MS) return Result.SKIPPED to null
        prefs.edit().putLong("upd_check", now).apply()

        val release = CloudSync.http("GET", LATEST, null, null)
        val version = release.getString("tag_name").removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return Result.UP_TO_DATE to version
        if (!c.packageManager.canRequestPackageInstalls()) return Result.NEED_PERMISSION to version
        if (!install) return Result.AVAILABLE to version
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
            ?: return Result.UP_TO_DATE to version

        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            URL(apk).openStream().use { input ->
                session.openWrite("kis.apk", 0, -1).use { out -> input.copyTo(out); session.fsync(out) }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(c, 2, Intent(c, UpdateReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
        return Result.INSTALLING to version
    }

    /** "0.10" > "0.9" : comparaison numérique de chaque partie. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val d = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

/** Résultat de l'installation : si Android demande une confirmation, on l'affiche (ou on notifie). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.visible) context.startActivity(confirm) else {
                    val nm = context.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(NotificationChannel("updates", "Mises à jour", NotificationManager.IMPORTANCE_DEFAULT))
                    val pi = PendingIntent.getActivity(context, 3, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    nm.notify(-1, Notification.Builder(context, "updates")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("KIS : nouvelle version prête")
                        .setContentText("Touchez pour installer la mise à jour")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build())
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updater", "Mise à jour installée")
            else -> Log.e("Updater", "Échec : " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
