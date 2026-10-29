package com.keepitsimple

import java.time.LocalDateTime

/**
 * [repeat] : récurrence « unité + intervalle », ex. "D1" chaque jour, "W2" toutes les 2 semaines,
 * "M1" chaque mois, "Y1" chaque année ; null = pas de répétition.
 * [gid] : identifiant de la tâche correspondante dans Google Tasks.
 */
data class Task(
    val id: Long,
    val title: String,
    val due: LocalDateTime?,
    val done: Boolean = false,
    val num: Int = 0,
    val repeat: String? = null,
    val gid: String? = null
)

object Repeat {
    fun next(due: LocalDateTime, repeat: String): LocalDateTime {
        val n = repeat.drop(1).toLongOrNull() ?: 1
        return when (repeat[0]) {
            'D' -> due.plusDays(n)
            'W' -> due.plusWeeks(n)
            'M' -> due.plusMonths(n)
            else -> due.plusYears(n)
        }
    }

    /** Prochaine occurrence strictement après [now]. */
    fun nextAfter(due: LocalDateTime, repeat: String, now: LocalDateTime): LocalDateTime {
        var d = due
        while (!d.isAfter(now)) d = next(d, repeat)
        return d
    }

    fun label(repeat: String): String {
        val n = repeat.drop(1).toIntOrNull() ?: 1
        val unit = when (repeat[0]) { 'D' -> "jour"; 'W' -> "semaine"; 'M' -> "mois"; else -> "an" }
        return when {
            n == 1 && repeat[0] == 'W' -> "Chaque semaine"
            n == 1 -> "Chaque $unit"
            repeat[0] == 'W' -> "Toutes les $n semaines"
            repeat[0] == 'M' -> "Tous les $n mois"
            else -> "Tous les $n ${unit}s"
        }
    }
}
