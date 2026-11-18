package tv.zenhome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;

/**
 * Client adb minimal : Zen Home se connecte a l'adbd de la TV (127.0.0.1:5555, adb TCP classique sans TLS)
 * pour lancer des commandes sous l'uid shell, par exemple le demon d'injection de touches (Inject.java)
 * apres un redemarrage de la TV.
 *
 * Cle RSA 2048 propre a l'appli, creee une fois dans files/adbkey (PKCS#8). Premiere connexion : adbd refuse
 * la signature, on envoie la cle publique et adbd demande au systeme de la faire accepter. Android 11 oublie
 * une cle inutilisee depuis 7 jours, sauf avec
 *   settings put global adb_allowed_connection_time 0
 *
 * La TCL de la chambre n'a PAS la fenetre "Autoriser le debogage USB ?" (aucune UsbDebuggingActivity) : la
 * demande reste sans reponse et echoue au bout de 60 s. On accepte donc la cle une fois depuis un ordinateur
 * deja autorise : l'uid shell a MANAGE_DEBUGGING et peut appeler IAdbManager.allowDebugging(true, cle)
 * pendant que la demande de Zen Home est en attente (60 s apres le demarrage du service Keys) :
 *   adb logcat -d -s ZenAdb | grep "cle publique"      (la cle publique de Zen Home, journalisee ici)
 *   adb shell service call adb 1 i32 1 s16 '<cle publique, "... tv.zenhome" compris>'
 * A refaire apres une remise a zero de la TV, l'effacement des donnees de Zen Home (nouvelle cle) ou
 * "Revoquer les autorisations de debogage USB".
 *
 * Protocole (system/core/adb/protocol.txt) : en-tete de 24 octets little-endian (commande, arg0, arg1,
 * longueur, somme des octets, commande ^ 0xffffffff) puis les donnees. CNXN -> AUTH jeton (type 1) ->
 * AUTH signature (type 2) ou AUTH cle publique (type 3) -> CNXN ; OPEN "shell:cmd" -> OKAY, WRTE (a acquitter
 * par OKAY) ... CLSE. On n'annonce pas shell_v2 : la sortie arrive brute, sans le cadrage du protocole shell.
 */
final class AdbLocal {

