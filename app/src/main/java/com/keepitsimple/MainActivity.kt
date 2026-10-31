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
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Écoute en continu tant que l'application est ouverte : pas de bouton à presser.
 * « ferme l'application » quitte l'application.
 * On coupe le micro pendant que l'application parle, puis on relance l'écoute.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile var visible = false
    }

    private var updateReady = false

    private enum class State { LISTENING, SPEAKING, PAUSED }

    private lateinit var store: TaskStore
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tasks = listOf<Task>()      // tâches à faire
    private var doneTasks = listOf<Task>()  // tâches terminées
    private var showDone = false            // onglet affiché
    private val shown get() = if (showDone) doneTasks else tasks
    private var paused = false
    private var resumed = false

    private val spoken = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH)
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.FRENCH)

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var mic: TextView
    private lateinit var pulse: View
    private lateinit var tabs: TabLayout
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
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
        if (!OnboardingActivity.done(this)) {
            // Premier lancement : présentation d'abord (elle rouvre cet écran à la fin)
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        store = TaskStore(this)

        status = findViewById(R.id.status)
        heard = findViewById(R.id.heard)
        mic = findViewById(R.id.mic)
        pulse = findViewById(R.id.pulse)
        tabs = findViewById(R.id.tabs)
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.list)
        findViewById<TextView>(R.id.version).text = "v" + BuildConfig.VERSION_NAME
        findViewById<TextView>(R.id.today).text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)).replaceFirstChar { it.uppercase() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ItemTouchHelper(swipe).attachToRecyclerView(list)
        tabs.addTab(tabs.newTab())
        tabs.addTab(tabs.newTab())
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { showDone = tab.position == 1; refresh() }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
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
        PeriodicSync.schedule(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        visible = true
        checkUpdate(force = false)
        if (hasMic() && !paused) listen()
        sync(announce = false)
    }

    /** Récupère les changements faits dans Google Tasks. */
    private fun sync(announce: Boolean) {
        if (!GoogleTasks.enabled(this)) { if (announce) say("Google n'est pas connecté"); return }
        Thread {
            val msg = try {
                val n = GoogleTasks.pull(this)
                if (n == 0) "Déjà à jour" else "Synchronisé : $n changement${if (n > 1) "s" else ""}"
            } catch (e: Exception) { GoogleTasks.failure(e) }
            runOnUiThread { refresh(); if (announce) say(msg) }
        }.start()
    }

    override fun onStop() {
        visible = false
        // Mise à jour installée quand on quitte l'application, pour ne pas la fermer en cours d'utilisation
        if (updateReady) Thread { runCatching { Updater.check(applicationContext, force = true) } }.start()
        super.onStop()
    }

    private fun checkUpdate(force: Boolean, announce: Boolean = false) {
        Thread {
            val (result, version) = try { Updater.check(this, force, install = false) } catch (e: Exception) { null to null }
            runOnUiThread {
                when (result) {
                    Updater.Result.AVAILABLE -> {
                        updateReady = true
                        heard.text = "Version $version disponible : installée quand vous quitterez l'application"
                        if (announce) say("Version $version disponible. Elle s'installera quand vous quitterez l'application")
                    }
                    Updater.Result.NEED_PERMISSION -> {
                        updateReady = true
                        // Demandé une seule fois par version (ou sur demande vocale)
                        val prefs = CloudSync.prefs(this)
                        if (announce || prefs.getString("upd_perm_asked", null) != version) {
                            prefs.edit().putString("upd_perm_asked", version).apply()
                            say("Version $version disponible. Autorisez KIS à installer des mises à jour")
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName")))
                        }
                    }
                    Updater.Result.UP_TO_DATE -> if (announce) say("KIS est à jour")
                    else -> if (announce) say("Vérification impossible, vérifiez le réseau")
                }
            }
        }.start()
    }

    override fun onPause() {
        resumed = false
        recognizer?.destroy()
        recognizer = null
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown() // absent si on est parti directement vers la présentation
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
        val delete = Regex("^(supprime|efface|enlève|retire)\\s*(la tâche\\s*)?(.*)$", RegexOption.IGNORE_CASE)
        val finish = Regex("^(terminé|termine|j'ai fini|j'ai fait|fait|valide)\\b\\s*(la tâche\\s*)?(.*)$", RegexOption.IGNORE_CASE)
        val connect = Regex("^(connecte|connecter|connexion|déconnecte|déconnecter)\\b.*(google|microsoft)", RegexOption.IGNORE_CASE)
        val help = Regex("^(aide|tutoriel|présentation|comment ça marche)\\b.*", RegexOption.IGNORE_CASE)
        val pause = Regex("^(pause|stop|arrête d'écouter|tais-toi)\\b.*", RegexOption.IGNORE_CASE)
        val close = Regex("^(ferme|fermer|quitte|quitter)\\s*(l'application|l'appli|l'app|kis|keep it simple)?$", RegexOption.IGNORE_CASE)
        // « envoie le récap » ; « envoie un mail à Paul demain » reste une tâche
        val updateCmd = Regex("^(mets à jour|mise à jour|installe la mise à jour)\\b.*(application|appli|kis)\\b.*", RegexOption.IGNORE_CASE)
        val syncCmd = Regex("^(synchronise|synchroniser|synchro|mets à jour)\\b.*", RegexOption.IGNORE_CASE)
        val recap = Regex("^(envoie|envoyer|envoi)\\b.*(récap|récapitulatif|tâches en retard).*", RegexOption.IGNORE_CASE)
        val m = delete.find(t)
        val f = finish.find(t)
        val c = connect.find(t)
        when {
            updateCmd.matches(t) -> checkUpdate(force = true, announce = true)
            syncCmd.matches(t) -> sync(announce = true)
            recap.matches(t) -> {
                say("J'envoie le récapitulatif")
                Thread {
                    val msg = try { Recap.send(this) } catch (e: Exception) { "Récap non envoyé : " + GoogleTasks.failure(e, "Gmail") }
                    runOnUiThread { say(msg) }
                }.start()
            }
            close.matches(t) -> finishAndRemoveTask()
            help.matches(t) -> startActivity(Intent(this, OnboardingActivity::class.java))
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
                if (tasks.isEmpty()) "Aucune tâche" else tasks.joinToString(". ", "${tasks.size} tâches. ") { "${it.num} : ${spokenLabel(it)}" }
            )
            m != null -> {
                val task = find(tasks + doneTasks, m.groupValues[3])
                if (task == null) say("Je n'ai pas trouvé cette tâche") else delete(task)
            }
            // « termine le rapport vendredi » sans tâche correspondante = nouvelle tâche
            f != null && find(tasks, f.groupValues[3]) != null -> setDone(find(tasks, f.groupValues[3])!!, true)
            else -> {
                val task = store.add(VoiceParser.parse(t))
                Reminders.schedule(this, task)
                CloudSync.push(this, task, ::say)
                refresh()
                // Pas de relecture vocale (gain de temps) : seuls les problèmes sont annoncés, brièvement
                when {
                    task.due == null -> say("Pas de date")
                    task.due.isBefore(LocalDateTime.now()) -> say("Date passée")
                    else -> { heard.text = "Ajouté n°${task.num} : ${label(task)}"; relisten(200) }
                }
            }
        }
    }

    /** Par numéro (« la 3 ») ou par mots du titre. */
    private fun find(list: List<Task>, words: String): Task? {
        VoiceParser.taskNumber(words)?.let { n -> return list.firstOrNull { !it.done && it.num == n } }
        val w = words.trim().lowercase()
        if (w.isBlank()) return null
        return list.firstOrNull { w in it.title.lowercase() || it.title.lowercase() in w }
    }

    private fun delete(shownTask: Task, speak: Boolean = true) {
        val task = store.get(shownTask.id) ?: shownTask
        Reminders.cancel(this, task)
        store.remove(task.id)
        GoogleTasks.delete(this, task.gid, ::say)
        refresh()
        if (speak) say("Supprimé : ${task.title}")
        Snackbar.make(list, "Supprimé : ${task.title}", Snackbar.LENGTH_LONG)
            .setAction("Annuler") {
                // Supprimée côté Google : on la recrée
                val restored = store.add(task.copy(gid = null))
                Reminders.schedule(this, restored)
                GoogleTasks.push(this, restored, ::say)
                refresh()
            }
            .show()
    }

    private fun setDone(shownTask: Task, done: Boolean, speak: Boolean = true) {
        // Relire la tâche : l'identifiant Google a pu être enregistré après l'affichage de la liste
        val task = store.get(shownTask.id) ?: return
        if (done && task.repeat != null && task.due != null) {
            // Récurrente : archivée, et la même tâche passe à l'échéance suivante
            val next = TaskActions.complete(this, task)!!
            refresh()
            val msg = "Terminé. Prochaine : ${dueLabel(next.due).first}"
            if (speak) say(msg) else Snackbar.make(list, msg, Snackbar.LENGTH_LONG).show()
            return
        }
        // Remise à faire : nouveau numéro libre (num = 0 → attribué par le store)
        store.patch(task.id) { it.copy(done = done, num = if (done) it.num else 0) }
        val updated = store.all().first { it.id == task.id }
        if (done) Reminders.cancel(this, task) else Reminders.schedule(this, updated)
        GoogleTasks.push(this, updated, ::say)
        refresh()
        if (speak) say(if (done) "Terminé : ${task.title}" else "À faire : ${task.title}")
        Snackbar.make(list, (if (done) "Terminé : " else "À faire : ") + task.title, Snackbar.LENGTH_LONG)
            .setAction("Annuler") { setDone(updated, !done, speak = false) }
            .show()
    }

    // ---------- Modification manuelle ----------

    private fun edit(task: Task) {
        val wasPaused = paused
        pauseListening() // pas d'écoute pendant la saisie
        val v = layoutInflater.inflate(R.layout.dialog_edit, null)
        val title = v.findViewById<EditText>(R.id.editTitle).apply { setText(task.title) }
        val dateBtn = v.findViewById<MaterialButton>(R.id.editDate)
        val timeBtn = v.findViewById<MaterialButton>(R.id.editTime)
        // Répétition : choix courants, plus la valeur actuelle si elle est personnalisée (ex. tous les 3 jours)
        val repeatChoices = listOf(null, "D1", "W1", "M1", "Y1").let { if (task.repeat == null || task.repeat in it) it else it + task.repeat }
        val repeatSpinner = v.findViewById<Spinner>(R.id.editRepeat).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                repeatChoices.map { it?.let(Repeat::label) ?: "Ne se répète pas" })
            setSelection(repeatChoices.indexOf(task.repeat))
        }
        var date: LocalDate? = task.due?.toLocalDate()
        var time: LocalTime = task.due?.toLocalTime() ?: LocalTime.of(9, 0)
        fun show() {
            dateBtn.text = date?.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.FRENCH)) ?: "Choisir une date"
            timeBtn.text = time.format(DateTimeFormatter.ofPattern("HH:mm"))
            timeBtn.isEnabled = date != null
        }
        show()
        dateBtn.setOnClickListener {
            val d = date ?: LocalDate.now()
            DatePickerDialog(this, { _, y, m, day -> date = LocalDate.of(y, m + 1, day); show() },
                d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, min -> time = LocalTime.of(h, min); show() },
                time.hour, time.minute, true).show()
        }
        v.findViewById<MaterialButton>(R.id.editNoDate).setOnClickListener { date = null; show() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Modifier la tâche")
            .setView(v)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                store.patch(task.id) {
                    it.copy(
                        title = title.text.toString().trim().ifEmpty { task.title },
                        due = date?.atTime(time),
                        repeat = if (date == null) null else repeatChoices[repeatSpinner.selectedItemPosition]
                    )
                }
                val updated = store.get(task.id) ?: return@setPositiveButton
                Reminders.cancel(this, task)
                Reminders.schedule(this, updated)
                GoogleTasks.push(this, updated, ::say)
                refresh()
            }
            .setOnDismissListener { if (!wasPaused) resumeListening() }
            .show()
    }

    private fun label(t: Task) = t.title + (t.due?.let { " — " + dueLabel(it).first } ?: "")

    private fun spokenLabel(t: Task) = t.title + (t.due?.let { ", " + it.format(spoken) } ?: "")

    private fun refresh() {
        val all = store.all()
        tasks = all.filterNot { it.done }
        doneTasks = all.filter { it.done }.reversed() // les plus récentes en premier
        tabs.getTabAt(0)?.text = "À faire (${tasks.size})"
        tabs.getTabAt(1)?.text = "Terminées (${doneTasks.size})"
        empty.text = if (showDone) "Aucune tâche terminée." else "Aucune tâche.\nParlez simplement, je vous écoute."
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // ---------- Liste ----------

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val num: TextView = v.findViewById(R.id.num)
        val title: TextView = v.findViewById(R.id.title)
        val due: TextView = v.findViewById(R.id.due)
    }

    private inner class TaskAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(layoutInflater.inflate(R.layout.item_task, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val task = shown[position]
            val (label, colorRes) = if (task.done) "Fait" to R.color.listening else dueLabel(task.due)
            val color = ContextCompat.getColor(this@MainActivity, colorRes)
            h.num.text = task.num.toString()
            h.num.visibility = if (task.done) View.GONE else View.VISIBLE
            h.title.text = task.title
            h.title.alpha = if (task.done) 0.5f else 1f
            h.title.paintFlags = if (task.done) h.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else h.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = task.done
            h.check.buttonTintList = ColorStateList.valueOf(color)
            h.check.setOnCheckedChangeListener { _, checked -> setDone(task, checked, speak = false) }
            // Badge plein dessiné en code : texte blanc lisible sur tous les téléphones
            h.due.text = if (task.repeat != null && !task.done) "↻ $label" else label
            h.due.background = GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(color)
            }
            h.itemView.setOnClickListener { edit(task) }
        }
    }

    /** Glisser à droite : terminé (ou remis à faire), à gauche : supprimer. */
    private val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val task = shown[vh.bindingAdapterPosition]
            if (direction == ItemTouchHelper.RIGHT) setDone(task, !task.done, speak = false) else delete(task, speak = false)
        }

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
            val v = vh.itemView
            if (dX != 0f) {
                val right = dX > 0
                paint.color = ContextCompat.getColor(this@MainActivity, if (right) R.color.listening else R.color.late)
                val bottom = v.bottom - (v.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
                val r = 16 * resources.displayMetrics.density
                if (right) c.drawRoundRect(v.left.toFloat(), v.top.toFloat(), v.left + dX + r, bottom.toFloat(), r, r, paint)
                else c.drawRoundRect(v.right + dX - r, v.top.toFloat(), v.right.toFloat(), bottom.toFloat(), r, r, paint)
                textPaint.textSize = 15 * resources.displayMetrics.scaledDensity
                val label = if (!right) "Supprimer" else if (showDone) "À faire" else "✓ Terminé"
                val y = (v.top + bottom) / 2f + textPaint.textSize / 3
                val pad = 20 * resources.displayMetrics.density
                val x = if (right) v.left + pad else v.right - pad - textPaint.measureText(label)
                c.drawText(label, x, y, textPaint)
            }
            super.onChildDraw(c, rv, vh, dX, dY, state, active)
        }
    }

    private fun dueLabel(due: LocalDateTime?): Pair<String, Int> {
        if (due == null) return "Sans date" to R.color.muted
        val now = LocalDateTime.now()
        val time = due.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when {
            due.isBefore(now) -> "En retard · " + due.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)) to R.color.late
            due.toLocalDate() == now.toLocalDate() -> "Aujourd'hui · $time" to R.color.today
            due.toLocalDate() == now.toLocalDate().plusDays(1) -> "Demain · $time" to R.color.accent
            else -> due.format(shortDate) to R.color.muted
        }
    }
}
