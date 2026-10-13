#!/bin/bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
TASK_BUILD="${TASK_BUILD:-$TASK_ROOT/.build}"
BT="$ANDROID_SDK_ROOT/build-tools/36.0.0"
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
mkdir -p "$TASK_BUILD/classes" "$TASK_BUILD/generated" "$TASK_BUILD/dex"
"$BT/aapt2" compile --dir "$TASK_ROOT/res" -o "$TASK_BUILD/resources.zip"
"$BT/aapt2" link -o "$TASK_BUILD/unsigned.apk" -I "$ANDROID_JAR" --manifest "$TASK_ROOT/AndroidManifest.xml" --java "$TASK_BUILD/generated" --version-code 2 --version-name 1.1 "$TASK_BUILD/resources.zip"
find "$TASK_ROOT/src" "$TASK_BUILD/generated" -name '*.java' > "$TASK_BUILD/sources.txt"
javac -source 8 -target 8 -Xlint:-options -cp "$ANDROID_JAR" -d "$TASK_BUILD/classes" @"$TASK_BUILD/sources.txt"
find "$TASK_BUILD/classes" -name '*.class' > "$TASK_BUILD/classes.txt"
"$BT/d8" --min-api 30 --lib "$ANDROID_JAR" --output "$TASK_BUILD/dex" @"$TASK_BUILD/classes.txt"
(cd "$TASK_BUILD/dex" && zip -q -j "$TASK_BUILD/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$TASK_BUILD/unsigned.apk" "$TASK_BUILD/aligned.apk"
if [ ! -f "$TASK_BUILD/local-signing.jks" ]; then
  keytool -genkeypair -keystore "$TASK_BUILD/local-signing.jks" -storepass android -keypass android -alias adbawake -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Local ADB Awake"
fi
"$BT/apksigner" sign --v4-signing-enabled false --ks "$TASK_BUILD/local-signing.jks" --ks-pass pass:android --out "$TASK_ROOT/ADB-Awake.apk" "$TASK_BUILD/aligned.apk"
"$BT/apksigner" verify "$TASK_ROOT/ADB-Awake.apk"
printf 'Built %s\n' "$TASK_ROOT/ADB-Awake.apk"
