#!/usr/bin/env bash
# Build a standalone API18 APK for the LAN screen-casting receiver (no Gradle, no network).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
PLATFORM="${PLATFORM_JAR:-$SDK/platforms/android-18/android.jar}"
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
elif [ -x '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac' ]; then
  export PATH="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin:$PATH"
fi
for command in java javac keytool zip; do
  command -v "$command" >/dev/null || { echo "Missing $command (JDK 17+ and zip required)" >&2; exit 1; }
done
for command in aapt d8 zipalign apksigner; do
  [ -x "$BT/$command" ] || { echo "Missing $BT/$command" >&2; exit 1; }
done
[ -f "$PLATFORM" ] || { echo "Missing API18 platform: $PLATFORM" >&2; exit 1; }

APP="$ROOT/app"
BUILD="$ROOT/build"
OUT="${OUT_DIR:-$ROOT/dist}"
KEY="${KEYSTORE_PATH:-$ROOT/local/development.p12}"
ALIAS="${KEY_ALIAS:-webcast}"
PASSWORD="${KEYSTORE_PASSWORD:-android}"
export KEYSTORE_PASSWORD="$PASSWORD"
CONSCRYPT="$ROOT/third_party/conscrypt"

mkdir -p "$BUILD" "$OUT"
if [ ! -f "$KEY" ]; then
  if [ -n "${KEYSTORE_PATH:-}" ]; then
    echo "Configured signing key does not exist: $KEY" >&2; exit 1
  fi
  mkdir -p "$ROOT/local"
  KEYSTORE_PASSWORD="$PASSWORD" keytool -genkeypair -keystore "$KEY" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass:env KEYSTORE_PASSWORD -keypass:env KEYSTORE_PASSWORD \
    -dname 'CN=WebCast development' >/dev/null
fi

[ -f "$APP/assets/tls/cert.pem" ] || { echo "Missing asset tls/cert.pem; run tools/make-cert.sh" >&2; exit 1; }
[ -f "$APP/assets/tls/key.pk8" ] || { echo "Missing asset tls/key.pk8; run tools/make-cert.sh" >&2; exit 1; }
[ -f "$CONSCRYPT/classes.jar" ] || { echo "Missing $CONSCRYPT/classes.jar" >&2; exit 1; }

WORK="$BUILD/app"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/gen"

"$BT/aapt" package -f -A "$APP/assets" -M "$APP/AndroidManifest.xml" -S "$APP/res" \
  -I "$PLATFORM" -F "$WORK/base.apk" -J "$WORK/gen" \
  --min-sdk-version 18 --target-sdk-version 18

find "$APP/src" "$WORK/gen" -name '*.java' -print > "$WORK/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn -bootclasspath "$PLATFORM" \
  -classpath "$CONSCRYPT/classes.jar" -d "$WORK/classes" @"$WORK/sources.txt"

find "$WORK/classes" -name '*.class' -print > "$WORK/classes.txt"
"$BT/d8" --release --min-api 18 --lib "$PLATFORM" --output "$WORK/dex" \
  @"$WORK/classes.txt" "$CONSCRYPT/classes.jar"

# Only ship the ARMv7 native lib: the target is an armeabi-v7a TV (drop x86 to halve APK size).
mkdir -p "$WORK/native/lib/armeabi-v7a"
cp "$CONSCRYPT/jni/armeabi-v7a/libconscrypt_jni.so" "$WORK/native/lib/armeabi-v7a/"
(cd "$WORK/native" && zip -q -r -X "$WORK/base.apk" lib)
(cd "$WORK/dex" && zip -q -X "$WORK/base.apk" classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/base.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEY" --ks-key-alias "$ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEYSTORE_PASSWORD \
  --min-sdk-version 18 --v1-signing-enabled true --v2-signing-enabled false \
  --v3-signing-enabled false --out "$OUT/WebCast.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/WebCast.apk"
"$BT/aapt" dump badging "$OUT/WebCast.apk" | sed -n '1,3p'
sha256sum "$OUT/WebCast.apk" 2>/dev/null || shasum -a 256 "$OUT/WebCast.apk"
echo "Built $OUT/WebCast.apk"
