package com.keepitsimple

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Transforme une phrase dictée (français) en tâche avec échéance.
 * Mots-clés reconnus :
 *  - jour : aujourd'hui, ce soir, demain, après-demain, lundi…dimanche, semaine prochaine,
 *           week-end, le 15 (novembre), le 15/11, dans N minutes|heures|jours|semaines|mois,
 *           dans une demi-heure, dans un quart d'heure
 *  - heure : à 18h, 18h30, 18:30, 18 heures 30, midi, minuit, matin, après-midi, soir,
 *           + et quart, et demie, moins le quart, moins N
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

    private const val FRACTION = "(?:\\s+(et quart|et demie?|moins le quart|moins (\\d{1,2})))?"

    private val relative = Regex(
        "\\b(?:dans|d'ici)\\s+(?:(une demi-heure)|(un quart d'heure)|(\\d+|une?)\\s+(minutes?|min|heures?|jours?|semaines?|mois)(\\s+et demie?)?)\\b",
        RegexOption.IGNORE_CASE
    )
    private val dayKeywords = Regex("\\b(après[- ]demain|demain|aujourd'hui)\\b", RegexOption.IGNORE_CASE)
    private val nextWeek = Regex("\\b(?:(?:la|pour la)\\s+)?semaine prochaine\\b", RegexOption.IGNORE_CASE)
    private val weekend = Regex("\\b(?:(?:ce|le|pour le)\\s+)?week[- ]?end(?:\\s+prochain)?\\b", RegexOption.IGNORE_CASE)
    private val weekday = Regex("\\b(?:(?:ce|le|pour|jusqu'à)\\s+)?(${weekdays.keys.joinToString("|")})(?:\\s+prochain)?\\b", RegexOption.IGNORE_CASE)
    private val dateNumeric = Regex("\\b(?:le\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b", RegexOption.IGNORE_CASE)
    private val dateText = Regex("\\b(?:le\\s+)?(\\d{1,2}|1er)\\s+(${months.joinToString("|")})(?:\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE)

    // Un nombre n'est une heure que s'il est précédé de « à / vers / pour / avant / dès »,
    // suivi de « heure(s) », ou écrit 18h30 / 18:30.
    private val time = Regex(
        "(?:(?:\\b(?:vers|pour|avant|dès)|à)\\s+(\\d{1,2})\\s*(?:h|heures?|:)(?:\\s*(\\d{2}))?" +
            "|\\b(\\d{1,2})\\s*heures?(?:\\s*(\\d{2}))?" +
            "|\\b(\\d{1,2})\\s*[h:]\\s*(\\d{2}))\\b$FRACTION",
        RegexOption.IGNORE_CASE
    )
    private val namedTime = Regex(
        "(?:à\\s+)?\\b(midi|minuit)\\b$FRACTION|(?:\\b(ce|cet|le|du)\\s+|dans l')?\\b(matin|après-midi|soir)\\b",
        RegexOption.IGNORE_CASE
    )

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Task {
        var text = input.trim()
        var date: LocalDate? = null
        var hour: LocalTime? = null

        relative.find(text)?.let { m ->
            val g = m.groupValues
            val unit = g[4].lowercase()
            val n = g[3].toLongOrNull() ?: 1L
            val due = when {
                g[1].isNotEmpty() -> now.plusMinutes(30)
                g[2].isNotEmpty() -> now.plusMinutes(15)
                unit.startsWith("min") -> now.plusMinutes(n)
                unit.startsWith("h") -> now.plusHours(n).plusMinutes(if (g[5].isNotEmpty()) 30 else 0)
                unit.startsWith("jour") -> now.plusDays(n)
                unit == "mois" -> now.plusMonths(n)
                else -> now.plusWeeks(n)
            }
            text = text.removeRange(m.range)
            if (g[1].isNotEmpty() || g[2].isNotEmpty() || unit.startsWith("min") || unit.startsWith("h")) {
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

        if (date == null) nextWeek.find(text)?.let { m ->
            date = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            text = text.removeRange(m.range)
        }

        if (date == null) weekend.find(text)?.let { m ->
            date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
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
            val g = m.groupValues
            val h = (g[1].ifEmpty { g[3] }.ifEmpty { g[5] }).toInt()
            val min = g[2].ifEmpty { g[4] }.ifEmpty { g[6] }.ifEmpty { "0" }.toInt()
            if (h in 0..23 && min in 0..59) {
                hour = withFraction(LocalTime.of(h, min), g[7], g[8])
                text = text.removeRange(m.range)
            }
        }

        namedTime.find(text)?.let { m ->
            val g = m.groupValues
            val k = g[1].ifEmpty { g[5] }.lowercase()
            if (hour == null) hour = when (k) {
                "midi" -> withFraction(LocalTime.NOON, g[2], g[3])
                "minuit" -> withFraction(LocalTime.MIDNIGHT, g[2], g[3])
                "matin" -> LocalTime.of(9, 0)
                "après-midi" -> LocalTime.of(14, 0)
                else -> LocalTime.of(19, 0)
            } else if (k != "matin" && hour!!.hour < 12) {
                hour = hour!!.plusHours(12) // « 7h du soir »
            }
            // « ce matin / ce soir / cet après-midi » = aujourd'hui
            if (date == null && (k == "soir" || g[4].lowercase() in setOf("ce", "cet"))) date = now.toLocalDate()
            text = text.removeRange(m.range)
        }

        val due = when {
            date != null -> LocalDateTime.of(date, hour ?: LocalTime.of(DEFAULT_HOUR, 0))
            hour != null -> LocalDateTime.of(now.toLocalDate(), hour).let { if (it.isBefore(now)) it.plusDays(1) else it }
            else -> null
        }
        return Task(System.currentTimeMillis(), clean(text), due)
    }

    private fun withFraction(t: LocalTime, frac: String, minus: String): LocalTime = when {
        frac.isEmpty() -> t
        frac.lowercase() == "et quart" -> t.plusMinutes(15)
        frac.lowercase().startsWith("et demi") -> t.plusMinutes(30)
        frac.lowercase() == "moins le quart" -> t.minusMinutes(15)
        else -> t.minusMinutes(minus.toLong())
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
