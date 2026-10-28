# Golf 7 Trajets — application Android

Enregistre chaque trajet de la Golf 7 avec le GPS du téléphone, **écran éteint compris** (service de localisation de premier plan avec notification « Trajet en cours »). Pas d'OBD dans cette version.

## Contenu
- `www/index.html` : l'interface de l'app (générée par `python3 tools/make_www.py web/app.html`)
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
