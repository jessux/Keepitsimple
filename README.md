# Keepitsimple
Application pense bête 

L'app écoute dès l'ouverture (ou bouton 🎤). Tout se fait à la voix, ex. :
- « rappelle-moi d'appeler Paul demain à 18h »
- « sortir le linge dans 20 minutes »
- « rendez-vous dentiste le 15 novembre à 14 heures »
- « sortir les poubelles ce soir »

Mots-clés : aujourd'hui, demain, après-demain, lundi…dimanche, le 15/11, le 15 novembre,
dans N minutes/heures/jours/semaines, à 18h / 18h30, midi, minuit, matin, après-midi, soir.
Sans heure : 9h. Notification à l'échéance.

Commandes : « lis mes tâches » (lecture vocale), « supprime appeler Paul ».

Build : `./gradlew assembleDebug` — tests : `./gradlew testDebugUnitTest`.

## Synchronisation (optionnelle)

Dire « connecte Google » / « connecte Microsoft » (ou « déconnecte … »).
Chaque nouvelle tâche est alors aussi envoyée vers le service connecté.

**Google Tasks** (Google Keep n'a pas d'API pour les comptes personnels) :
1. console.cloud.google.com → nouveau projet → activer « Google Tasks API ».
2. Écran de consentement OAuth (externe, ajouter son compte en testeur).
3. Identifiants → ID client OAuth type **Android**, package `com.keepitsimple`, SHA-1 de la clé de signature
   (`./gradlew signingReport`).
Limite : Google Tasks ne garde que la date ; l'heure est mise dans les notes.

**Microsoft To Do** (désactivé pour le moment : `ENABLED = true` dans `CloudSync.kt` pour réactiver) :
1. portal.azure.com → App registrations → New : comptes « tout annuaire + comptes Microsoft personnels ».
2. Authentication → Add platform « Mobile and desktop » → URI personnalisée `com.keepitsimple://auth`.
3. API permissions → Microsoft Graph → Delegated → `Tasks.ReadWrite`.
4. Copier l'Application (client) ID dans `local.properties` : `MS_CLIENT_ID=...`, puis recompiler.
