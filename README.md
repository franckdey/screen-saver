# PhotoDream – économiseur d'écran photos pour Android TV

Un économiseur d'écran inspiré de Windows Media Center : des vignettes floutées, de tailles
variées et irrégulièrement espacées, dérivent lentement dans tous les sens en arrière-plan,
et une photo à la fois sort de la mosaïque, grandit
jusqu'au plein écran, reste affichée quelques secondes (avec un léger zoom lent), puis
retourne à sa place avant que la suivante ne prenne le relais.

Les photos sont lues dans un **dossier partagé sur le réseau (SMB)** : NAS Synology/QNAP,
Freebox, box, PC Windows, etc. Elles sont gardées en cache sur la TV, réduites à la taille
de l'écran, donc les passages suivants sont instantanés et l'économiseur continue de
fonctionner même si le NAS est en veille.

---

## 1. Obtenir le fichier APK

Trois possibilités, de la plus simple à la plus « bricolage » :

### a) GitHub compile tout seul (recommandé)

Le projet contient un « workflow » GitHub Actions (`.github/workflows/build.yml`).
Dès que le code est poussé sur la branche `main` d'un dépôt GitHub, GitHub compile l'APK
gratuitement et le publie dans l'onglet **Releases** du dépôt, à l'adresse :

```
https://github.com/<ton-compte>/<nom-du-depot>/releases/latest/download/PhotoDream.apk
```

Ce lien direct est pratique pour l'installer depuis la TV avec l'appli *Downloader*
(voir plus bas). Attention : il ne marche que si le dépôt est **public**. Avec un dépôt
privé, télécharge l'APK depuis l'onglet *Actions* sur un ordinateur, puis passe par adb ou
une clé USB.

### b) Android Studio sur un ordinateur

1. Installer [Android Studio](https://developer.android.com/studio).
2. *File › Open…* et choisir le dossier `PhotoDream`.
3. Attendre la synchronisation Gradle, puis *Build › Build App Bundle(s) / APK(s) › Build APK(s)*.
4. L'APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`.

### c) En ligne de commande

Avec le JDK 17 et le SDK Android installés (variable `ANDROID_HOME` définie) :

```
./gradlew assembleRelease
```

L'APK est produit dans `app/build/outputs/apk/release/app-release.apk`.

> L'APK est signé avec la clé fournie dans `app/photodream.keystore`. Quelle que soit
> la méthode utilisée, les nouvelles versions s'installent donc par-dessus l'ancienne sans
> perdre les réglages.

---

## 2. Installer l'APK sur la TV

### Préparer la TV (une seule fois)

1. **Paramètres › Préférences de l'appareil › À propos** (sur Google TV :
   *Paramètres › Système › À propos*), puis appuyer **7 fois sur « Build »** :
   le mode développeur est activé.
