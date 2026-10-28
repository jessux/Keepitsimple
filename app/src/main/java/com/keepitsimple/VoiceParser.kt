package com.keepitsimple

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Transforme une phrase dictée (français) en tâche avec échéance.
 * Mots-clés reconnus :
 *  - jour : aujourd'hui, ce soir, demain, après-demain, lundi…dimanche,
 *           le 15 (novembre), le 15/11, dans N minutes|heures|jours|semaines
 *  - heure : à 18h, 18h30, 18:30, 18 heures 30, midi, minuit, matin, après-midi, soir
 * Sans heure précisée, l'échéance est fixée à 9h.
 */
object VoiceParser {

    private const val DEFAULT_HOUR = 9

    private val prefixes = Regex(
        "^(rappelle[- ]moi|rappel|n'oublie pas|pense à|tâche|ajoute|nouvelle tâche)\\s*(de |d'|qu'|que |:)?\\s*",
        RegexOption.IGNORE_CASE
    )

    private val weekdays = mapOf(
        "lundi" to DayOfWeek.MONDAY, "mardi" to DayOfWeek.TUESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY, "vendredi" to DayOfWeek.FRIDAY, "samedi" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY
    )

    private val months = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre"
    )

    private val relative = Regex("\\b(?:dans|d'ici)\\s+(\\d+|une?)\\s+(minutes?|min|heures?|h|jours?|semaines?)\\b", RegexOption.IGNORE_CASE)
    private val dayKeywords = Regex("\\b(après[- ]demain|demain|aujourd'hui)\\b", RegexOption.IGNORE_CASE)
    private val weekday = Regex("\\b(?:(?:ce|le|pour|jusqu'à)\\s+)?(${weekdays.keys.joinToString("|")})(?:\\s+prochain)?\\b", RegexOption.IGNORE_CASE)
    private val dateNumeric = Regex("\\b(?:le\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b", RegexOption.IGNORE_CASE)
    private val dateText = Regex("\\b(?:le\\s+)?(\\d{1,2}|1er)\\s+(${months.joinToString("|")})(?:\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)
    private val time = Regex("(?:(?:à|vers|pour)\\s+)?\\b(\\d{1,2})\\s*(?:h|heures?|:)\\s*(\\d{2})?\\b", RegexOption.IGNORE_CASE)
    private val namedTime = Regex("(?:à\\s+)?\\b(midi|minuit)\\b|(?:\\b(?:ce|le|du)\\s+|dans l')?\\b(matin|après-midi|soir)\\b", RegexOption.IGNORE_CASE)

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Task {
        var text = input.trim()
        var date: LocalDate? = null
        var hour: LocalTime? = null

        relative.find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: 1L
            val unit = m.groupValues[2].lowercase()
            val due = when {
                unit.startsWith("min") -> now.plusMinutes(n)
                unit.startsWith("h") -> now.plusHours(n)
                unit.startsWith("jour") -> now.plusDays(n)
                else -> now.plusWeeks(n)
            }
            text = text.removeRange(m.range)
            if (unit.startsWith("min") || unit.startsWith("h")) {
                return Task(System.currentTimeMillis(), clean(text), due.withSecond(0).withNano(0))
            }
            date = due.toLocalDate()
        }

        if (date == null) dayKeywords.find(text)?.let { m ->
            val k = m.groupValues[1].lowercase()
            date = when {
                k.startsWith("après") -> now.toLocalDate().plusDays(2)
                k == "demain" -> now.toLocalDate().plusDays(1)
                else -> now.toLocalDate()
            }
            text = text.removeRange(m.range)
        }

        if (date == null) weekday.find(text)?.let { m ->
            val dow = weekdays.getValue(m.groupValues[1].lowercase())
            date = now.toLocalDate().with(TemporalAdjusters.next(dow))
            text = text.removeRange(m.range)
        }

        if (date == null) dateNumeric.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            date = buildDate(now, day, month, year)
            if (date != null) text = text.removeRange(m.range)
        }

        if (date == null) dateText.find(text)?.let { m ->
            val day = if (m.groupValues[1] == "1er") 1 else m.groupValues[1].toInt()
            val month = months.indexOf(m.groupValues[2].lowercase()) + 1
            date = buildDate(now, day, month, m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt())
            if (date != null) text = text.removeRange(m.range)
        }

        time.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (h in 0..23 && min in 0..59) {
                hour = LocalTime.of(h, min)
                text = text.removeRange(m.range)
            }
        }

        namedTime.find(text)?.let { m ->
            val k = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
            if (hour == null) hour = when (k) {
                "midi" -> LocalTime.NOON
                "minuit" -> LocalTime.MIDNIGHT
                "matin" -> LocalTime.of(9, 0)
                "après-midi" -> LocalTime.of(14, 0)
                else -> LocalTime.of(19, 0)
            } else if (k != "matin" && hour!!.hour < 12) {
                hour = hour!!.plusHours(12) // « 7h du soir »
            }
            if (k == "soir" && date == null) date = now.toLocalDate()
            text = text.removeRange(m.range)
        }

        val due = when {
            date != null -> LocalDateTime.of(date, hour ?: LocalTime.of(DEFAULT_HOUR, 0))
            hour != null -> LocalDateTime.of(now.toLocalDate(), hour).let { if (it.isBefore(now)) it.plusDays(1) else it }
            else -> null
        }
        return Task(System.currentTimeMillis(), clean(text), due)
    }

    private fun buildDate(now: LocalDateTime, day: Int, month: Int, year: Int?): LocalDate? = try {
        val d = LocalDate.of(year ?: now.year, month, day)
        if (year == null && d.isBefore(now.toLocalDate())) d.plusYears(1) else d
    } catch (e: Exception) {
        null
    }

    private fun clean(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
            .replace(prefixes, "")
            .trim(' ', ',', '.', '-')
        return t.replaceFirstChar { it.uppercase() }.ifEmpty { "Tâche" }
    }
}
