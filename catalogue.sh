#!/bin/sh
# dumps every hittable actor and its loot flag to catalogue.tsv
set -e
cd "$(dirname "$0")"

GAME="${SK_GAME_DIR:-}"
if [ -z "$GAME" ]; then
    echo "set SK_GAME_DIR to the game install" >&2
    exit 1
fi
DAT="$GAME/rsrc/config/actor.dat"
[ -f "$DAT" ] || { echo "no actor configs at $DAT" >&2; exit 1; }
[ -f projectx-pcode.jar ] || { echo "need projectx-pcode.jar beside this script" >&2; exit 1; }

mkdir -p build/catalogue
javac -nowarn -cp projectx-pcode.jar -d build/catalogue Catalogue.java
java -cp "projectx-pcode.jar:build/catalogue" Catalogue "$DAT" catalogue.tsv
echo "wrote catalogue.tsv"