2. Dans **Options pour les développeurs**, activer **Débogage USB** (et
   **Débogage réseau / ADB sans fil** s'il existe) si tu comptes utiliser adb.

### Méthode 1 : l'appli Downloader (sans ordinateur)

1. Installer **Downloader** (by AFTVnews) depuis le Play Store de la TV.
2. Autoriser Downloader à installer des applis inconnues :
   *Paramètres › Applications › Sécurité et restrictions › Sources inconnues › Downloader*.
3. Ouvrir Downloader, taper le lien direct de l'APK (voir 1.a), valider, puis **Installer**.

### Méthode 2 : adb depuis un ordinateur

Avec les [platform-tools Android](https://developer.android.com/tools/releases/platform-tools)
installés sur le PC, et l'adresse IP de la TV (*Paramètres › Réseau*) :

```
adb connect 192.168.1.50
adb install -r PhotoDream.apk
```

La première fois, la TV demande d'autoriser l'ordinateur : accepter.

### Méthode 3 : clé USB

Copier l'APK sur une clé USB, la brancher sur la TV et l'ouvrir avec un gestionnaire de
fichiers (par exemple *X-plore* ou *File Commander* depuis le Play Store), en autorisant
ce gestionnaire à installer des applis inconnues.

---

### Mettre à jour

Chaque modification fusionnée dans `main` publie une nouvelle version (numérotée 1.N) dans
la Release *latest*. Au démarrage, l'écran de réglages de PhotoDream vérifie s'il en existe
une plus récente et affiche alors un bouton vert **« Installer la mise à jour »** ; le bouton
**« Rechercher une mise à jour »** fait la même vérification à la demande.

La première fois, Android demande d'autoriser PhotoDream à installer des applis
(*Sources inconnues*) : l'appli ouvre directement ce réglage quand la TV le propose, sinon
il se trouve dans *Paramètres › Applications › Sécurité et restrictions › Sources inconnues*.
Ensuite il suffit de confirmer l'installation. Les réglages sont conservés.

> Les versions installées avant l'arrivée de cette fonction ne savent pas se mettre à jour
> seules : il faut les remplacer une dernière fois à la main (Downloader, même lien).

---

## 3. Configurer

Ouvrir **PhotoDream** depuis l'écran d'accueil de la TV (rangée des applis) :

| Réglage | Exemple |
|---|---|
| Dossier réseau | `smb://192.168.1.20/photo/Vacances` ou `\\NAS\photo\Vacances` |
| Utilisateur | compte du NAS (vide = accès invité) |
| Mot de passe | mot de passe de ce compte |
| Domaine | presque toujours vide |

Le chemin se lit ainsi : `smb://` + **adresse du NAS** + **nom du partage** + dossier(s).
Quelques exemples courants :

- **Synology** : `smb://192.168.1.20/photo` (dossier partagé *photo*)
- **Freebox** : `smb://192.168.1.254/Freebox/Photos` (activer le partage Windows dans
  *Paramètres de la Freebox › Partage de fichiers*)
- **PC Windows** : `smb://192.168.1.30/Photos` après avoir partagé le dossier
  (*clic droit › Propriétés › Partage*), avec le compte Windows.

Ensuite :

1. **Tester la connexion** : indique le nombre de photos trouvées ou la cause de l'erreur.
2. **Lancer le diaporama** pour voir le rendu (Retour pour quitter).
3. Ajuster la durée d'affichage, la densité des vignettes, le flou, l'heure…

La liste des photos est mémorisée : le diaporama démarre tout de suite, même avec des
milliers de photos, puis relit le dossier en arrière-plan pour prendre en compte les photos
ajoutées ou supprimées. Changer de dossier (ou « Vider le cache ») repart d'une liste neuve.

Formats pris en charge : JPEG, PNG, WebP, BMP, et HEIC à partir d'Android 9.
Les dossiers techniques (`@eaDir` de Synology, `#recycle`, fichiers cachés) sont ignorés.

### Pendant le diaporama

| Touche | Effet |
|---|---|
| OK (ou Lecture/Pause) | fixe la photo en plein écran ; OK à nouveau relance le défilement, sinon il reprend tout seul au bout d'une minute |
| Flèche droite | passe tout de suite à la photo suivante |
| Flèche gauche | revient à la photo précédente ; chaque appui remonte d'une photo de plus, puis le diaporama reprend la suite |
| Retour, Accueil ou autre touche | quitte l'économiseur |

Sur téléphone, un appui sur l'écran fixe ou relance la photo.

### Diaporama simple

Au lieu de la mosaïque animée, PhotoDream peut montrer un **diaporama simple** : les photos
entières en plein écran, l'une après l'autre, dossier par dossier et par nom, avec un fondu
rapide entre deux photos.

- Bouton **« Lancer le diaporama simple »** dans les réglages, à côté de « Lancer le diaporama ».
- Réglage **« Économiseur d'écran : mosaïque animée / diaporama simple »** pour choisir ce que
  l'économiseur affiche.
- Réglage **« Durée par photo dans le diaporama simple »** (3 à 30 s, 5 s par défaut).

| Touche ou appui | Effet |
|---|---|
| Flèche droite, ou appui sur le tiers droit de l'écran | photo suivante |
| Flèche gauche, ou appui sur le tiers gauche de l'écran | photo précédente |
| OK, ou appui au milieu de l'écran | pause / reprise |
| Retour | quitte |

Le diaporama reprend là où il s'était arrêté la fois précédente. Le lieu et la date (ou le
dossier et le nom) s'affichent en bas à gauche, la position (« 12 / 340 ») en bas à droite.
Les photos regardées comptent comme vues pour la mosaïque, comme d'habitude.

---

## 4. Choisir PhotoDream comme économiseur d'écran

### Android TV « classique » (Sony, Philips, Nvidia Shield…)

*Paramètres › Préférences de l'appareil › Économiseur d'écran › Économiseur d'écran* et
choisir **PhotoDream – photos du NAS**. Le bouton « Choisir comme économiseur » de l'appli
ouvre directement ce menu. Régler aussi le délai (*Démarrer après*).

### Google TV (Chromecast avec Google TV, TV récentes TCL/Sony/Hisense…)

Google TV ne propose que son propre « Mode Ambiant » dans les menus. Il faut donc choisir
PhotoDream une fois avec adb, depuis un ordinateur :

```
adb connect 192.168.1.50
adb shell settings put secure screensaver_components com.maxime.photodream/.PhotoDreamService
adb shell settings put secure screensaver_enabled 1
```

Pour le tester tout de suite, sans attendre :

```
adb shell am start -n com.android.systemui/.Somnambulator
```

Après une mise à jour système de la TV, il peut être nécessaire de relancer la commande.

---

## 5. Dépannage

| Message | Que faire |
|---|---|
| NAS injoignable | Vérifier que le NAS est allumé, sur le même réseau, et que le service SMB/« partage Windows » est activé. Utiliser son adresse IP plutôt que son nom. |
| Identifiant ou mot de passe refusé | Vérifier le compte ; sur Synology, l'utilisateur doit avoir le droit de lecture sur le dossier. |
| Partage introuvable | Le 2ᵉ élément du chemin doit être le **nom du partage** tel que le NAS l'affiche. |
| Aucune photo trouvée | Cocher « Inclure les sous-dossiers » ou vérifier le dossier. |
| Écran noir avec seulement un message | Lire le message affiché : c'est la cause exacte de l'erreur. |

Les très vieux NAS qui ne parlent que **SMB1** ne sont pas pris en charge : activer SMB2 ou
SMB3 dans leurs réglages (c'est le cas par défaut sur tous les modèles récents).

---

## 6. Tester sur un téléphone Android

Le même APK s'installe sur un téléphone ou une tablette (Android 8 minimum) :

1. Télécharger `PhotoDream.apk` sur le téléphone (lien de la Release, ou fichier envoyé
   par mail / Drive) et l'ouvrir. Android demande d'autoriser l'installation depuis le
   navigateur ou le gestionnaire de fichiers : accepter.
2. Le téléphone doit être **connecté au Wi-Fi de la maison** (pas en 4G/5G), sinon le NAS
   est injoignable.
3. Ouvrir PhotoDream, remplir le dossier réseau, **Tester la connexion**, puis **Lancer le
   diaporama**. Un appui sur l'écran fixe ou relance la photo, le bouton Retour quitte.

En bonus, sur beaucoup de téléphones (Pixel, Motorola, Xiaomi…), PhotoDream peut servir
d'économiseur pendant la charge : *Paramètres › Affichage › Économiseur d'écran*. Samsung
a retiré ce menu sur ses modèles récents.

---

## Tests

Chaque pull request lance le workflow **Tests** (`.github/workflows/tests.yml`) :

- **tests unitaires** : lecture de l'adresse SMB, formats reconnus, messages d'erreur, nom de
  dossier affiché, minuterie de l'animation (zoom, photo fixée, reprise après une minute,
  photo suivante), mise à jour intégrée (lecture de `version.json`, comparaison des versions,
  téléchargement de l'APK à travers une redirection comme celle de GitHub) ;
- **tests Robolectric** (Android simulé sur la JVM) : réduction des photos en Full HD, remise à
  l'endroit d'après l'EXIF, flou des vignettes, cache, ordre sans répétition, mode hors ligne ;
- **tests d'intégration** contre un vrai serveur Samba démarré dans GitHub Actions : liste
  récursive, dossiers ignorés (`@eaDir`, fichiers cachés), lecture, mauvais mot de passe,
  partage ou dossier introuvable ;
- **lint** Android puis compilation de l'APK.

En local : `./gradlew testDebugUnitTest` (les tests Samba sont ignorés sans la variable
`SMB_TEST_HOST`).

---

## Organisation du code

- `SlideshowView.kt` : le dessin de l'animation (vignettes, zoom, cadre, textes).
- `Timeline.kt` : le déroulé de chaque photo, la pause et la reprise (sans dessin, testé).
- `PhotoPlayer.kt` : l'ordre et la minuterie du diaporama simple (sans dessin, testé).
- `PhotoDreamService.kt` : l'économiseur d'écran Android (*DreamService*).
- `SlideshowActivity.kt` : le diaporama lancé à la main.
- `SettingsActivity.kt` : l'écran de réglages pour la télécommande.
- `PhotoSource.kt`, `SmbPhotoSource.kt` : connexion au partage SMB (bibliothèque *smbj*).
- `Updater.kt`, `ApkInstaller.kt` : mise à jour intégrée (vérification sur GitHub,
  téléchargement, installation).
- `PhotoRepository.kt`, `Images.kt` : ordre aléatoire, téléchargement, cache, réduction,
  rotation EXIF et flou des vignettes.
