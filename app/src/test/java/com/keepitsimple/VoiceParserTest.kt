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

    @Test fun etQuart() = check("réunion à 9 heures et quart", "Réunion", LocalDateTime.of(2026, 10, 29, 9, 15))
    @Test fun midiEtDemi() = check("acheter du lait à midi et demi", "Acheter du lait", LocalDateTime.of(2026, 10, 28, 12, 30))
    @Test fun moinsLeQuart() = check("appeler Paul demain à 18h moins le quart", "Appeler Paul", LocalDateTime.of(2026, 10, 29, 17, 45))
    @Test fun demiHeure() = check("sortir le gâteau dans une demi-heure", "Sortir le gâteau", LocalDateTime.of(2026, 10, 28, 10, 30))
    @Test fun heureEtDemie() = check("rappel dans 1 heure et demie", "Tâche", LocalDateTime.of(2026, 10, 28, 11, 30))
    @Test fun dansMois() = check("contrôle technique dans 3 mois", "Contrôle technique", LocalDateTime.of(2027, 1, 28, 9, 0))
    @Test fun semaineProchaine() = check("faire les courses la semaine prochaine", "Faire les courses", LocalDateTime.of(2026, 11, 2, 9, 0))
    @Test fun weekEnd() = check("ranger le garage ce week-end", "Ranger le garage", LocalDateTime.of(2026, 10, 31, 9, 0))
    @Test fun nombreSansHeure() = check("réviser le chapitre 12 demain", "Réviser le chapitre 12", LocalDateTime.of(2026, 10, 29, 9, 0))
    @Test fun quantite() = check("acheter 3 kg de pommes", "Acheter 3 kg de pommes", null)
    @Test fun ceMatinAujourdhui() = check("appeler Paul ce matin", "Appeler Paul", LocalDateTime.of(2026, 10, 28, 9, 0))
    @Test fun dateInvalide() = check("fin du projet le 31/02", "Fin du projet le 31/02", null)

    @Test fun numeros() {
        assertEquals(3, VoiceParser.taskNumber("la 3"))
        assertEquals(3, VoiceParser.taskNumber("la tâche numéro trois"))
        assertEquals(1, VoiceParser.taskNumber("la une"))
        assertEquals(12, VoiceParser.taskNumber("numéro 12"))
        assertEquals(null, VoiceParser.taskNumber("les courses"))
        assertEquals(null, VoiceParser.taskNumber("le rapport 3"))
    }

    private fun checkRepeat(input: String, title: String, due: LocalDateTime, repeat: String) {
        val t = VoiceParser.parse(input, now)
        assertEquals(title, t.title)
        assertEquals(due, t.due)
        assertEquals(repeat, t.repeat)
    }

    @Test fun tousLesJours() = checkRepeat("prendre mes médicaments tous les jours à 8h", "Prendre mes médicaments", LocalDateTime.of(2026, 10, 29, 8, 0), "D1")
    @Test fun tousLesLundis() = checkRepeat("sortir les poubelles tous les lundis à 20h", "Sortir les poubelles", LocalDateTime.of(2026, 11, 2, 20, 0), "W1")
    @Test fun chaqueMercredi() = checkRepeat("piscine chaque mercredi à 18h", "Piscine", LocalDateTime.of(2026, 10, 28, 18, 0), "W1")
    @Test fun tousLes3Jours() = checkRepeat("arroser les plantes tous les 3 jours", "Arroser les plantes", LocalDateTime.of(2026, 10, 31, 9, 0), "D3")
    @Test fun tousLesMoisLe5() = checkRepeat("payer le loyer tous les mois le 5", "Payer le loyer", LocalDateTime.of(2026, 11, 5, 9, 0), "M1")
    @Test fun tousLesSoirs() = checkRepeat("fermer les volets tous les soirs", "Fermer les volets", LocalDateTime.of(2026, 10, 28, 19, 0), "D1")
    @Test fun anniversaire() = checkRepeat("anniversaire de Léa tous les ans le 3 mars", "Anniversaire de Léa", LocalDateTime.of(2027, 3, 3, 9, 0), "Y1")

    @Test fun versions() {
        assertEquals(true, Updater.isNewer("0.10", "0.9"))
        assertEquals(true, Updater.isNewer("0.10.1", "0.10"))
        assertEquals(true, Updater.isNewer("1.0", "0.9"))
        assertEquals(false, Updater.isNewer("0.6", "0.6"))
        assertEquals(false, Updater.isNewer("0.5", "0.6"))
    }
}
