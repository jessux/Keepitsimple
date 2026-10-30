package com.keepitsimple

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** Stockage simple en JSON dans les SharedPreferences. */
class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)

    fun all(): List<Task> {
        val list = load()
        val numbered = withNumbers(list)
        if (numbered != list) save(numbered)
        return numbered.sortedWith(compareBy(nullsLast()) { it.due })
    }

    /** Numéro court et unique pour chaque tâche à faire (le plus petit libre), pour la désigner à la voix. */
    private fun withNumbers(list: List<Task>): List<Task> {
        val used = mutableSetOf<Int>()
        val missing = list.indices.filter { i -> !list[i].done && !(list[i].num > 0 && used.add(list[i].num)) }
        val out = list.toMutableList()
        missing.forEach { i ->
            val n = generateSequence(1) { it + 1 }.first { it !in used }
            used += n
            out[i] = out[i].copy(num = n)
        }
        return out
    }

    private fun load(): List<Task> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(
                o.getLong("id"), o.getString("title"),
                o.optString("due").takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse),
                o.optBoolean("done", false),
                o.optInt("num", 0),
                o.optString("repeat").ifEmpty { null },
                o.optString("gid").ifEmpty { null }
            )
        }
    }

    /** Version enregistrée la plus récente (la liste affichée peut ignorer l'identifiant Google reçu entre-temps). */
    fun get(id: Long): Task? = load().firstOrNull { it.id == id }

    /** Identifiant unique, même pour plusieurs tâches créées dans la même milliseconde. */
    fun newId() = maxOf(System.currentTimeMillis(), (load().maxOfOrNull { it.id } ?: 0) + 1)

    /** Ajoute la tâche et la renvoie avec son numéro. */
    fun add(task: Task): Task {
        save(load() + task.copy(num = 0))
        return all().first { it.id == task.id }
    }

    fun remove(id: Long) = save(load().filterNot { it.id == id })

    fun update(task: Task) = save(load().map { if (it.id == task.id) task else it })

    /** Modifie une seule tâche à partir de sa version la plus récente (sûr depuis un thread d'arrière-plan). */
    @Synchronized
    fun patch(id: Long, change: (Task) -> Task) = save(load().map { if (it.id == id) change(it) else it })

    private fun save(tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("due", it.due?.toString() ?: "").put("done", it.done).put("num", it.num)
            .put("repeat", it.repeat ?: "").put("gid", it.gid ?: "")) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
