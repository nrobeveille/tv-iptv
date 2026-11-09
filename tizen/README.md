# IPTV pour TV Samsung (Tizen)

Version « appli web » de l'appli IPTV, pour les TV Samsung (Tizen 4 et plus, pensée pour une
Crystal UHD CU7100 de 2023 sous Tizen 7). Même fonctionnement que la version Android TV :

- **TV en direct** : catégories (★ Favoris, Récentes, Toutes, groupes de la playlist), chaînes
  avec le programme en cours, détail du programme à droite.
- **Films / Séries** : grilles d'affiches, saisons et épisodes, reprise de lecture, épisode
  suivant automatique.
- **Favoris**, **Rechercher**, **Guide TV** (XMLTV de la playlist, d'un compte Xtream ou saisi).
- Lecture par **AVPlay**, le lecteur matériel de Samsung (HLS, MPEG-TS, MP4, MKV…).

## Touches de la télécommande

| Dans les listes | |
|---|---|
| Flèches | se déplacer (le menu de gauche change de rubrique) |
| OK | ouvrir / regarder |
| OK **maintenu** | ajouter / retirer des favoris (plus d'options sur une playlist) |
| CH ▲▼ | page précédente / suivante |
| Retour | revenir en arrière |

| Lecteur — direct | |
|---|---|
| ▲ / CH+ · ▼ / CH− | chaîne suivante / précédente |
| OK ou ◀ | liste des chaînes |
| ▶ | bandeau du programme |
| OK maintenu (ou touche de couleur) | options : format d'image, audio, sous-titres, favori |
| Chiffres (touche 123) | aller à la chaîne n° … |

| Lecteur — films / séries | |
|---|---|
| OK / Lecture-Pause | pause / reprise |
| ◀ / ▶ | −10 s / +30 s |
| ▼ ou OK maintenu | options (audio, sous-titres, format) |

## Installer l'appli sur la TV (étape par étape)

Samsung n'autorise que les applis du store… sauf en **mode développeur** : on installe alors
l'appli depuis un PC, signée avec **ton propre certificat Samsung** (gratuit). C'est à faire une
fois ; les mises à jour se font ensuite en une commande.

### 1. Créer un compte Samsung

Si tu n'en as pas : <https://account.samsung.com>. Il sert à générer le certificat.

### 2. Installer Tizen Studio sur le PC

1. Télécharge **Tizen Studio** (version « with IDE installer ») :
   <https://developer.tizen.org/development/tizen-studio/download>.
   Installe-le dans `C:\tizen-studio` (le chemin par défaut).
2. À la fin, le **Package Manager** s'ouvre (sinon : `C:\tizen-studio\package-manager\package-manager.exe`).
3. Onglet **Main SDK** : installe **Tizen SDK tools**.
4. Onglet **Extension SDK** : installe **TV Extensions-7.0** (ta TV est en Tizen 7) et
   **Samsung Certificate Extension**.

### 3. Repérer les adresses IP

- **PC** : `ipconfig` dans un terminal → « Adresse IPv4 » (ex. `192.168.1.20`).
- **TV** : *Paramètres → Général → Réseau → État du réseau → Paramètres IP* (ex. `192.168.1.50`).

La TV et le PC doivent être sur le même réseau (même box).

### 4. Passer la TV en mode développeur

1. Sur la TV, ouvre **Applis** (Accueil → Applis).
2. Tape **1 2 3 4 5** avec la télécommande (touche **123** pour le pavé numérique).
3. Mets **Mode développeur** sur **Activé**, saisis l'**IP du PC**, valide.
4. **Redémarre la TV** : maintiens le bouton d'alimentation de la télécommande jusqu'à voir le
   logo Samsung (un simple veille/réveil ne suffit pas).
5. En rouvrant **Applis**, « Develop Mode » est affiché en haut : c'est bon.

### 5. Connecter la TV à Tizen Studio

1. Lance **Tizen Studio**, puis **Tools → Device Manager**.
2. **Remote Device Manager** → **+** → nom : `TV salon`, IP : celle de la TV, port `26101` → **Add**.
3. Active l'interrupteur **Connection** : la TV apparaît dans la liste de gauche.

(En ligne de commande : `C:\tizen-studio\tools\sdb connect IP_DE_LA_TV:26101`.)

### 6. Créer ton certificat Samsung

La TV doit être connectée (étape 5) : son identifiant (DUID) est alors ajouté tout seul au
certificat.

1. Dans Tizen Studio : **Tools → Certificate Manager** → **+**.
2. Choisis **Samsung** (pas « Tizen »), puis **TV**.
3. Nom du profil : **`tviptv`** (le script utilise ce nom par défaut).
4. **Create a new author certificate** : un nom (ex. `Nathan`) et un mot de passe →
   connecte-toi avec ton **compte Samsung** quand c'est demandé.
5. Certificat distributeur : laisse **Public**, vérifie que le **DUID de ta TV** est dans la
   liste (ajoute-le avec le bouton s'il n'y est pas) → **Finish**.
6. Retourne dans **Tools → Device Manager**, fais un **clic droit sur la TV** →
   **Permit to install applications** : ça envoie ton certificat à la TV (sans ça,
   l'installation échoue avec « Check certificate error »). À refaire si tu recrées le certificat.
7. **Sauvegarde le dossier** `C:\tizen-studio-data\keystore` : les mises à jour doivent être
   signées avec le même certificat auteur.

### 7. Installer l'appli

Télécharge `install-tizen.ps1` depuis la
[dernière release Tizen](https://github.com/nrobeveille/tv-iptv/releases) (ou prends-le dans
`tizen/scripts/`), puis dans PowerShell, dans le dossier du fichier :

```powershell
powershell -ExecutionPolicy Bypass -File .\install-tizen.ps1 -TvIp 192.168.1.50
```

Le script télécharge la dernière version sur GitHub, se connecte à la TV, signe l'appli avec
ton profil `tviptv`, l'installe et la lance. « IPTV » apparaît ensuite dans les applis de la
TV (tu peux l'épingler sur l'accueil).

Options : `-CertProfile autreNom`, `-Source C:\...\tv-iptv\tizen` (version locale, pour
développer), `-Wgt chemin\tv-iptv.wgt`, `-TizenStudio D:\tizen-studio`.

Sur macOS / Linux : `./install-tizen.sh 192.168.1.50`.

### 8. Ajouter ta playlist

Au premier lancement : **Ajouter une playlist** → saisis l'adresse M3U de ton abonnement. Pour
éviter de la taper à la télécommande, utilise l'appli **SmartThings** sur ton téléphone
(télécommande → clavier) : tu peux y coller l'adresse.

## Mises à jour

Chaque push sur `main` qui touche `tizen/` est compilé par GitHub Actions et publié comme
release `tizen-vX.Y.N` (avec le `.wgt` et les scripts). Dans **Paramètres → Mise à jour**,
l'appli compare sa version à la dernière release Tizen et t'avertit (pastille sur
« Paramètres »).

Une TV Samsung ne permet pas à une appli de s'installer elle-même : pour mettre à jour,
**relance simplement le script d'installation** (la TV doit rester en mode développeur avec
l'IP de ton PC). Les playlists, favoris et réglages sont conservés.

## Si ça coince

- **« TV non connectée »** : mode développeur activé avec la bonne IP du PC, TV *redémarrée*
  (bouton d'alimentation maintenu), pare-feu Windows qui bloque `sdb` (port 26101).
- **« Check certificate error » (118, -12)** à l'installation : d'abord, Device Manager → clic
  droit sur la TV → **Permit to install applications**, puis relance le script. Sinon :
  certificat « Tizen » au lieu de « Samsung », ou DUID de la TV absent du certificat
  distributeur → recrée le profil (étape 6) avec la TV connectée.
- **L'appli disparaît après une mise à jour de la TV** : relance le script (étape 7).
- **La playlist ne charge pas** : vérifie l'adresse dans un navigateur sur le PC. Certains
  fournisseurs bloquent les TV Samsung ; essaie l'adresse `get.php?…&type=m3u_plus&output=ts`.
- **Une chaîne ne se lance pas** : Paramètres → *Identifiant du lecteur (User-Agent)* →
  essaie « VLC », « IPTV Smarters » ou « Par défaut (TV) ».

## Développer / tester sur PC

L'interface fonctionne dans Chrome (la vidéo passe alors par la balise `<video>`, donc pas de
HLS ni de `.ts`) :

```
cd tizen && python3 -m http.server 8080   # puis http://localhost:8080 (flèches, Entrée, Échap)
```

La playlist doit alors être servie avec les en-têtes CORS ; sur la TV, l'appli empaquetée
n'a pas cette restriction (`<access origin="*">` dans `config.xml`).

## Structure

```
tizen/
├── config.xml          identité de l'appli, permissions, accès réseau
├── index.html          page unique (+ plan vidéo AVPlay)
├── css/app.css         interface 1920×1080
├── js/
│   ├── util.js         touches de la télécommande, stockage, formatage
│   ├── m3u.js          lecture de la playlist M3U (chaînes / films / séries)
│   ├── epg.js          guide TV XMLTV lu au fil de l'eau (gzip compris)
│   ├── player.js       lecteur AVPlay (ou <video> sur PC)
│   ├── ui.js           listes et grilles virtuelles, navigation, fenêtres
│   ├── player-screen.js écran de lecture
│   └── app.js          état, chargement, rubriques, paramètres, mises à jour
└── scripts/            installation sur la TV (Windows / macOS / Linux)
```
