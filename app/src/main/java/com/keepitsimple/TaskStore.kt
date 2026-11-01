package com.keepitsimple

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import org.json.JSONArray
import java.time.LocalDateTime
import java.util.concurrent.Callable

/** Stockage des tâches dans une base Room (SQLite) : écritures transactionnelles, une ligne par tâche. */
class TaskStore(context: Context) {
    private val db = KisDb.get(context)
    private val dao = db.tasks()

    fun all(): List<Task> = db.runInTransaction(Callable {
        val list = dao.all().map { it.task() }
        val numbered = withNumbers(list)
        numbered.forEachIndexed { i, t -> if (t != list[i]) dao.update(TaskRow.of(t)) }
        numbered.sortedWith(compareBy(nullsLast()) { it.due })
    })

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

    /** Version enregistrée la plus récente (la liste affichée peut ignorer l'identifiant Google reçu entre-temps). */
    fun get(id: Long): Task? = dao.get(id)?.task()

    /** Identifiant unique, même pour plusieurs tâches créées dans la même milliseconde. */
    fun newId() = maxOf(System.currentTimeMillis(), (dao.maxId() ?: 0) + 1)

    /** Ajoute la tâche et la renvoie avec son numéro. */
    fun add(task: Task): Task = db.runInTransaction(Callable {
        dao.insert(TaskRow.of(task.copy(num = 0)))
        all().first { it.id == task.id }
    })

    fun remove(id: Long) = dao.delete(id)

    fun update(task: Task) { dao.update(TaskRow.of(task)) }

    /** Modifie une seule tâche à partir de sa version la plus récente (atomique, sûr depuis un thread d'arrière-plan). */
    fun patch(id: Long, change: (Task) -> Task) = db.runInTransaction(Runnable {
        dao.get(id)?.let { dao.update(TaskRow.of(change(it.task()))) }
    })
}

@Entity(tableName = "tasks")
data class TaskRow(
    @PrimaryKey val id: Long,
    val title: String,
    val due: String?,
    val done: Boolean,
    val num: Int,
    val repeat: String?,
    val gid: String?
) {
    fun task() = Task(id, title, due?.let(LocalDateTime::parse), done, num, repeat, gid)

    companion object {
        fun of(t: Task) = TaskRow(t.id, t.title, t.due?.toString(), t.done, t.num, t.repeat, t.gid)
    }
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY id") fun all(): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE id = :id") fun get(id: Long): TaskRow?
    @Query("SELECT MAX(id) FROM tasks") fun maxId(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(row: TaskRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertAll(rows: List<TaskRow>)
    @Update fun update(row: TaskRow)
    @Query("DELETE FROM tasks WHERE id = :id") fun delete(id: Long)
}

/**
 * File d'envoi durable vers Google Tasks : [kind] « g_out » (tâche à envoyer, [ref] = id local)
 * ou « g_del » (suppression, [ref] = identifiant Google). Rejouée dans l'ordre de [created].
 */
@Entity(tableName = "outbox", primaryKeys = ["kind", "ref"])
data class OutboxRow(
    val kind: String,
    val ref: String,
    val created: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val error: String? = null
)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY created") fun list(kind: String): List<OutboxRow>
    @Query("SELECT COUNT(*) FROM outbox WHERE kind = :kind AND ref = :ref") fun count(kind: String, ref: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun add(row: OutboxRow)
    @Query("DELETE FROM outbox WHERE kind = :kind AND ref = :ref") fun remove(kind: String, ref: String)
    @Query("UPDATE outbox SET attempts = attempts + 1, error = :error WHERE kind = :kind AND ref = :ref")
    fun failed(kind: String, ref: String, error: String?)
}

@Database(entities = [TaskRow::class, OutboxRow::class], version = 1, exportSchema = false)
abstract class KisDb : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun outbox(): OutboxDao

    companion object {
        @Volatile private var instance: KisDb? = null

        fun get(c: Context): KisDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, KisDb::class.java, "kis.db")
                // Requêtes courtes sur quelques dizaines de lignes : appelées directement depuis l'interface
                .allowMainThreadQueries()
                .build().also { importLegacy(c, it); instance = it }
        }

        /** Reprise unique de l'ancien stockage (SharedPreferences, versions ≤ 0.10.1) : tâches et file d'envoi Google. */
        private fun importLegacy(c: Context, db: KisDb) {
            val cloud = c.getSharedPreferences("cloud", Context.MODE_PRIVATE)
            listOf("g_out", "g_del").forEach { kind ->
                val refs = cloud.getStringSet(kind, null) ?: return@forEach
                db.runInTransaction(Runnable { refs.forEach { db.outbox().add(OutboxRow(kind, it)) } })
                cloud.edit().remove(kind).commit()
            }
            val prefs = c.getSharedPreferences("tasks", Context.MODE_PRIVATE)
            val json = prefs.getString("list", null) ?: return
            val arr = JSONArray(json)
            val rows = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TaskRow(
                    o.getLong("id"), o.getString("title"),
                    o.optString("due").ifEmpty { null },
                    o.optBoolean("done", false),
                    o.optInt("num", 0),
                    o.optString("repeat").ifEmpty { null },
                    o.optString("gid").ifEmpty { null }
                )
            }
            db.runInTransaction(Runnable { db.tasks().insertAll(rows) })
            // Ancienne liste conservée sous un autre nom (secours), et plus jamais réimportée
            prefs.edit().putString("list_legacy", json).remove("list").commit()
        }
    }
}
