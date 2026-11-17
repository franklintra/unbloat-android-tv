#!/bin/bash
# Compile ZenHome.apk sans Android Studio (Ubuntu/Debian/WSL) :
#   sudo apt install openjdk-17-jdk aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign
#   bash build.sh
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf out && mkdir -p out/classes
javac --release 8 -nowarn -cp $SDK -d out/classes $(find src -name '*.java')
dalvik-exchange --dex --output=out/classes.dex out/classes
aapt package -f -M AndroidManifest.xml -S res -A assets -I $SDK -F out/unsigned.apk
(cd out && aapt add unsigned.apk classes.dex >/dev/null)
# AirPlay video (../native/build-native.sh)
[ -f lib/armeabi-v7a/libzenairplay.so ] && aapt add out/unsigned.apk lib/armeabi-v7a/libzenairplay.so >/dev/null
zipalign -f -p 4 out/unsigned.apk out/aligned.apk
[ -f key.jks ] || keytool -genkeypair -keystore key.jks -storepass changeit -keypass changeit -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ZenHome" >/dev/null
apksigner sign --ks key.jks --ks-pass pass:changeit --out out/ZenHome.apk out/aligned.apk
echo "OK : out/ZenHome.apk"
