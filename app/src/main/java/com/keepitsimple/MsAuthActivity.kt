package com.keepitsimple

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Reçoit la redirection com.keepitsimple://auth après la connexion Microsoft. */
class MsAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.data?.getQueryParameter("code")
        if (code == null) { done("Connexion Microsoft annulée"); return }
        Thread {
            val msg = try {
                MicrosoftTodo.exchangeCode(this, code); "Microsoft To Do connecté"
            } catch (e: Exception) { "Échec de connexion Microsoft" }
            runOnUiThread { done(msg) }
        }.start()
    }

    private fun done(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
