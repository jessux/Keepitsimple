package com.keepitsimple

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Écoute en continu tant que l'application est ouverte : pas de bouton à presser.
 * « ferme l'application » quitte l'application.
 * On coupe le micro pendant que l'application parle, puis on relance l'écoute.
 */
class MainActivity : AppCompatActivity() {

    private enum class State { LISTENING, SPEAKING, PAUSED }

    private lateinit var store: TaskStore
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tasks = listOf<Task>()
    private var paused = false
    private var resumed = false

    private val spoken = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH)
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.FRENCH)

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var mic: TextView
    private lateinit var pulse: View
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private val adapter = TaskAdapter()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMic()) listen() else setState(State.PAUSED, "Micro non autorisé")
    }

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        // Google renvoie la cause de l'échec dans l'intent, même quand le résultat n'est pas OK
        val error = try {
            Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            null
        } catch (e: ApiException) { e.statusCode } catch (e: Exception) { -1 }
        if (error == null) {
            CloudSync.prefs(this).edit().putBoolean("google", true).apply()
            say("Google connecté")
        } else say(GoogleTasks.errorMessage(error))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = TaskStore(this)

        status = findViewById(R.id.status)
        heard = findViewById(R.id.heard)
        mic = findViewById(R.id.mic)
        pulse = findViewById(R.id.pulse)
        count = findViewById(R.id.count)
        empty = findViewById(R.id.empty)
        findViewById<TextView>(R.id.today).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)).replaceFirstChar { it.uppercase() }

        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ -> delete(tasks[pos]); true }
        // Touche facultative : pause / reprise de l'écoute
        mic.setOnClickListener { if (paused) resumeListening() else pauseListening() }

        tts = TextToSpeech(this) { tts.language = Locale.FRENCH }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { handler.post { if (!paused) listen() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { handler.post { if (!paused) listen() } }
        })

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(needed.toTypedArray())
        Recap.scheduleDaily(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (hasMic() && !paused) listen()
    }

    override fun onPause() {
        resumed = false
        recognizer?.destroy()
        recognizer = null
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- Écoute continue ----------

    private fun listen() {
        if (!resumed || paused || !hasMic()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setState(State.PAUSED, "Reconnaissance vocale indisponible"); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
        setState(State.LISTENING, "J'écoute…")
    }

    private fun relisten(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val s = 1f + (rmsdB.coerceIn(0f, 10f) / 25f)
            pulse.scaleX = s; pulse.scaleY = s
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { heard.text = "« $it »" }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) relisten(300) else {
                heard.text = "« $text »"
                handle(text)
            }
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> setState(State.PAUSED, "Micro non autorisé")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> relisten(1000)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> relisten(3000)
                else -> relisten(300) // silence ou phrase non comprise : on réécoute
            }
        }
    }

    private fun pauseListening() {
        paused = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        setState(State.PAUSED, "En pause — touchez le micro")
    }

    private fun resumeListening() {
        paused = false
        listen()
    }

    private fun setState(state: State, text: String) {
        val color = ContextCompat.getColor(this, when (state) {
            State.LISTENING -> R.color.listening
            State.SPEAKING -> R.color.speaking
            State.PAUSED -> R.color.paused
        })
        mic.backgroundTintList = ColorStateList.valueOf(color)
        pulse.backgroundTintList = ColorStateList.valueOf(color)
        if (state != State.LISTENING) { pulse.scaleX = 1f; pulse.scaleY = 1f }
        status.text = text
    }

    private fun say(text: String) {
        recognizer?.cancel() // ne pas s'écouter soi-même
        setState(State.SPEAKING, "Je réponds…")
        heard.text = text
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg") != TextToSpeech.SUCCESS) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            relisten(500)
        }
    }

    // ---------- Commandes ----------

    private fun handle(text: String) {
        val t = text.trim()
        val read = Regex("^(lis|lire|liste|quelles sont)\\b.*", RegexOption.IGNORE_CASE)
        val delete = Regex("^(supprime|efface|enlève|retire|termine|j'ai fini)\\s*(la tâche\\s*)?(.*)$", RegexOption.IGNORE_CASE)
        val connect = Regex("^(connecte|connecter|connexion|déconnecte|déconnecter)\\b.*(google|microsoft)", RegexOption.IGNORE_CASE)
        val pause = Regex("^(pause|stop|arrête d'écouter|tais-toi)\\b.*", RegexOption.IGNORE_CASE)
        val close = Regex("^(ferme|fermer|quitte|quitter)\\s*(l'application|l'appli|l'app|kis|keep it simple)?$", RegexOption.IGNORE_CASE)
        // « envoie le récap » ; « envoie un mail à Paul demain » reste une tâche
        val recap = Regex("^(envoie|envoyer|envoi)\\b.*(récap|récapitulatif|tâches en retard).*", RegexOption.IGNORE_CASE)
        val m = delete.find(t)
        val c = connect.find(t)
        when {
            recap.matches(t) -> {
                say("J'envoie le récapitulatif")
                Thread {
                    val msg = try { Recap.send(this) } catch (e: Exception) { "Échec d'envoi du récapitulatif" }
                    runOnUiThread { say(msg) }
                }.start()
            }
            close.matches(t) -> finishAndRemoveTask()
            pause.matches(t) -> pauseListening()
            c != null -> {
                val google = c.groupValues[2].lowercase() == "google"
                if (c.groupValues[1].lowercase().startsWith("déconnect")) {
                    CloudSync.prefs(this).edit().apply {
                        if (google) remove("google") else { remove("ms_refresh"); remove("ms_list") }
                    }.apply()
                    say(if (google) "Google Tasks déconnecté" else "Microsoft To Do déconnecté")
                } else if (google) {
                    GoogleTasks.connect(this, { googleConsent.launch(IntentSenderRequest.Builder(it).build()) }) { error ->
                        say(if (error == null) "Google connecté" else GoogleTasks.errorMessage(error))
                    }
                } else if (!MicrosoftTodo.configured()) {
                    say("Microsoft To Do est désactivé")
                } else MicrosoftTodo.connect(this)
            }
            read.matches(t) -> say(
                if (tasks.isEmpty()) "Aucune tâche" else tasks.joinToString(". ", "${tasks.size} tâches. ") { spokenLabel(it) }
            )
            m != null -> {
                val words = m.groupValues[3].lowercase()
                val task = tasks.firstOrNull { words.isNotBlank() && (words in it.title.lowercase() || it.title.lowercase() in words) }
                if (task == null) say("Je n'ai pas trouvé cette tâche") else delete(task)
            }
            else -> {
                val task = VoiceParser.parse(t)
                store.add(task)
                Reminders.schedule(this, task)
                CloudSync.push(this, task, ::say)
                refresh()
                say(when {
                    task.due == null -> "Ajouté sans échéance : ${task.title}. Je n'ai pas compris de date."
                    task.due.isBefore(LocalDateTime.now()) -> "Attention, échéance déjà passée : ${spokenLabel(task)}"
                    else -> "Ajouté : ${spokenLabel(task)}"
                })
            }
        }
    }

    private fun delete(task: Task) {
        Reminders.cancel(this, task)
        store.remove(task.id)
        refresh()
        say("Supprimé : ${task.title}")
    }

    private fun spokenLabel(t: Task) = t.title + (t.due?.let { ", " + it.format(spoken) } ?: "")

    private fun refresh() {
        tasks = store.all()
        count.text = when (tasks.size) { 0 -> ""; 1 -> "1 tâche"; else -> "${tasks.size} tâches" }
        empty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ---------- Liste ----------

    private inner class TaskAdapter : BaseAdapter() {
        override fun getCount() = tasks.size
        override fun getItem(position: Int) = tasks[position]
        override fun getItemId(position: Int) = tasks[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_task, parent, false)
            val task = tasks[position]
            val (label, colorRes) = dueLabel(task.due)
            val color = ContextCompat.getColor(this@MainActivity, colorRes)
            v.findViewById<TextView>(R.id.title).text = task.title
            v.findViewById<View>(R.id.dot).backgroundTintList = ColorStateList.valueOf(color)
            v.findViewById<TextView>(R.id.due).apply {
                text = label
                setTextColor(color)
                backgroundTintList = ColorStateList.valueOf(color)
            }
            return v
        }
    }

    private fun dueLabel(due: LocalDateTime?): Pair<String, Int> {
        if (due == null) return "Sans date" to R.color.muted
        val now = LocalDateTime.now()
        val time = due.toLocalTime().toString()
        return when {
            due.isBefore(now) -> "En retard" to R.color.late
            due.toLocalDate() == now.toLocalDate() -> "Aujourd'hui · $time" to R.color.today
            due.toLocalDate() == now.toLocalDate().plusDays(1) -> "Demain · $time" to R.color.accent
            else -> due.format(shortDate) to R.color.muted
        }
    }
}
