#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

GAME_DIR="${SK_GAME_DIR:-}"
BUILD_DIR="build"
OUT_DIR="out"
ZIP_NAME="damage-meter.zip"
PCODE_JAR="projectx-pcode.jar"

JAVASSIST_JAR="lib/javassist-3.29.2-GA.jar"
JAVASSIST_URL="https://repo1.maven.org/maven2/org/javassist/javassist/3.29.2-GA/javassist-3.29.2-GA.jar"
CFR_JAR="lib/cfr.jar"
CFR_URL="https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar"
MODUTILS_JAR="lib/sk-modutils-1.0.2.jar"

REFRESH_PCODE=0
for arg in "$@"; do
    case "$arg" in
        --refresh-pcode) REFRESH_PCODE=1 ;;
        *) echo "unknown option: $arg" >&2; exit 1 ;;
    esac
done

fetch() {
    local dest="$1" url="$2"
    if [ -f "$dest" ]; then return; fi
    echo "fetching $dest"
    curl -sfL -o "$dest" "$url" || { echo "failed to download $dest from $url" >&2; exit 1; }
}

mkdir -p lib
fetch "$JAVASSIST_JAR" "$JAVASSIST_URL"
fetch "$CFR_JAR" "$CFR_URL"

if [ "$REFRESH_PCODE" = "1" ] || [ ! -f "$PCODE_JAR" ]; then
    CANDIDATE="${GAME_DIR:+$GAME_DIR/code/projectx-pcode.jar}"
    if [ -n "$CANDIDATE" ] && [ -f "$CANDIDATE" ]; then
        echo "copying $PCODE_JAR from $CANDIDATE"
        cp "$CANDIDATE" "$PCODE_JAR"
    elif [ ! -f "$PCODE_JAR" ]; then
        echo "no $PCODE_JAR here and no install found." >&2
        echo "drop your own <game install>/code/projectx-pcode.jar in as $PCODE_JAR" >&2
        echo "(or set SK_GAME_DIR and rerun with --refresh-pcode)" >&2
        exit 1
    fi
fi

rm -rf "$BUILD_DIR"
javac -cp "$JAVASSIST_JAR:$MODUTILS_JAR" -d "$BUILD_DIR" Patcher.java Discovery.java
rm -rf "$OUT_DIR"
java -cp "$BUILD_DIR:$JAVASSIST_JAR:$MODUTILS_JAR" Patcher

GETDOWN="${GAME_DIR:+$GAME_DIR/getdown.txt}"
if [ -n "$GETDOWN" ] && [ -f "$GETDOWN" ]; then
    PX_VERSION=$(grep -m1 '^version' "$GETDOWN" | sed 's/.*= *//')
elif [ -f mod.json ]; then
    PX_VERSION=$(grep -m1 'pxVersion' mod.json | sed -E 's/.*: *"([^"]*)".*/\1/')
    echo "no getdown.txt (set SK_GAME_DIR to sync pxVersion), keeping $PX_VERSION" >&2
else
    # the launcher silently skips mods with a version it doesn't know
    echo "cannot tell which game version to build for." >&2
    echo "set SK_GAME_DIR to your install so getdown.txt can be read, e.g." >&2
    echo "  SK_GAME_DIR=\"\$HOME/.steam/debian-installation/steamapps/common/Spiral Knights\" ./build.sh" >&2
    exit 1
fi

cat > mod.json << EOF
{
  "mod": {
    "name": "Damage Meter",
    "description": "Left click changes the stat. Right click swaps between the current/last floor or whole run. Drag to move. The corner buttons reset the run or hide the meter.",
    "author": "Mergu",
    "version": "1.0.0",
    "type": "class",
    "pxVersion": "$PX_VERSION"
  }
}
EOF

rm -f "$ZIP_NAME"
zip -j "$ZIP_NAME" mod.json > /dev/null
(cd "$OUT_DIR" && zip -r "../$ZIP_NAME" com > /dev/null)
echo "built $ZIP_NAME"
