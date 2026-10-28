package com.keepitsimple

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: TaskStore
    private lateinit var tts: TextToSpeech
    private lateinit var adapter: ArrayAdapter<String>
    private var tasks = listOf<Task>()
    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", Locale.FRENCH)

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::handle)
    }

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            CloudSync.prefs(this).edit().putBoolean("google", true).apply()
            say("Google Tasks connecté")
        } else say("Connexion Google annulée")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = TaskStore(this)

        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        tts = TextToSpeech(this) { tts.language = Locale.FRENCH }
        findViewById<Button>(R.id.mic).setOnClickListener { listen() }
        // Écoute dès l'ouverture : aucune saisie clavier nécessaire
        if (savedInstanceState == null) listen()

        val list = findViewById<ListView>(R.id.list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val t = tasks[pos]
            Reminders.cancel(this, t)
            store.remove(t.id)
            refresh(); true
        }
        refresh()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ex : rappelle-moi d'acheter du pain demain à 18h")
        try { speech.launch(i) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Reconnaissance vocale indisponible", Toast.LENGTH_LONG).show()
        }
    }

    private fun say(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg")
    }

    private fun handle(text: String) {
        val read = Regex("^(lis|lire|liste|quelles sont)\\b.*", RegexOption.IGNORE_CASE)
        val delete = Regex("^(supprime|efface|enlève|retire|termine|j'ai fini)\\s*(la tâche\\s*)?(.*)$", RegexOption.IGNORE_CASE)
        val connect = Regex("^(connecte|connecter|connexion|déconnecte|déconnecter)\\b.*(google|microsoft)", RegexOption.IGNORE_CASE)
        val m = delete.find(text.trim())
        val c = connect.find(text.trim())
        when {
            c != null -> {
                val google = c.groupValues[2].lowercase() == "google"
                if (c.groupValues[1].lowercase().startsWith("déconnect")) {
                    CloudSync.prefs(this).edit().apply {
                        if (google) remove("google") else { remove("ms_refresh"); remove("ms_list") }
                    }.apply()
                    say(if (google) "Google Tasks déconnecté" else "Microsoft To Do déconnecté")
                } else if (google) {
                    GoogleTasks.connect(this, { googleConsent.launch(IntentSenderRequest.Builder(it).build()) }) { ok ->
                        say(if (ok) "Google Tasks connecté" else "Échec de connexion Google")
                    }
                } else if (!MicrosoftTodo.configured()) {
                    say("Microsoft To Do est désactivé")
                } else MicrosoftTodo.connect(this)
            }
            read.matches(text.trim()) -> say(
                if (tasks.isEmpty()) "Aucune tâche" else tasks.joinToString(". ", "${tasks.size} tâches. ") { label(it) }
            )
            m != null -> {
                val words = m.groupValues[3].lowercase()
                val t = tasks.firstOrNull { words.isNotBlank() && (words in it.title.lowercase() || it.title.lowercase() in words) }
                if (t == null) say("Je n'ai pas trouvé cette tâche") else {
                    Reminders.cancel(this, t)
                    store.remove(t.id)
                    refresh()
                    say("Supprimé : ${t.title}")
                }
            }
            else -> {
                val task = VoiceParser.parse(text)
                store.add(task)
                Reminders.schedule(this, task)
                CloudSync.push(this, task, ::say)
                refresh()
                say("Ajouté : ${label(task)}")
            }
        }
    }

    private fun label(t: Task) = t.title + (t.due?.let { " — " + it.format(fmt) } ?: "")

    private fun refresh() {
        tasks = store.all()
        adapter.clear()
        adapter.addAll(tasks.map(::label))
    }
}
