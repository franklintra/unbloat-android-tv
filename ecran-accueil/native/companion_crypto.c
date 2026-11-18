/*
 * JNI crypto for tv.zenhome.Crypto: the primitives of Apple's Companion Link (HAP-style pairing,
 * then a ChaCha20-Poly1305 session). Android 11's JCA has no Ed25519 or X25519, so this uses the
 * OpenSSL 3.0 that is already statically linked into libzenairplay.so for UxPlay.
 *
 * Every function returns NULL (or false) on any error and leaves no Java exception pending:
 * the Java side only checks for null.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/rand.h>

#define TAG_LEN 16

/* ------------------------------------------------------------------ helpers */

/* copies a Java byte[] into a malloc'd buffer (never NULL on success, even for length 0).
 * want >= 0 requires that exact length. Returns NULL for a null array or a wrong length. */
static unsigned char *bytes(JNIEnv *e, jbyteArray a, jsize want, jsize *len) {
    if (!a) return NULL;
    jsize n = (*e)->GetArrayLength(e, a);
    if (want >= 0 && n != want) return NULL;
    unsigned char *p = malloc(n > 0 ? (size_t) n : 1);
    if (!p) return NULL;
    if (n > 0) (*e)->GetByteArrayRegion(e, a, 0, n, (jbyte *) p);
    if (len) *len = n;
    return p;
}

/* frees a buffer that may hold a secret */
static void wipe(unsigned char *p, size_t n) {
    if (!p) return;
    OPENSSL_cleanse(p, n);
    free(p);
}

static jbyteArray jbytes(JNIEnv *e, const unsigned char *p, size_t n) {
    jbyteArray a = (*e)->NewByteArray(e, (jsize) n);
    if (!a) { (*e)->ExceptionClear(e); return NULL; }
    if (n > 0) (*e)->SetByteArrayRegion(e, a, 0, (jsize) n, (const jbyte *) p);
    return a;
}

/* {a, b} as byte[][] */
static jobjectArray pair(JNIEnv *e, const unsigned char *a, const unsigned char *b, size_t n) {
    jclass c = (*e)->FindClass(e, "[B");
    if (!c) { (*e)->ExceptionClear(e); return NULL; }
    jobjectArray r = (*e)->NewObjectArray(e, 2, c, NULL);
    jbyteArray ja = jbytes(e, a, n), jb = jbytes(e, b, n);
    if (!r || !ja || !jb) { (*e)->ExceptionClear(e); return NULL; }
    (*e)->SetObjectArrayElement(e, r, 0, ja);
    (*e)->SetObjectArrayElement(e, r, 1, jb);
    return r;
}

/* public key of a raw 32-byte private key (Ed25519 seed or X25519 scalar) */
static int raw_public(int type, const unsigned char *priv, unsigned char *pub) {
    EVP_PKEY *k = EVP_PKEY_new_raw_private_key(type, NULL, priv, 32);
    size_t n = 32;
    int ok = k && EVP_PKEY_get_raw_public_key(k, pub, &n) == 1 && n == 32;
    EVP_PKEY_free(k);
    return ok;
}

static jobjectArray keypair(JNIEnv *e, int type) {
    unsigned char priv[32], pub[32];
    jobjectArray r = NULL;
    if (RAND_bytes(priv, sizeof priv) == 1 && raw_public(type, priv, pub)) r = pair(e, priv, pub, 32);
    OPENSSL_cleanse(priv, sizeof priv);
    return r;
}

/* ------------------------------------------------------------------ Ed25519 */

JNIEXPORT jobjectArray JNICALL Java_tv_zenhome_Crypto_ed25519Keypair(JNIEnv *e, jclass c) {
    (void) c;
    return keypair(e, EVP_PKEY_ED25519);
}

JNIEXPORT jbyteArray JNICALL Java_tv_zenhome_Crypto_ed25519Public(JNIEnv *e, jclass c, jbyteArray jseed) {
    (void) c;
    unsigned char *seed = bytes(e, jseed, 32, NULL), pub[32];
    jbyteArray r = seed && raw_public(EVP_PKEY_ED25519, seed, pub) ? jbytes(e, pub, 32) : NULL;
    wipe(seed, 32);
    return r;
}

