package com.keepitsimple

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class VoiceParserTest {
    // Mercredi 28 octobre 2026, 10h00
    private val now = LocalDateTime.of(2026, 10, 28, 10, 0)

    private fun check(input: String, title: String, due: LocalDateTime?) {
        val t = VoiceParser.parse(input, now)
        assertEquals(title, t.title)
        assertEquals(due, t.due)
    }

    @Test fun demainAHeure() = check("rappelle-moi d'appeler Paul demain à 18h", "Appeler Paul", LocalDateTime.of(2026, 10, 29, 18, 0))
    @Test fun jourSemaine() = check("acheter du pain vendredi à 8h30", "Acheter du pain", LocalDateTime.of(2026, 10, 30, 8, 30))
    @Test fun dansMinutes() = check("sortir le linge dans 20 minutes", "Sortir le linge", LocalDateTime.of(2026, 10, 28, 10, 20))
    @Test fun dansJours() = check("payer la facture dans 3 jours", "Payer la facture", LocalDateTime.of(2026, 10, 31, 9, 0))
    @Test fun dateTexte() = check("tâche rendez-vous dentiste le 15 novembre à 14 heures", "Rendez-vous dentiste", LocalDateTime.of(2026, 11, 15, 14, 0))
    @Test fun dateNumerique() = check("anniversaire de Léa le 3/01", "Anniversaire de Léa", LocalDateTime.of(2027, 1, 3, 9, 0))
    @Test fun ceSoir() = check("n'oublie pas de sortir les poubelles ce soir", "Sortir les poubelles", LocalDateTime.of(2026, 10, 28, 19, 0))
    @Test fun heureSeuleDejaPassee() = check("prendre médicament à 8h", "Prendre médicament", LocalDateTime.of(2026, 10, 29, 8, 0))
    @Test fun midi() = check("déjeuner avec Marc après-demain à midi", "Déjeuner avec Marc", LocalDateTime.of(2026, 10, 30, 12, 0))
    @Test fun sansEcheance() = check("acheter des piles", "Acheter des piles", null)
}
