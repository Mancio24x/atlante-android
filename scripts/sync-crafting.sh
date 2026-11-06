#!/usr/bin/env bash
# Copies the latest GitHub release into the local crafting folder (android-release/ and dist/),
# moving the previous APK to precedenti/ and regenerating the sources ZIP.
# Usage: bash scripts/sync-crafting.sh [vX.Y.Z]   (default: latest release)
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f "$HOME/.config/atlante-android/env.sh" ]] && source "$HOME/.config/atlante-android/env.sh"
repo=Mancio24x/atlante-android
signer=870146335386f2e34cb4f8f7b8a7e4c6c74db9700a19f120a96b6fd94b163c71
tag="${1:-$(gh -R "$repo" release list --limit 1 --json tagName --jq '.[0].tagName')}"
version="${tag#v}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
gh -R "$repo" release download "$tag" -D "$tmp" --pattern "Atlante-Library-$version.apk*"
(cd "$tmp" && shasum -a 256 -c "Atlante-Library-$version.apk.sha256")
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
"$sdk/build-tools/35.0.0/apksigner" verify --print-certs "$tmp/Atlante-Library-$version.apk" | grep -q "$signer" \
  || { echo "Firma diversa da quella attesa: interrotto." >&2; exit 1; }
for dir in ../android-release dist; do
  mkdir -p "$dir/precedenti"
  for old in "$dir"/Atlante-Library-*.apk*; do
    [[ -e "$old" && "$(basename "$old")" != Atlante-Library-$version.apk* ]] && mv "$old" "$dir/precedenti/"
  done
  cp "$tmp"/Atlante-Library-"$version".apk* "$dir/"
done
python3 scripts/package-source.py >/dev/null
commit="$(git rev-parse --short HEAD)"
leggimi=../android-release/LEGGIMI.txt
# Only the version lines (title, APK, checksum, release link) and the commit change; the notes stay as written.
sed -i '' -E "1s/1\.0\.[0-9]+/$version/; /^(APK installabile|Checksum verificato|Release):/s/1\.0\.[0-9]+/$version/g; s/\(commit [0-9a-f]+\)/(commit $commit)/" "$leggimi"
echo "Cartella crafting aggiornata a $version (commit $commit). Aggiorna a mano le novità in LEGGIMI.txt se servono."