JNIEXPORT jbyteArray JNICALL Java_tv_zenhome_Crypto_ed25519Sign(JNIEnv *e, jclass c, jbyteArray jseed, jbyteArray jmsg) {
    (void) c;
    jsize mlen = 0;
    unsigned char *seed = bytes(e, jseed, 32, NULL), *msg = bytes(e, jmsg, -1, &mlen), sig[64];
    size_t slen = sizeof sig;
    jbyteArray r = NULL;
    EVP_PKEY *k = seed ? EVP_PKEY_new_raw_private_key(EVP_PKEY_ED25519, NULL, seed, 32) : NULL;
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (k && msg && ctx && EVP_DigestSignInit(ctx, NULL, NULL, NULL, k) == 1
            && EVP_DigestSign(ctx, sig, &slen, msg, (size_t) mlen) == 1 && slen == 64)
        r = jbytes(e, sig, 64);
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(k);
    wipe(seed, 32);
    free(msg);
    return r;
}

JNIEXPORT jboolean JNICALL Java_tv_zenhome_Crypto_ed25519Verify(JNIEnv *e, jclass c, jbyteArray jpub, jbyteArray jmsg,
        jbyteArray jsig) {
    (void) c;
    jsize mlen = 0;
    unsigned char *pub = bytes(e, jpub, 32, NULL), *msg = bytes(e, jmsg, -1, &mlen), *sig = bytes(e, jsig, 64, NULL);
    EVP_PKEY *k = pub ? EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, NULL, pub, 32) : NULL;
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    int ok = k && msg && sig && ctx && EVP_DigestVerifyInit(ctx, NULL, NULL, NULL, k) == 1
            && EVP_DigestVerify(ctx, sig, 64, msg, (size_t) mlen) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(k);
    free(pub);
    free(msg);
    free(sig);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* ------------------------------------------------------------------ X25519 */

JNIEXPORT jobjectArray JNICALL Java_tv_zenhome_Crypto_x25519Keypair(JNIEnv *e, jclass c) {
    (void) c;
    return keypair(e, EVP_PKEY_X25519);
}

/* fails (null) when the shared secret is all zeros (low-order peer key), as RFC 7748 allows */
JNIEXPORT jbyteArray JNICALL Java_tv_zenhome_Crypto_x25519(JNIEnv *e, jclass c, jbyteArray jpriv, jbyteArray jpeer) {
    (void) c;
    unsigned char *priv = bytes(e, jpriv, 32, NULL), *peer = bytes(e, jpeer, 32, NULL), out[32];
    size_t n = sizeof out;
    jbyteArray r = NULL;
    EVP_PKEY *k = priv ? EVP_PKEY_new_raw_private_key(EVP_PKEY_X25519, NULL, priv, 32) : NULL;
    EVP_PKEY *p = peer ? EVP_PKEY_new_raw_public_key(EVP_PKEY_X25519, NULL, peer, 32) : NULL;
    EVP_PKEY_CTX *ctx = k ? EVP_PKEY_CTX_new(k, NULL) : NULL;
    if (ctx && p && EVP_PKEY_derive_init(ctx) == 1 && EVP_PKEY_derive_set_peer(ctx, p) == 1
            && EVP_PKEY_derive(ctx, out, &n) == 1 && n == 32)
        r = jbytes(e, out, 32);
    OPENSSL_cleanse(out, sizeof out);
    EVP_PKEY_CTX_free(ctx);
    EVP_PKEY_free(p);
    EVP_PKEY_free(k);
    wipe(priv, 32);
    free(peer);
    return r;
}

/* ------------------------------------------------------------------ ChaCha20-Poly1305 (RFC 8439) */

