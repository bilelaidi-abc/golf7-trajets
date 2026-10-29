# Golf 7 Trajets — application Android

Enregistre chaque trajet de la Golf 7 avec le GPS du téléphone, **écran éteint compris** (service de localisation de premier plan avec notification « Trajet en cours »). Pas d'OBD dans cette version.

## Contenu
- `www/index.html` : l'interface de l'app (à modifier directement)
- `android/app/src/main/java/.../TripService.java` : enregistrement des trajets en arrière-plan (GPS, vibrations, calcul de conso)
- `web/` : version web (navigateur) de départ
- `android/` : projet Android (Capacitor 7)
- `.github/workflows/android.yml` : construit l'APK automatiquement sur GitHub

## Construire l'APK
- **Sur GitHub** : pousser ce dossier dans un dépôt, l'onglet Actions produit `golf7-trajets-apk` (fichier `app-debug.apk`).
- **Sur un PC** avec Android Studio : `npm ci`, `npx cap sync android`, puis ouvrir le dossier `android/` et lancer *Build > Build APK*.

## Installer sur le téléphone
Copier `app-debug.apk` sur le téléphone, l'ouvrir et autoriser « Installer des applis inconnues ». Au premier trajet, accepter la position et les notifications.

## Conseils
- Sur Samsung, Xiaomi, Huawei : désactiver l'optimisation de batterie pour l'app, sinon Android peut couper l'enregistrement après un long moment écran éteint.
- Si l'app est fermée en plein trajet, le trajet est repris à la réouverture (sauvegarde toutes les 15 s).

## Fonctionnement (v1.2)
- Démarrage automatique à la connexion Bluetooth de la voiture, même app fermée ; arrêt 5 s après la déconnexion. Vibration au début et à la fin.
- Aucun trajet minimum : un trajet voiture à l'arrêt est enregistré (consommation au ralenti).
- Consommation calculée par trajet : croisière selon la vitesse, temps au ralenti et énergie des accélérations. Ajustée automatiquement si tu saisis la conso de l'ordinateur de bord sur quelques trajets.
- Carte (OpenStreetMap / CARTO) en fond du tracé, villes de départ et d'arrivée (Photon).

## Relevé du tableau de bord (v1.3)
Onglet **Voiture** : photo du compteur, lecture du texte dans le téléphone (ML Kit, hors ligne), valeurs à vérifier puis enregistrées (kilométrage, conso moyenne, autonomie, vidange et inspection en km/jours). Décompte d'entretien avec les km des trajets, conso de l'ordinateur de bord utilisée pour ajuster le calcul, rappel hebdomadaire par notification.
