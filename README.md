# IPTV pour Android TV

Lecteur IPTV pour Google TV / Android TV (pensé pour le **Xiaomi TV Stick 4K**) : on lui donne
l'adresse d'une playlist **M3U** (compte IPTV) et on navigue comme dans une appli IPTV classique.

- **TV en direct** : catégories à gauche (★ Favoris, Récentes, Toutes les chaînes, puis les
  groupes de la playlist), chaînes au centre avec le programme en cours, détail à droite
  (programme en cours, description, à suivre).
- **Films** et **Séries** : grilles d'affiches par catégorie. Les comptes Xtream exportés en M3U
  (`get.php?…&type=m3u_plus`) sont triés automatiquement (`/movie/`, `/series/` dans l'URL) ;
  les épisodes sont regroupés par série et par saison (`S01 E03`, `Saison 1 Épisode 3`).
  Reprise là où on s'est arrêté, enchaînement automatique de l'épisode suivant.
- **Favoris** : appui long sur OK sur une chaîne, un film ou une série.
- **Rechercher** dans tout le contenu.
- **Guide TV (EPG)** : XMLTV de la playlist (`url-tvg`), celui d'un compte Xtream
  (`xmltv.php`, déduit tout seul) ou une adresse saisie. Compressé (`.xml.gz`) ou non.
- **Ajout depuis le téléphone** : la TV affiche un QR code ; on colle l'adresse de la playlist
  sur le téléphone (même Wi-Fi) au lieu de la taper à la télécommande.
- **Paramètres** : plusieurs playlists, actualisation, format d'image, User-Agent (VLC par
  défaut : beaucoup de fournisseurs refusent les lecteurs inconnus), reprise de la dernière
  chaîne au démarrage, **mise à jour depuis GitHub**.

Les playlists et le guide sont gardés en cache sur la TV (actualisés toutes les 24 h / 12 h,
ou à la demande).

> **TV Samsung ?** Elles tournent sous Tizen, pas Android : la version Samsung (appli web,
> lecteur AVPlay) est dans [`tizen/`](tizen/README.md), avec le guide d'installation pas à pas.

Stack : Kotlin, Jetpack Compose + Compose for TV, Media3 ExoPlayer (HLS, MPEG-TS, MP4, MKV…),
Coil, DataStore, ZXing (QR code).

## Touches dans le lecteur

| Direct | |
|---|---|
| ▲ / CH+ | chaîne suivante |
| ▼ / CH− | chaîne précédente |
| OK ou ◀ | liste des chaînes de la catégorie |
| ▶ ou Info | bandeau du programme |
| Chiffres | aller à la chaîne n° … |
| Menu | options : format d'image, audio, sous-titres, favori |
| Retour | quitter le lecteur |

| Films / séries | |
|---|---|
| OK / Lecture | pause / reprise |
| ◀ / ▶ | −10 s / +30 s |
| ▼ ou Menu | options : audio, sous-titres, format d'image |
| ⏭ / ⏮ | épisode suivant / précédent |

## Récupérer l'APK

Chaque push sur `main` est compilé par GitHub Actions et publié comme release `v1.0.N`
(N = numéro du build) avec `tv-iptv.apk`. Les 5 dernières versions sont conservées.
Les autres branches sont compilées (APK en artefact) mais pas publiées.

## Installer sur le stick

```
adb connect IP_DU_STICK:PORT
adb install -r tv-iptv.apk
adb shell appops set fr.nrobeveille.tviptv REQUEST_INSTALL_PACKAGES allow
```

« IPTV » apparaît dans la liste des applis. La dernière commande autorise l'appli à installer
ses propres mises à jour (sinon elle ouvre l'écran système où l'autoriser la première fois).

Autre option sans PC : télécharger l'APK de la dernière release avec une appli comme
*Downloader* sur la TV.

## Mises à jour

**Paramètres → Mise à jour** interroge l'API GitHub
(`/repos/nrobeveille/tv-iptv/releases/latest`). Si le numéro de la release est plus grand que
celui de la version installée, l'appli télécharge l'APK, vérifie que c'est bien elle, puis
l'installe et redémarre. Elle vérifie aussi en silence au démarrage ; une pastille apparaît
sur « Paramètres » quand une version est disponible.

Le dépôt doit rester **public** pour que l'appli lise les releases sans jeton (sinon : secret
`UPDATE_TOKEN` avec un jeton GitHub en lecture seule, intégré à l'APK).

### Clé de signature

Android n'accepte une mise à jour que si elle est signée avec la même clé. Les builds de `main`
sont signés avec la clé fournie par les secrets du dépôt
(*Settings → Secrets and variables → Actions*) :

- `SIGNING_KEYSTORE_BASE64` : le fichier `.jks` encodé en base64 ;
- `SIGNING_PASSWORD` : son mot de passe (alias de la clé : `tviptv`, modifiable avec le secret
  `SIGNING_KEY_ALIAS`).

Sans ces secrets, l'APK est compilé mais pas publié. **Garde une copie de la clé** : si elle
est perdue, il faudra désinstaller puis réinstaller l'appli une fois.

## Structure

```
app/src/main/java/fr/nrobeveille/tviptv/
├── MainActivity.kt        activité unique, pile d'écrans
├── IptvViewModel.kt       état, chargement playlist / guide, navigation, mises à jour
├── data/                  parseur M3U, téléchargement, guide XMLTV, réglages (DataStore)
├── remote/                mini serveur web pour l'ajout depuis le téléphone
├── update/                recherche, téléchargement et installation des mises à jour
└── ui/
    ├── home/              menu de gauche et rubriques
    ├── live/              TV en direct
    ├── vod/               films, séries, détail d'une série, favoris
    ├── search/            recherche
    ├── player/            lecteur (Media3)
    ├── settings/          paramètres, édition de playlist, QR code
    ├── components/        lignes, affiches, dialogues, gestion du focus
    └── theme/             couleurs
```
