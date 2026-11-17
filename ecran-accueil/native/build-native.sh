#!/bin/bash
# Compile libzenairplay.so (AirPlay video : UxPlay + libplist + OpenSSL) pour la TV (armeabi-v7a)
# et la copie dans ../source/lib/armeabi-v7a/, ou build-mac.sh la met dans l'APK.
#
# Prerequis (macOS) :
#   brew install cmake ninja
#   sdkmanager --sdk_root=~/Library/Android/sdk "ndk;29.0.14206865"
#
# Licences : UxPlay est GPL-3.0, donc libzenairplay.so et tout APK qui la contient aussi.
# libplist LGPL-2.1, OpenSSL Apache-2.0.
set -e
cd "$(dirname "$0")"
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_ROOT:-$SDK_ROOT/ndk/29.0.14206865}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
API=24

# versions figees (testees sur la TCL le 2026-11-17)
clone() {   # depot, dossier, commit
    [ -d "third_party/$2" ] || git clone -q "$1" "third_party/$2"
    git -C "third_party/$2" fetch -q --depth 1 origin "$3" 2>/dev/null || true
    git -C "third_party/$2" checkout -q "$3"
}
mkdir -p third_party build
clone https://github.com/FDH2/UxPlay.git UxPlay 48f5ce47c9ef694afccfac907c9e8828329657fe
clone https://github.com/libimobiledevice/libplist.git libplist b3f14c22dbb29056c0d3c37bb9b2401ffdb7bc8f
clone https://github.com/openssl/openssl.git openssl e9e85dd09d0eabc9e5437007c6bd033d4ef8b6b1

if [ ! -f build/openssl-armv7/lib/libcrypto.a ]; then
    (cd third_party/openssl && export ANDROID_NDK_ROOT="$NDK" PATH="$TC:$PATH" &&
     ./Configure android-arm -D__ANDROID_API__=$API no-shared no-tests no-ui-console no-engine no-dso \
         --prefix="$PWD/../../build/openssl-armv7" >/dev/null &&
     make -j"$(sysctl -n hw.ncpu)" build_libs >/dev/null && make install_dev >/dev/null)
fi

cmake -S . -B build/cmake -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build/cmake
mkdir -p ../source/lib/armeabi-v7a
"$TC/llvm-strip" --strip-unneeded -o ../source/lib/armeabi-v7a/libzenairplay.so build/cmake/libzenairplay.so
echo "OK : ../source/lib/armeabi-v7a/libzenairplay.so"
