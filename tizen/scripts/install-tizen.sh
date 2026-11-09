#!/usr/bin/env bash
# Installe (ou met à jour) l'appli IPTV sur une TV Samsung depuis macOS / Linux.
#
#   ./install-tizen.sh 192.168.1.50                 # dernière version publiée sur GitHub
#   ./install-tizen.sh 192.168.1.50 tviptv          # avec un autre nom de profil de certificat
#   SOURCE=../ ./install-tizen.sh 192.168.1.50      # depuis le dossier tizen/ local
#   WGT=/chemin/tv-iptv.wgt ./install-tizen.sh 192.168.1.50
#
# Prérequis : Tizen Studio (+ TV Extensions + Samsung Certificate Extension), un profil de
# certificat Samsung, et la TV en mode développeur avec l'IP de ce PC (voir tizen/README.md).
set -euo pipefail

TV_IP="${1:?Usage : $0 IP_DE_LA_TV [profil]}"
PROFILE="${2:-tviptv}"
APP_ID="TvIptvNrob.IPTV"
REPO="nrobeveille/tv-iptv"
TIZEN_HOME="${TIZEN_STUDIO:-$HOME/tizen-studio}"
SDB="$TIZEN_HOME/tools/sdb"
TIZEN="$TIZEN_HOME/tools/ide/bin/tizen"

[ -x "$SDB" ] || { echo "Tizen Studio introuvable dans $TIZEN_HOME (variable TIZEN_STUDIO)"; exit 1; }

WORK="$(mktemp -d)"
TARGET="$WORK/tv-iptv.wgt"

if [ -n "${SOURCE:-}" ]; then
  echo "==> Empaquetage du dossier $SOURCE"
  mkdir "$WORK/app"
  cp -r "$SOURCE"/{index.html,config.xml,icon.png,css,js} "$WORK/app/"
  (cd "$WORK/app" && zip -qr "$TARGET" .)
elif [ -n "${WGT:-}" ]; then
  cp "$WGT" "$TARGET"
else
  echo "==> Recherche de la dernière version sur GitHub"
  URL="$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=30" \
    | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin) if x["tag_name"].startswith("tizen-v")]; print(next(a["browser_download_url"] for a in r[0]["assets"] if a["name"].endswith(".wgt")))')"
  curl -fsSL "$URL" -o "$TARGET"
fi

echo "==> Connexion à la TV ($TV_IP)"
"$SDB" connect "$TV_IP:26101"
LINE="$("$SDB" devices | grep "$TV_IP" | grep device | head -n1 || true)"
[ -n "$LINE" ] || { echo "TV non connectée : vérifie le mode développeur (IP de ce PC, TV redémarrée)."; exit 1; }
DEVICE="$(echo "$LINE" | awk '{print ($3 != "" ? $3 : $1)}')"
echo "TV : $DEVICE"

echo "==> Signature avec le profil « $PROFILE »"
"$TIZEN" package -t wgt -s "$PROFILE" -- "$TARGET"

echo "==> Installation"
"$TIZEN" install -n "$TARGET" -t "$DEVICE"

echo "==> Lancement"
"$TIZEN" run -p "$APP_ID" -t "$DEVICE" >/dev/null || true
echo "C'est fait : « IPTV » est dans les applis de la TV."
