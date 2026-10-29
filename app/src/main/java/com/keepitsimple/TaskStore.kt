package com.keepitsimple

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** Stockage simple en JSON dans les SharedPreferences. */
class TaskStore(context: Context) {
    private val prefs = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)

    fun all(): List<Task> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(
                o.getLong("id"), o.getString("title"),
                o.optString("due").takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse),
                o.optBoolean("done", false)
            )
        }.sortedWith(compareBy(nullsLast()) { it.due })
    }

    fun add(task: Task) = save(all() + task)

    fun remove(id: Long) = save(all().filterNot { it.id == id })

    fun update(task: Task) = save(all().map { if (it.id == task.id) task else it })

    private fun save(tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("due", it.due?.toString() ?: "").put("done", it.done)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
