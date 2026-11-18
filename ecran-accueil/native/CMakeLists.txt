# libzenairplay.so : UxPlay's AirPlay server (mirroring + HLS video) for Zen Home, armeabi-v7a.
# Also carries companion_crypto.c (tv.zenhome.Crypto: Ed25519, X25519, ChaCha20-Poly1305, HKDF
# for the Companion Link server) over the same OpenSSL.
# Built by ../source/build-native.sh with the NDK toolchain. Sources in third_party/ (git clones):
#   UxPlay (GPL-3.0), libplist (LGPL-2.1), OpenSSL 3.0 (Apache-2.0, prebuilt by build-native.sh).
cmake_minimum_required(VERSION 3.18)
project(zenairplay C)

set(UX ${CMAKE_CURRENT_SOURCE_DIR}/third_party/UxPlay/lib)
set(PL ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplist)
set(OSSL ${CMAKE_CURRENT_SOURCE_DIR}/build/openssl-armv7)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fvisibility=hidden -Wno-unused-parameter -Wno-deprecated-declarations")

# libplist (C part only)
file(GLOB PLIST_SRC ${PL}/src/*.c)
list(APPEND PLIST_SRC ${PL}/libcnary/node.c ${PL}/libcnary/node_list.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PUBLIC ${PL}/include PRIVATE ${PL}/src ${PL}/libcnary/include)
target_compile_definitions(plist PRIVATE __LITTLE_ENDIAN__=1 HAVE_TM_TM_GMTOFF=1 HAVE_TM_TM_ZONE=1 HAVE_STRNDUP=1 LIBPLIST_STATIC PACKAGE_VERSION="2.7.0")

# UxPlay lib/ without its Bonjour backends (dns_sd/, mdnsd/): dnssd_android.c replaces them
file(GLOB UX_SRC ${UX}/*.c ${UX}/playfair/*.c ${UX}/fairplay_v2/*.c ${UX}/llhttp/*.c)
add_library(zenairplay SHARED ${UX_SRC} dnssd_android.c zenairplay.c companion_crypto.c)
target_include_directories(zenairplay PRIVATE ${UX} ${UX}/playfair ${UX}/fairplay_v2 ${UX}/llhttp ${OSSL}/include)
target_compile_definitions(zenairplay PRIVATE NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L
    STANDALONE TARGET_POSIX _LINUX _REENTRANT _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64)
target_link_libraries(zenairplay plist ${OSSL}/lib/libcrypto.a log)
target_link_options(zenairplay PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
