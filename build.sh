#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Craft/GUI agent processes may not inherit the interactive shell environment.
if [[ "$(uname -s)" == Darwin && -f "$HOME/.config/atlante-android/env.sh" ]]; then
    source "$HOME/.config/atlante-android/env.sh"
fi
variant="${1:-release}"
if [[ "$variant" != release && "$variant" != debug ]]; then
    echo 'Uso: ./build.sh [release|debug]' >&2
    exit 2
fi
if ! java -version >/dev/null 2>&1; then
    echo 'Manca Java 17. Installa il JDK 17 oppure usa il workflow GitHub incluso (README.md).' >&2
    exit 1
fi
if ! command -v gradle >/dev/null 2>&1; then
    echo 'Manca Gradle 8.9. Il workflow GitHub lo installa automaticamente (README.md).' >&2
    exit 1
fi
sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_path" || ! -f "$sdk_path/platforms/android-35/android.jar" ]]; then
    echo 'Manca Android SDK 35. Servono platforms;android-35 e build-tools;35.0.0.' >&2
    exit 1
fi
if [[ "$variant" == release ]]; then
    gradle --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
    # Room migration tests need the exported schemas, packaged only in debug assets.
    gradle --no-daemon :app:testDebugUnitTest --tests 'app.atlante.library.SagaDatabaseTest'
else
    gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
fi
apk="app/build/outputs/apk/$variant/app-$variant.apk"
if [[ ! -f "$apk" ]]; then echo 'APK non generato.' >&2; exit 1; fi
"$sdk_path/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$apk"
"$sdk_path/build-tools/35.0.0/zipalign" -c -p 4 "$apk"
mkdir -p dist
version_name="${VERSION_NAME:-$(sed -n 's/^versionName=//p' version.properties)}"
suffix=""
if [[ "$variant" == debug ]]; then suffix="-debug"; fi
output="dist/Atlante-Library-$version_name$suffix.apk"
cp "$apk" "$output"
python3 - "$output" <<'PY'
import hashlib, pathlib, sys
apk = pathlib.Path(sys.argv[1])
apk.with_suffix('.apk.sha256').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + apk.name + '\n')
print('APK verificato: ' + str(apk))
PY
