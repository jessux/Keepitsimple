package com.keepitsimple

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: TaskStore
    private lateinit var adapter: ArrayAdapter<String>
    private var tasks = listOf<Task>()
    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", Locale.FRENCH)

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(::addFromText)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = TaskStore(this)

        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val input = findViewById<EditText>(R.id.input)
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE && input.text.isNotBlank()) {
                addFromText(input.text.toString()); input.text.clear(); true
            } else false
        }

        findViewById<Button>(R.id.mic).setOnClickListener {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ex : rappelle-moi d'acheter du pain demain à 18h")
            try { speech.launch(i) } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Reconnaissance vocale indisponible", Toast.LENGTH_LONG).show()
            }
        }

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

    private fun addFromText(text: String) {
        val task = VoiceParser.parse(text)
        store.add(task)
        Reminders.schedule(this, task)
        Toast.makeText(this, "Ajouté : ${label(task)}", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun label(t: Task) = t.title + (t.due?.let { " — " + it.format(fmt) } ?: "")

    private fun refresh() {
        tasks = store.all()
        adapter.clear()
        adapter.addAll(tasks.map(::label))
    }
}