/* seal: returns ct || tag16. open: input is ct || tag16, returns plain or null if the tag is wrong */
static jbyteArray chacha(JNIEnv *e, int seal, jbyteArray jkey, jbyteArray jnonce, jbyteArray jaad, jbyteArray jin) {
    jsize alen = 0, inlen = 0;
    unsigned char *key = bytes(e, jkey, 32, NULL), *nonce = bytes(e, jnonce, 12, NULL);
    unsigned char *aad = jaad ? bytes(e, jaad, -1, &alen) : NULL, *in = bytes(e, jin, -1, &inlen), *out = NULL;
    jbyteArray r = NULL;
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    int n = 0, fin = 0;
    if (!key || !nonce || (jaad && !aad) || !in || !ctx || (!seal && inlen < TAG_LEN)) goto done;
    int len = seal ? inlen : inlen - TAG_LEN;        /* length of the plaintext / ciphertext body */
    out = malloc((size_t) len + TAG_LEN);
    if (!out) goto done;
    if (EVP_CipherInit_ex(ctx, EVP_chacha20_poly1305(), NULL, NULL, NULL, seal) != 1
            || EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, 12, NULL) != 1
            || EVP_CipherInit_ex(ctx, NULL, NULL, key, nonce, seal) != 1)
        goto done;
    if (alen > 0 && EVP_CipherUpdate(ctx, NULL, &n, aad, alen) != 1) goto done;
    n = 0;
    if (len > 0 && EVP_CipherUpdate(ctx, out, &n, in, len) != 1) goto done;
    if (!seal && EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, TAG_LEN, in + len) != 1) goto done;
    if (EVP_CipherFinal_ex(ctx, out + n, &fin) != 1) goto done;   /* open: fails on a bad tag */
    n += fin;
    if (n != len) goto done;
    if (seal) {
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_GET_TAG, TAG_LEN, out + len) != 1) goto done;
        r = jbytes(e, out, (size_t) len + TAG_LEN);
    } else {
        r = jbytes(e, out, (size_t) len);
    }
done:
    EVP_CIPHER_CTX_free(ctx);
    if (out) wipe(out, (size_t) (seal ? inlen : inlen - TAG_LEN) + TAG_LEN);
    wipe(key, 32);
    free(nonce);
    free(aad);
    wipe(in, (size_t) inlen);
    return r;
}

JNIEXPORT jbyteArray JNICALL Java_tv_zenhome_Crypto_chachaSeal(JNIEnv *e, jclass c, jbyteArray key, jbyteArray nonce,
        jbyteArray aad, jbyteArray plain) {
    (void) c;
    return chacha(e, 1, key, nonce, aad, plain);
}

JNIEXPORT jbyteArray JNICALL Java_tv_zenhome_Crypto_chachaOpen(JNIEnv *e, jclass c, jbyteArray key, jbyteArray nonce,
        jbyteArray aad, jbyteArray ctTag) {
    (void) c;
    return chacha(e, 0, key, nonce, aad, ctTag);
}

/* ------------------------------------------------------------------ HKDF-SHA512 (RFC 5869) */

/* salt and info may be null (= empty). len must be 1..255*64. */
JNIEXPORT jbyteArray JNICALL Java_tv_zenhome_Crypto_hkdfSha512(JNIEnv *e, jclass c, jbyteArray jikm, jbyteArray jsalt,
        jbyteArray jinfo, jint len) {
    (void) c;
    jsize ilen = 0, slen = 0, nlen = 0;
    unsigned char *ikm = bytes(e, jikm, -1, &ilen);
    unsigned char *salt = jsalt ? bytes(e, jsalt, -1, &slen) : NULL;
    unsigned char *info = jinfo ? bytes(e, jinfo, -1, &nlen) : NULL;
    unsigned char *out = len > 0 && len <= 255 * 64 ? malloc((size_t) len) : NULL;
    jbyteArray r = NULL;
    EVP_KDF *kdf = EVP_KDF_fetch(NULL, "HKDF", NULL);
    EVP_KDF_CTX *ctx = kdf ? EVP_KDF_CTX_new(kdf) : NULL;
    if (ikm && out && ctx && (!jsalt || salt) && (!jinfo || info)) {
        OSSL_PARAM p[5], *q = p;
        *q++ = OSSL_PARAM_construct_utf8_string(OSSL_KDF_PARAM_DIGEST, (char *) "SHA512", 0);
        *q++ = OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_KEY, ikm, (size_t) ilen);
        if (slen > 0) *q++ = OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_SALT, salt, (size_t) slen);
        if (nlen > 0) *q++ = OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_INFO, info, (size_t) nlen);
        *q = OSSL_PARAM_construct_end();
        if (EVP_KDF_derive(ctx, out, (size_t) len, p) == 1) r = jbytes(e, out, (size_t) len);
    }
    EVP_KDF_CTX_free(ctx);
    EVP_KDF_free(kdf);
    if (out) wipe(out, (size_t) len);
    wipe(ikm, (size_t) ilen);
    free(salt);
    free(info);
    return r;
}
