#!/bin/bash
# Compile ZenHome.apk sur macOS avec le SDK Android d'Android Studio.
# Même étapes que build.sh, mais d8 remplace dalvik-exchange.
# Garde key.jks : une mise à jour doit être signée avec la même clé.
set -e
cd "$(dirname "$0")"
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK_ROOT/build-tools/36.0.0"
SDK="$SDK_ROOT/platforms/android-34/android.jar"
rm -rf out && mkdir -p out/classes
javac --release 8 -nowarn -cp "$SDK" -d out/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$SDK" --output out $(find out/classes -name '*.class')
"$BT/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$SDK" -F out/unsigned.apk
(cd out && "$BT/aapt" add unsigned.apk classes.dex >/dev/null)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
[ -f key.jks ] || keytool -genkeypair -keystore key.jks -storepass changeit -keypass changeit -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ZenHome" >/dev/null
"$BT/apksigner" sign --ks key.jks --ks-pass pass:changeit --out out/ZenHome.apk out/aligned.apk
echo "OK : out/ZenHome.apk"
