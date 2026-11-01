# KIS — Keep It Simple
Application pense bête 

L'app écoute en continu tant qu'elle est ouverte : il suffit de parler. Ex. :
- « rappelle-moi d'appeler Paul demain à 18h »
- « sortir le linge dans 20 minutes »
- « rendez-vous dentiste le 15 novembre à 14 heures »
- « sortir les poubelles ce soir »

Mots-clés : aujourd'hui, demain, après-demain, lundi…dimanche, semaine prochaine, week-end,
le 15/11, le 15 novembre, dans N minutes/heures/jours/semaines/mois, dans une demi-heure,
à 18h / 18h30, et quart, et demie, moins le quart, midi, minuit, matin, après-midi, soir.
Un nombre n'est lu comme une heure qu'avec « à / vers / pour / avant » ou « heures ».
L'app prévient à voix haute si aucune date n'est comprise ou si l'échéance est passée.
Sans heure : 9h. Notification à l'échéance.

Commandes : « lis mes tâches » (lecture vocale), « supprime appeler Paul », « ferme l'application », « pause » (toucher le micro pour reprendre).

Build : `./gradlew assembleDebug` — tests : `./gradlew testDebugUnitTest`.

## Synchronisation (optionnelle)

Dire « connecte Google » / « connecte Microsoft » (ou « déconnecte … »).
Chaque nouvelle tâche est alors aussi envoyée vers le service connecté.

**Google Tasks** (Google Keep n'a pas d'API pour les comptes personnels) :
1. console.cloud.google.com → nouveau projet → activer « Google Tasks API » et « Gmail API ».
2. Écran de consentement OAuth (externe, ajouter son compte en testeur).
3. Identifiants → ID client OAuth type **Android**, package `com.keepitsimple`, SHA-1 de la clé de signature
   (`./gradlew signingReport`).
Limite : Google Tasks ne garde que la date ; l'heure est mise dans les notes.

**Microsoft To Do** (désactivé pour le moment : `ENABLED = true` dans `CloudSync.kt` pour réactiver) :
1. portal.azure.com → App registrations → New : comptes « tout annuaire + comptes Microsoft personnels ».
2. Authentication → Add platform « Mobile and desktop » → URI personnalisée `com.keepitsimple://auth`.
3. API permissions → Microsoft Graph → Delegated → `Tasks.ReadWrite`.
4. Copier l'Application (client) ID dans `local.properties` : `MS_CLIENT_ID=...`, puis recompiler.

## Récapitulatif des tâches en retard (Gmail)

Une fois Google connecté, un e-mail listant les tâches en retard est envoyé chaque jour à 6h
à l'adresse du compte Google (rien n'est envoyé s'il n'y a pas de retard).
Envoi immédiat : dire « envoie le récap ».

## Tâches récurrentes

« prendre mes médicaments tous les jours à 8h », « sortir les poubelles tous les lundis à 20h »,
« arroser les plantes tous les 3 jours », « payer le loyer tous les mois le 5 »,
« anniversaire de Léa tous les ans le 3 mars », « fermer les volets tous les soirs ».
Une tâche récurrente terminée est archivée dans « Terminées » et repart à l'échéance suivante (même numéro).
La répétition se modifie aussi en touchant la tâche.

## Synchronisation Google Tasks dans les deux sens

KIS → Google : création, modification, terminée, suppression.
Google → KIS : à l'ouverture de l'app, toutes les heures, ou en disant « synchronise ».
Google Tasks ne gère pas les répétitions : chaque occurrence y apparaît comme une tâche simple.
Sans réseau, les envois vers Google sont mis en attente et rejoués à la synchronisation suivante ;
une modification locale en attente n'est pas écrasée par Google.

## Mise à jour automatique

KIS vérifie toutes les heures la dernière release GitHub et s'installe tout seul quand l'application
n'est pas à l'écran (ou quand on la quitte). « mets à jour l'application » force la vérification.
Une seule fois : autoriser KIS à installer des applications inconnues (l'app ouvre le réglage).
Android 12+ : silencieux à partir de la 2ᵉ mise à jour ; avant, une notification demande de confirmer.