    static final String TAG = "ZenAdb";
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    static final int A_VERSION = 0x01000000, MAX_DATA = 256 * 1024, MAX_IN = 1024 * 1024;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final Charset UTF8 = Charset.forName("UTF-8");
    // DigestInfo SHA-1 : adb signe le jeton de 20 octets comme une empreinte SHA-1 (RSA_sign NID_sha1)
    static final byte[] SHA1_PREFIX = { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    /** Lance cmd sous l'uid shell et rend sa sortie, ou null (adb coupe, cle refusee, delai depasse). */
    static String shell(Context c, String cmd, int timeoutMs) {
        try {
            return shell("127.0.0.1", 5555, key(c), "tv.zenhome", cmd, timeoutMs, true);
        } catch (Exception e) {
            Log.w(TAG, "adb shell", e);
            return null;
        }
    }

    static synchronized KeyPair key(Context c) throws IOException, GeneralSecurityException {
        File f = new File(c.getFilesDir(), "adbkey");
        if (f.length() > 0) {
            byte[] b = new byte[(int) f.length()];
            DataInputStream in = new DataInputStream(new FileInputStream(f));
            try { in.readFully(b); } finally { in.close(); }
            return pair((RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(b)));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair k = g.generateKeyPair();
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream o = new FileOutputStream(tmp);
        try { o.write(k.getPrivate().getEncoded()); } finally { o.close(); }
        if (!tmp.renameTo(f)) throw new IOException("cle adb non enregistree");
        Log.i(TAG, "nouvelle cle adb");
        return k;
    }

    // Conscrypt relit la cle sans ses parametres CRT : exposant public F4, celui de la cle qu'on a creee
    static KeyPair pair(RSAPrivateKey p) throws GeneralSecurityException {
        BigInteger e = p instanceof RSAPrivateCrtKey ? ((RSAPrivateCrtKey) p).getPublicExponent() : RSAKeyGenParameterSpec.F4;
        RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(p.getModulus(), e));
        return new KeyPair(pub, p);
    }

    // ---------------------------------------------------------------- protocole

    /** offerKey : envoyer la cle publique si la signature est refusee (affiche la demande d'autorisation). */
    static String shell(String host, int port, KeyPair k, String keyName, String cmd, int timeoutMs, boolean offerKey)
            throws IOException, GeneralSecurityException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), Math.min(timeoutMs, 3000));
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            write(out, CNXN, A_VERSION, MAX_DATA, "host::\0".getBytes(UTF8));
            boolean signed = false, offered = false;
            while (true) {
                Msg m = read(s, in, deadline);
                if (m.cmd == CNXN) break;
                if (m.cmd == STLS) throw new IOException("adbd demande TLS");
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) continue;
                if (!signed) {
                    write(out, AUTH, AUTH_SIGNATURE, 0, sign(k.getPrivate(), m.data));
                    signed = true;
                } else if (offerKey && !offered) {
                    byte[] pub = publicKey((RSAPublicKey) k.getPublic(), keyName);
                    write(out, AUTH, AUTH_RSAPUBLICKEY, 0, pub);
                    offered = true;
                    // Certaines TCL n'ont pas la fenetre "Autoriser le debogage USB ?" : shell (MANAGE_DEBUGGING) peut
                    // alors accepter la cle pendant ces 60 s, depuis un ordinateur deja autorise :
                    //   adb shell service call adb 1 i32 1 s16 '<cle publique ci-dessous>'
                    Log.i(TAG, "cle adb envoyee : accepter sur la TV (Toujours autoriser) ; cle publique : "
                            + new String(pub, 0, pub.length - 1, UTF8));
                } else {
                    throw new IOException("cle adb refusee");
                }
            }

            int local = 1, remote = 0;
            write(out, OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(UTF8));
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            while (true) {
                Msg m = read(s, in, deadline);
                if (m.arg1 != local) continue;
                if (m.cmd == OKAY) remote = m.arg0;
                else if (m.cmd == WRTE) { res.write(m.data); write(out, OKAY, local, m.arg0, new byte[0]); }
                else if (m.cmd == CLSE) {
                    if (remote == 0) throw new IOException("service shell refuse");
                    write(out, CLSE, local, remote, new byte[0]);
                    return new String(res.toByteArray(), UTF8);
                }
            }
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    static final class Msg { int cmd, arg0, arg1; byte[] data; }

    static byte[] packet(int cmd, int arg0, int arg1, byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        return b.array();
    }

    static void write(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        out.write(packet(cmd, arg0, arg1, data));
        out.flush();
    }

    static Msg read(Socket s, DataInputStream in, long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) throw new IOException("adb : delai depasse");
        s.setSoTimeout((int) left);
        byte[] h = new byte[24];
        in.readFully(h);
        return parse(h, in);
    }

    static Msg parse(byte[] h, DataInputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.arg0 = b.getInt(); m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt();   // somme : adbd ne la remplit plus depuis la version 0x01000001
        if (b.getInt() != ~m.cmd || len < 0 || len > MAX_IN) throw new IOException("paquet adb invalide");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- cles

    // signature PKCS#1 v1.5 de DigestInfo(SHA-1) || jeton ; repli : RSA brut avec le bourrage fait a la main
    static byte[] sign(PrivateKey k, byte[] token) throws GeneralSecurityException {
        byte[] data = new byte[SHA1_PREFIX.length + token.length];
        System.arraycopy(SHA1_PREFIX, 0, data, 0, SHA1_PREFIX.length);
        System.arraycopy(token, 0, data, SHA1_PREFIX.length, token.length);
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(k);
            s.update(data);
            return s.sign();
        } catch (NoSuchAlgorithmException e) {
            return signRaw(k, data);
        }
    }

    static byte[] signRaw(PrivateKey k, byte[] data) throws GeneralSecurityException {
        int n = 256;
        byte[] em = new byte[n];   // 00 01 ff..ff 00 data
        em[1] = 1;
        for (int i = 2; i < n - data.length - 1; i++) em[i] = (byte) 0xff;
        System.arraycopy(data, 0, em, n - data.length, data.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        return c.doFinal(em);
    }

    // format adbkey.pub : base64 de la struct RSAPublicKey de mincrypt (little-endian) + " nom", termine par \0
    static byte[] publicKey(RSAPublicKey k, String name) {
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        int words = 64;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n.mod(r32).modInverse(r32).negate().mod(r32).intValue());   // n0inv = -1/n[0] mod 2^32
        putLittle(b, n, 256);
        putLittle(b, BigInteger.ONE.shiftLeft(4096).mod(n), 256);              // rr = (2^2048)^2 mod n
        b.putInt(k.getPublicExponent().intValue());
        return (base64(b.array()) + " " + name + "\0").getBytes(UTF8);
    }

    static void putLittle(ByteBuffer b, BigInteger v, int len) {
        byte[] be = v.toByteArray();   // big-endian, octet de signe possible en tete
        for (int i = 0; i < len; i++) b.put(i < be.length ? be[be.length - 1 - i] : 0);
    }

    static String base64(byte[] d) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder s = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int v = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            s.append(a.charAt(v >> 18 & 63)).append(a.charAt(v >> 12 & 63));
            s.append(i + 1 < d.length ? a.charAt(v >> 6 & 63) : '=').append(i + 2 < d.length ? a.charAt(v & 63) : '=');
        }
        return s.toString();
    }
}
