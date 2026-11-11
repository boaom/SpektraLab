#!/bin/bash
# Assemble spektralab-mobile.apk (no gradle): javac -> d8 -> aapt2 -> zip -> zipalign -> apksigner.
# Runs on CI (ubuntu-latest, JDK 17 from setup-java) or any glibc x86_64 host.
set -euo pipefail
PORT="$(cd "$(dirname "$0")" && pwd)"
APK="$PORT/apk"
BT="$APK/bt"
AJ="$APK/android.jar"
cd "$PORT"

fetch_tools() {
  [ -f "$BT/aapt2" ] && [ -f "$AJ" ] && return 0
  mkdir -p "$BT" "$APK/btzip" "$APK/pfzip"
  curl -sS -o "$APK/bt.zip" https://dl.google.com/android/repository/build-tools_r34-linux.zip
  unzip -qo "$APK/bt.zip" -d "$APK/btzip"
  mv "$APK"/btzip/android-*/* "$BT/"
  curl -sS -o "$APK/pf.zip" https://dl.google.com/android/repository/platform-33-ext4_r01.zip
  unzip -qo "$APK/pf.zip" -d "$APK/pfzip"
  find "$APK/pfzip" -name android.jar -exec mv {} "$AJ" \;
  rm -rf "$APK/btzip" "$APK/pfzip" "$APK/bt.zip" "$APK/pf.zip"
}

build() {
  fetch_tools
  cd "$APK"
  rm -rf classes dexout base.apk aligned.apk spektralab-mobile.apk res.zip
  mkdir -p classes dexout

  javac -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options \
    -bootclasspath "$AJ" -classpath "$AJ" \
    $(find java -name '*.java') -d classes
  java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 30 --lib "$AJ" --output dexout \
    $(find classes -name '*.class')

  # compile the icon / theme resources into the link (aapt2 link takes .flat
  # archives, so the res/ tree is compiled first)
  "$BT/aapt2" compile --dir res -o res.zip

  "$BT/aapt2" link -o base.apk -I "$AJ" \
    --manifest AndroidManifest.xml \
    -A assets res.zip \
    --min-sdk-version 30 --target-sdk-version 34 \
    --version-code 1 --version-name "1.0" \
    --auto-add-overlay

  cp dexout/classes.dex .
  zip -q base.apk classes.dex
  zip -qr base.apk lib
  "$BT/zipalign" -f 4 base.apk aligned.apk
  [ -f keystore.jks ] || keytool -genkeypair -keystore keystore.jks -alias spk \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass spektralab -keypass spektralab \
      -dname "CN=SpektraLab Mobile, OU=dev, O=spk, C=CN"
  java -jar "$BT/lib/apksigner.jar" sign \
      --ks keystore.jks --ks-pass pass:spektralab \
      --out spektralab-mobile.apk aligned.apk
  java -jar "$BT/lib/apksigner.jar" verify spektralab-mobile.apk
  ls -la spektralab-mobile.apk
}

case "${1:-build}" in
  tools) fetch_tools ;;
  build) build ;;
  *) echo "usage: $0 [tools|build]" ;;
esac
