package tv.zenhome;

import android.util.Log;

/**
 * Crypto du serveur Companion Link (appairage facon HAP puis session ChaCha20-Poly1305).
 * Le JCA d'Android 11 n'a ni Ed25519 ni X25519 : tout passe par l'OpenSSL 3.0 deja lie
 * statiquement dans libzenairplay.so (../native/companion_crypto.c).
 *
 * Appeler load() avant tout le reste. Chaque methode rend null (ou false) en cas d'erreur,
 * mauvaise longueur ou tag invalide : jamais d'exception.
 * Les cles privees Ed25519 sont des graines de 32 octets (RFC 8032), pas la forme 64 octets.
 */
final class Crypto {
    static final String TAG = "ZenCrypto";
    private static int state;   // 0 pas essaye, 1 chargee, -1 absente

    private Crypto() {}

    /** Charge libzenairplay.so une fois ; false si absente. */
    static synchronized boolean load() {
        if (state == 0) {
            try {
                System.loadLibrary("zenairplay");
                state = 1;
            } catch (Throwable t) {
                Log.e(TAG, "libzenairplay.so absente", t);
                state = -1;
            }
        }
        return state > 0;
    }

    /** Nouvelle paire Ed25519 : {graine 32, cle publique 32}. */
    static native byte[][] ed25519Keypair();

    /** Cle publique (32) d'une graine Ed25519 (32). */
    static native byte[] ed25519Public(byte[] seed32);

    /** Signature Ed25519 (64) de msg. */
    static native byte[] ed25519Sign(byte[] seed32, byte[] msg);

    /** true si sig64 est une signature valide de msg par pub32. */
    static native boolean ed25519Verify(byte[] pub32, byte[] msg, byte[] sig64);

    /** Nouvelle paire X25519 : {cle privee 32, cle publique 32}. */
    static native byte[][] x25519Keypair();

    /** Secret partage X25519 (32) ; null si la cle du pair donne un secret nul. */
    static native byte[] x25519(byte[] priv32, byte[] peerPub32);

    /** ChaCha20-Poly1305 (RFC 8439) : rend chiffre || tag16. aad peut etre null. */
    static native byte[] chachaSeal(byte[] key32, byte[] nonce12, byte[] aad, byte[] plain);

    /** Inverse de chachaSeal sur chiffre || tag16 ; null si le tag est invalide. aad peut etre null. */
    static native byte[] chachaOpen(byte[] key32, byte[] nonce12, byte[] aad, byte[] ctTag);

    /** HKDF-SHA512 (RFC 5869), len octets (1 a 16320). salt et info peuvent etre null (= vides). */
    static native byte[] hkdfSha512(byte[] ikm, byte[] salt, byte[] info, int len);
}
