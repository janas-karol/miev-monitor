#!/usr/bin/env bash
# Build APK bez Gradle: aapt2 + javac + d8 + zipalign + apksigner.
#
# Dlaczego tak: aplikacja nie ma zadnych zaleznosci (ani AndroidX, ani Kotlinu),
# wiec Gradle + AGP + Maven to ~400 MB pobierania i kilka minut na kazdy build.
# Tutaj wystarczy JDK i Android SDK (build-tools + platform).
#
# Uzycie:
#   ./build.sh            # build + podpis kluczem debug
#   ./build.sh install    # dodatkowo adb install (jesli telefon podlaczony)
set -euo pipefail

cd "$(dirname "$0")"

JDK="${JDK:-$HOME/tools/jdk-17}"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"

APP_NAME="imiev"
VERSION_NAME="${VERSION_NAME:-1.0}"
VERSION_CODE="${VERSION_CODE:-1}"
MIN_SDK="${MIN_SDK:-21}"
TARGET_SDK="${TARGET_SDK:-36}"

OUT="build"
APK_DIR="apk"

if [[ ! -x "$JDK/bin/javac" ]]; then
    echo "BLAD: brak JDK w $JDK (ustaw zmienna JDK)" >&2
    exit 1
fi
if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "BLAD: brak platformy Android w $PLATFORM" >&2
    exit 1
fi
if [[ -z "$BUILD_TOOLS" ]]; then
    echo "BLAD: brak build-tools w $SDK" >&2
    exit 1
fi

echo "JDK:         $JDK"
echo "platforma:   $PLATFORM"
echo "build-tools: $BUILD_TOOLS"

# d8 i apksigner to skrypty wolajace `java` - musza je widziec w PATH
export PATH="$JDK/bin:$PATH"
export JAVA_HOME="$JDK"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$APK_DIR"

# 1. zasoby -> spakowane zasoby + R.java
"$BUILD_TOOLS/aapt2" compile --dir res -o "$OUT/res.zip"
"$BUILD_TOOLS/aapt2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$OUT/res.zip"

# 2. kompilacja Javy
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" \
    -encoding UTF-8 \
    -source 11 -target 11 \
    -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

# 3. bytecode -> dex
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BUILD_TOOLS/d8" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    --output "$OUT/dex" \
    @"$OUT/classes.txt"

# 4. dex do APK (zip w systemie bywa nieobecny - uzywamy Pythona)
python3 - "$OUT/app-unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

# 5. wyrownanie i podpis
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

# Podpis: klucz release z ~/.secrets (jesli jest), inaczej debug.
# Klucz release daje stabilny podpis miedzy buildami - aktualizacje wchodza
# "na miejsce", bez odinstalowywania aplikacji.
SECRETS_ENV="${IMIEV_SECRETS_ENV:-$HOME/.secrets/imiev-release.env}"
APK="$APK_DIR/$APP_NAME-$VERSION_NAME.apk"

if [[ -f "$SECRETS_ENV" ]]; then
    # shellcheck disable=SC1090
    source "$SECRETS_ENV"
    if [[ ! -f "${IMIEV_KS:-}" ]]; then
        echo "BLAD: $SECRETS_ENV wskazuje na nieistniejacy keystore: ${IMIEV_KS:-brak}" >&2
        exit 1
    fi
    echo "Podpis:     RELEASE (${IMIEV_KS_ALIAS}, $IMIEV_KS)"
    "$BUILD_TOOLS/apksigner" sign \
        --ks "$IMIEV_KS" --ks-key-alias "$IMIEV_KS_ALIAS" \
        --ks-pass env:IMIEV_KS_PASS --key-pass env:IMIEV_KEY_PASS \
        --min-sdk-version "$MIN_SDK" \
        --out "$APK" "$OUT/app-aligned.apk"
else
    KS="$HOME/.android/debug.keystore"
    if [[ ! -f "$KS" ]]; then
        mkdir -p "$(dirname "$KS")"
        echo "Tworze klucz debug: $KS"
        "$JDK/bin/keytool" -genkeypair -v \
            -keystore "$KS" -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US" > /dev/null
    fi
    echo "Podpis:     DEBUG ($KS)"
    echo "UWAGA: brak $SECRETS_ENV - podpisano kluczem debug."
    "$BUILD_TOOLS/apksigner" sign \
        --ks "$KS" --ks-pass pass:android --key-pass pass:android \
        --ks-key-alias androiddebugkey \
        --min-sdk-version "$MIN_SDK" \
        --out "$APK" "$OUT/app-aligned.apk"
fi

# Artefakty do publikacji: suma kontrolna pliku i odcisk certyfikatu.
# Dzieki nim kazdy moze sprawdzic, ze APK jest dokladnie tym, co zbudowalem.
( cd "$APK_DIR" && sha256sum "$APP_NAME-$VERSION_NAME.apk" > "SHA256SUMS-$VERSION_NAME.txt" )
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" \
    | grep -E "certificate DN|certificate SHA-256" > "$APK_DIR/CERT-$VERSION_NAME.txt"

echo
echo "Suma kontrolna: $APK_DIR/SHA256SUMS-$VERSION_NAME.txt"
cat "$APK_DIR/SHA256SUMS-$VERSION_NAME.txt"
echo "Certyfikat:    $APK_DIR/CERT-$VERSION_NAME.txt"
cat "$APK_DIR/CERT-$VERSION_NAME.txt"

"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | head -3

echo
echo "Gotowe: $APK"
ls -lh "$APK"
"$BUILD_TOOLS/aapt2" dump badging "$APK" | head -4

if [[ "${1:-}" == "install" ]]; then
    "$SDK/platform-tools/adb" install -r "$APK"
fi
