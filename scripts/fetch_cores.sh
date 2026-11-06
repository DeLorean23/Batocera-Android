#!/usr/bin/env bash
# Lädt die Libretro-Cores aus cores.txt vom libretro-Buildbot und legt sie
# als lib<core>_libretro_android.so in app/src/main/jniLibs/<abi>/ ab.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="https://buildbot.libretro.com/nightly/android/latest"
OUT="$ROOT/app/src/main/jniLibs"
TMP="$(mktemp -d)"
REPORT="$ROOT/cores-report.txt"
: > "$REPORT"

fetch() {
  local core="$1" abi="$2"
  local zip="$TMP/${core}_${abi}.zip"
  mkdir -p "$OUT/$abi"
  if curl -fsSL --retry 3 -o "$zip" "$BASE/$abi/${core}_libretro_android.so.zip"; then
    unzip -o -q "$zip" -d "$TMP/$abi-$core" && \
    mv "$TMP/$abi-$core/${core}_libretro_android.so" "$OUT/$abi/lib${core}_libretro_android.so" && \
    echo "OK    $abi $core" | tee -a "$REPORT" && return 0
  fi
  echo "FEHLT $abi $core" | tee -a "$REPORT"
}

grep -vE '^\s*(#|$)' "$ROOT/cores.txt" | while read -r core abis; do
  fetch "$core" arm64-v8a
  if [ "$abis" = "both" ]; then
    fetch "$core" armeabi-v7a
  fi
done
rm -rf "$TMP"
echo "Cores fertig:"; du -sh "$OUT"/* || true
