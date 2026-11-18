package tv.zenhome;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Codecs du protocole Companion Link (Companion.java) :
 * - OPACK, le format binaire des messages ({"_i": "_hidC", "_x": 12, "_t": 2, "_c": {...}})
 * - TLV8 (Opack.Tlv), le format des messages d'appairage HAP (pair-setup, pair-verify)
 * - plist binaire (Opack.Plist), juste ce qu'il faut pour les archives NSKeyedArchiver de la saisie de texte (_tiD)
 *
 * Portage de atvr4samsung (opack.py, tlv8.py, keyed_archiver.py ; MIT, vb3), lui-meme tire de pyatv (MIT,
 * Pierre Stahl) : voir l'en-tete de Companion.java. Types Java : null, Boolean, Long (entiers), Double (reels),
 * String, byte[], UUID, List, Map (LinkedHashMap : l'ordre des cles est garde).
 */
final class Opack {

    private Opack() { }

    // ------------------------------------------------------------------ OPACK

    /** Encode comme pyatv : les objets de plus d'un octet deja vus sont remplaces par une reference (0xA0+i). */
    static byte[] pack(Object o) { return pack(o, new ArrayList<byte[]>()); }

    private static byte[] pack(Object d, List<byte[]> table) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (d == null) b.write(0x04);
        else if (d instanceof Boolean) b.write((Boolean) d ? 0x01 : 0x02);
        else if (d instanceof UUID) {
            b.write(0x05);
            le(b, Long.reverseBytes(((UUID) d).getMostSignificantBits()), 8);   // octets dans l'ordre du texte
            le(b, Long.reverseBytes(((UUID) d).getLeastSignificantBits()), 8);
        } else if (d instanceof Long || d instanceof Integer || d instanceof Short || d instanceof Byte) {
            long v = ((Number) d).longValue();
            if (v >= 0 && v < 0x28) b.write((int) v + 8);
            else if (v >= 0 && v <= 0xff) { b.write(0x30); le(b, v, 1); }
            else if (v >= 0 && v <= 0xffff) { b.write(0x31); le(b, v, 2); }
            else if (v >= 0 && v <= 0xffffffffL) { b.write(0x32); le(b, v, 4); }
            else { b.write(0x33); le(b, v, 8); }
        } else if (d instanceof Double || d instanceof Float) {
            b.write(0x36);
            le(b, Double.doubleToLongBits(((Number) d).doubleValue()), 8);
        } else if (d instanceof String) {
            byte[] s = ((String) d).getBytes(StandardCharsets.UTF_8);
            int n = s.length;
            if (n <= 0x20) b.write(0x40 + n);
            else if (n <= 0xff) { b.write(0x61); le(b, n, 1); }
            else if (n <= 0xffff) { b.write(0x62); le(b, n, 2); }
            else if (n <= 0xffffff) { b.write(0x63); le(b, n, 3); }
            else { b.write(0x64); le(b, n, 4); }
            b.write(s, 0, n);
        } else if (d instanceof byte[]) {
            byte[] s = (byte[]) d;
            int n = s.length;
            if (n <= 0x20) b.write(0x70 + n);
            else if (n <= 0xff) { b.write(0x91); le(b, n, 1); }
            else if (n <= 0xffff) { b.write(0x92); le(b, n, 2); }
            else { b.write(0x93); le(b, n, 4); }
            b.write(s, 0, n);
        } else if (d instanceof List) {
            List<?> l = (List<?>) d;
            b.write(0xD0 + Math.min(l.size(), 0xF));
            for (Object x : l) { byte[] p = pack(x, table); b.write(p, 0, p.length); }
            if (l.size() >= 0xF) b.write(0x03);
        } else if (d instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) d;
            b.write(0xE0 + Math.min(m.size(), 0xF));
            for (Map.Entry<?, ?> e : m.entrySet()) {
                byte[] k = pack(e.getKey(), table); b.write(k, 0, k.length);
                byte[] v = pack(e.getValue(), table); b.write(v, 0, v.length);
            }
            if (m.size() >= 0xF) b.write(0x03);
        } else throw new IllegalArgumentException("OPACK : type " + d.getClass().getName());

        byte[] p = b.toByteArray();
        int i = -1;
        for (int k = 0; k < table.size(); k++) if (Arrays.equals(table.get(k), p)) { i = k; break; }
        if (i >= 0) {
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            if (i < 0x21) r.write(0xA0 + i);
            else if (i <= 0xff) { r.write(0xC1); le(r, i, 1); }
            else if (i <= 0xffff) { r.write(0xC2); le(r, i, 2); }
            else { r.write(0xC3); le(r, i, 4); }
            return r.toByteArray();
        }
        if (p.length > 1) table.add(p);
        return p;
    }

    private static void le(ByteArrayOutputStream b, long v, int n) { for (int i = 0; i < n; i++) b.write((int) (v >>> (8 * i)) & 0xff); }

    /** Decode le premier objet ; les octets en trop sont ignores (comme opack.unpack). Exception si invalide. */
    static final int MAX_TABLE = 4096;   // les messages Companion en ont quelques dizaines

    static Object unpack(byte[] data) { return unpack(data, new int[]{0}, new ArrayList<Object>(), 0); }

    private static Object unpack(byte[] d, int[] pos, List<Object> table, int depth) {
        if (depth > 64) throw new IllegalArgumentException("OPACK trop imbrique");
        int p = pos[0];
        int t = u8(d, p);
        Object v;
        boolean add = true;
        if (t == 0x01) { v = Boolean.TRUE; p++; add = false; }
        else if (t == 0x02) { v = Boolean.FALSE; p++; add = false; }
        else if (t == 0x04) { v = null; p++; add = false; }
        else if (t == 0x05) {
            need(d, p + 1, 16);
            v = new UUID(Long.reverseBytes(rle(d, p + 1, 8)), Long.reverseBytes(rle(d, p + 9, 8)));
            p += 17;
        } else if (t == 0x06) { need(d, p + 1, 8); v = rle(d, p + 1, 8); p += 9; }   // date absolue : entier brut
        else if (t >= 0x08 && t <= 0x2F) { v = (long) (t - 8); p++; add = false; }
        else if (t == 0x35) { need(d, p + 1, 4); v = (double) Float.intBitsToFloat((int) rle(d, p + 1, 4)); p += 5; }
        else if (t == 0x36) { need(d, p + 1, 8); v = Double.longBitsToDouble(rle(d, p + 1, 8)); p += 9; }
        else if ((t & 0xF0) == 0x30) {
            int n = 1 << (t & 0xF);
            if (n > 8) throw new IllegalArgumentException("OPACK : entier de " + n + " octets");
            need(d, p + 1, n);
            v = rle(d, p + 1, n);
            p += 1 + n;
        } else if (t >= 0x40 && t <= 0x60) {
            int n = t - 0x40;
            need(d, p + 1, n);
            v = new String(d, p + 1, n, StandardCharsets.UTF_8);
            p += 1 + n;
        } else if (t > 0x60 && t <= 0x64) {
            int w = t & 0xF;
            need(d, p + 1, w);
            int n = len(rle(d, p + 1, w));
            need(d, p + 1 + w, n);
            v = new String(d, p + 1 + w, n, StandardCharsets.UTF_8);
            p += 1 + w + n;
        } else if (t >= 0x70 && t <= 0x90) {
            int n = t - 0x70;
            need(d, p + 1, n);
            v = Arrays.copyOfRange(d, p + 1, p + 1 + n);
            p += 1 + n;
        } else if (t >= 0x91 && t <= 0x94) {
            int w = 1 << ((t & 0xF) - 1);
            need(d, p + 1, w);
            int n = len(rle(d, p + 1, w));
            need(d, p + 1 + w, n);
            v = Arrays.copyOfRange(d, p + 1 + w, p + 1 + w + n);
            p += 1 + w + n;
        } else if ((t & 0xF0) == 0xD0) {
            int n = t & 0xF;
            ArrayList<Object> l = new ArrayList<>();
            pos[0] = p + 1;
            if (n == 0xF) {
                while (u8(d, pos[0]) != 0x03) l.add(unpack(d, pos, table, depth + 1));
                pos[0]++;
            } else for (int i = 0; i < n; i++) l.add(unpack(d, pos, table, depth + 1));
            return l;
        } else if ((t & 0xE0) == 0xE0) {
            int n = t & 0xF;
            LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
            pos[0] = p + 1;
            if (n == 0xF) {
                while (u8(d, pos[0]) != 0x03) { Object k = unpack(d, pos, table, depth + 1); m.put(k, unpack(d, pos, table, depth + 1)); }
                pos[0]++;
            } else for (int i = 0; i < n; i++) { Object k = unpack(d, pos, table, depth + 1); m.put(k, unpack(d, pos, table, depth + 1)); }
            return m;
        } else if (t >= 0xA0 && t <= 0xC0) { v = ref(table, t - 0xA0); p++; }
        else if (t >= 0xC1 && t <= 0xC4) {
            int w = t - 0xC0;
            need(d, p + 1, w);
            v = ref(table, len(rle(d, p + 1, w)));
            p += 1 + w;
        } else throw new IllegalArgumentException("OPACK : type 0x" + Integer.toHexString(t));

        if (add && indexOf(table, v) < 0) {
            // indexOf est lineaire : sans plafond, une trame de 1 Mio non authentifiee coute des minutes de CPU
            if (table.size() >= MAX_TABLE) throw new IllegalArgumentException("OPACK : trop d'objets");
            table.add(v);
        }
        pos[0] = p;
        return v;
    }

    private static Object ref(List<Object> table, int i) {
        if (i < 0 || i >= table.size()) throw new IllegalArgumentException("OPACK : reference " + i + " inconnue");
        return table.get(i);
    }

    private static int indexOf(List<Object> table, Object v) {
        for (int i = 0; i < table.size(); i++) {
            Object o = table.get(i);
            if (o instanceof byte[] && v instanceof byte[] ? Arrays.equals((byte[]) o, (byte[]) v) : o == null ? v == null : o.equals(v)) return i;
        }
        return -1;
    }

    private static int u8(byte[] d, int p) {
        if (p >= d.length) throw new IllegalArgumentException("OPACK tronque");
        return d[p] & 0xff;
    }

    private static void need(byte[] d, int p, int n) { if (n < 0 || p + n > d.length) throw new IllegalArgumentException("OPACK tronque"); }

    private static int len(long n) {
        if (n < 0 || n > Integer.MAX_VALUE) throw new IllegalArgumentException("OPACK : longueur " + n);
        return (int) n;
    }

    private static long rle(byte[] d, int p, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (d[p + i] & 0xff);
        return v;
    }

    /** Petit constructeur de dictionnaire : map("_i", "x", "_t", 3). */
    static LinkedHashMap<Object, Object> map(Object... kv) {
        LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ TLV8 (HAP)

    static final class Tlv {
        static final int METHOD = 0x00, IDENTIFIER = 0x01, SALT = 0x02, PUBLIC_KEY = 0x03, PROOF = 0x04,
                ENCRYPTED_DATA = 0x05, SEQ_NO = 0x06, ERROR = 0x07, BACK_OFF = 0x08, SIGNATURE = 0x0A, NAME = 0x11;
        static final int ERR_UNKNOWN = 0x01, ERR_AUTHENTICATION = 0x02, ERR_BACK_OFF = 0x03, ERR_MAX_PEERS = 0x04,
                ERR_MAX_TRIES = 0x05, ERR_UNAVAILABLE = 0x06, ERR_BUSY = 0x07;

        /** Lit les TLV ; une etiquette repetee (valeur de plus de 255 octets) est concatenee. */
        static LinkedHashMap<Integer, byte[]> read(byte[] d) {
            LinkedHashMap<Integer, byte[]> r = new LinkedHashMap<>();
            int p = 0;
            while (p < d.length) {
                if (p + 1 >= d.length) throw new IllegalArgumentException("TLV tronque");
                int tag = d[p] & 0xff, n = d[p + 1] & 0xff;
                byte[] v = Arrays.copyOfRange(d, p + 2, Math.min(p + 2 + n, d.length));
                byte[] old = r.get(tag);
                if (old != null) {
                    byte[] c = Arrays.copyOf(old, old.length + v.length);
                    System.arraycopy(v, 0, c, old.length, v.length);
                    v = c;
                }
                r.put(tag, v);
                p += 2 + n;
            }
            return r;
        }

        /** write(SEQ_NO, new byte[]{2}, SALT, salt, ...) dans cet ordre ; une valeur vide n'ecrit rien (comme tlv8.py). */
        static byte[] write(Object... tagValue) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (int i = 0; i + 1 < tagValue.length; i += 2) {
                int tag = (Integer) tagValue[i];
                byte[] v = (byte[]) tagValue[i + 1];
                for (int p = 0; p < v.length; p += 255) {
                    int n = Math.min(255, v.length - p);
                    b.write(tag);
                    b.write(n);
                    b.write(v, p, n);
                }
            }
            return b.toByteArray();
        }
    }

    // ------------------------------------------------------------------ plist binaire

    /**
     * Plist binaire (bplist00) : lecture complete, ecriture des types utiles (dictionnaire a cles texte, liste,
     * texte, donnees, entier, booleen, Uid). Sert aux archives NSKeyedArchiver de la saisie de texte (_tiD).
     */
    static final class Plist {

        /** Reference d'objet NSKeyedArchiver (CF$UID). */
        static final class Uid {
            final int value;
            Uid(int v) { value = v; }
            @Override public boolean equals(Object o) { return o instanceof Uid && ((Uid) o).value == value; }
            @Override public int hashCode() { return value; }
            @Override public String toString() { return "Uid(" + value + ")"; }
        }

        static Object read(byte[] d) {
            if (d.length < 40 || !new String(d, 0, 8, StandardCharsets.US_ASCII).startsWith("bplist0"))
                throw new IllegalArgumentException("pas un plist binaire");
            int t = d.length - 32;
            int offSize = d[t + 6] & 0xff, refSize = d[t + 7] & 0xff;
            long count = be(d, t + 8, 8), top = be(d, t + 16, 8), table = be(d, t + 24, 8);
            if (offSize < 1 || offSize > 8 || refSize < 1 || refSize > 8 || count < 1 || count > 100_000
                    || top >= count || table < 8 || table + count * offSize > t)
                throw new IllegalArgumentException("plist : en-tete invalide");
            Reader r = new Reader(d, offSize, refSize, (int) count, (int) table);
            return r.object((int) top, 0);
        }

        private static final class Reader {
            final byte[] d;
            final int offSize, refSize, count, table;

            Reader(byte[] d, int offSize, int refSize, int count, int table) {
                this.d = d; this.offSize = offSize; this.refSize = refSize; this.count = count; this.table = table;
            }

            Object object(int i, int depth) {
                if (i < 0 || i >= count) throw new IllegalArgumentException("plist : objet " + i);
                if (depth > 32) throw new IllegalArgumentException("plist trop imbrique");
                int p = (int) be(d, table + i * offSize, offSize);
                if (p < 8 || p >= d.length - 32) throw new IllegalArgumentException("plist : decalage " + p);
                int m = d[p] & 0xff, hi = m >> 4, lo = m & 0xF;
                switch (hi) {
                    case 0x0:
                        if (m == 0x08) return Boolean.FALSE;
                        if (m == 0x09) return Boolean.TRUE;
                        return null;
                    case 0x1: {
                        int n = 1 << lo;
                        check(p + 1, n);
                        if (n > 8) throw new IllegalArgumentException("plist : entier de " + n + " octets");
                        long v = be(d, p + 1, n);
                        return v;   // 8 octets : signe (complement a deux), comme CFBinaryPlist
                    }
                    case 0x2: {
                        int n = 1 << lo;
                        check(p + 1, n);
                        if (n == 4) return (double) Float.intBitsToFloat((int) be(d, p + 1, 4));
                        if (n == 8) return Double.longBitsToDouble(be(d, p + 1, 8));
                        throw new IllegalArgumentException("plist : reel de " + n + " octets");
                    }
                    case 0x3:
                        check(p + 1, 8);
                        return Double.longBitsToDouble(be(d, p + 1, 8));   // date : secondes depuis 2001
                    case 0x4: { int[] n = size(p, lo); check(n[1], n[0]); return Arrays.copyOfRange(d, n[1], n[1] + n[0]); }
                    case 0x5: { int[] n = size(p, lo); check(n[1], n[0]); return new String(d, n[1], n[0], StandardCharsets.US_ASCII); }
                    case 0x6: { int[] n = size(p, lo); check(n[1], 2 * n[0]); return new String(d, n[1], 2 * n[0], StandardCharsets.UTF_16BE); }
                    case 0x8: check(p + 1, lo + 1); return new Uid((int) be(d, p + 1, lo + 1));
                    case 0xA: {
                        int[] n = size(p, lo);
                        check(n[1], n[0] * refSize);
                        ArrayList<Object> l = new ArrayList<>();
                        for (int k = 0; k < n[0]; k++) l.add(object((int) be(d, n[1] + k * refSize, refSize), depth + 1));
                        return l;
                    }
                    case 0xD: {
                        int[] n = size(p, lo);
                        check(n[1], 2 * n[0] * refSize);
                        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                        for (int k = 0; k < n[0]; k++) {
                            Object key = object((int) be(d, n[1] + k * refSize, refSize), depth + 1);
                            Object val = object((int) be(d, n[1] + (n[0] + k) * refSize, refSize), depth + 1);
                            map.put(String.valueOf(key), val);
                        }
                        return map;
                    }
                    default:
                        throw new IllegalArgumentException("plist : marqueur 0x" + Integer.toHexString(m));
                }
            }

            /** {taille, debut des donnees} ; taille 0xF : un entier suit. */
            int[] size(int p, int lo) {
                if (lo != 0xF) return new int[]{lo, p + 1};
                check(p + 1, 1);
                int m = d[p + 1] & 0xff;
                if ((m >> 4) != 0x1) throw new IllegalArgumentException("plist : taille invalide");
                int n = 1 << (m & 0xF);
                check(p + 2, n);
                long v = be(d, p + 2, n);
                if (v < 0 || v > d.length) throw new IllegalArgumentException("plist : taille " + v);
                return new int[]{(int) v, p + 2 + n};
            }

            void check(int p, int n) { if (n < 0 || p < 0 || p + n > d.length - 32) throw new IllegalArgumentException("plist tronque"); }
        }

        private static long be(byte[] d, int p, int n) {
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 8) | (d[p + i] & 0xff);
            return v;
        }

        /** Ecrit un plist binaire ; objets non partages (chaque valeur a sa propre entree, ce qui reste valide). */
        static byte[] write(Object root) {
            ArrayList<Object> objs = new ArrayList<>();
            ArrayList<int[]> refs = new ArrayList<>();
            flatten(root, objs, refs);
            int n = objs.size();
            int refSize = n < 256 ? 1 : n < 65536 ? 2 : 4;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write('b'); b.write('p'); b.write('l'); b.write('i'); b.write('s'); b.write('t'); b.write('0'); b.write('0');
            long[] off = new long[n];
            for (int i = 0; i < n; i++) {
                off[i] = b.size();
                Object o = objs.get(i);
                if (o instanceof Boolean) b.write((Boolean) o ? 0x09 : 0x08);
                else if (o instanceof Number) writeInt(b, ((Number) o).longValue());
                else if (o instanceof Uid) {
                    int v = ((Uid) o).value;
                    int w = v < 0x100 ? 1 : v < 0x10000 ? 2 : 4;
                    b.write(0x80 + w - 1);
                    beWrite(b, v, w);
                } else if (o instanceof byte[]) {
                    byte[] s = (byte[]) o;
                    head(b, 0x4, s.length);
                    b.write(s, 0, s.length);
                } else if (o instanceof String) {
                    String s = (String) o;
                    boolean ascii = true;
                    for (int k = 0; k < s.length(); k++) if (s.charAt(k) > 0x7f) { ascii = false; break; }
                    if (ascii) { head(b, 0x5, s.length()); byte[] x = s.getBytes(StandardCharsets.US_ASCII); b.write(x, 0, x.length); }
                    else { head(b, 0x6, s.length()); byte[] x = s.getBytes(StandardCharsets.UTF_16BE); b.write(x, 0, x.length); }
                } else if (o instanceof List) {
                    int[] r = refs.get(i);
                    head(b, 0xA, r.length);
                    for (int x : r) beWrite(b, x, refSize);
                } else if (o instanceof Map) {
                    int[] r = refs.get(i);
                    head(b, 0xD, r.length / 2);
                    for (int x : r) beWrite(b, x, refSize);
                } else throw new IllegalArgumentException("plist : type " + o);
            }
            long table = b.size();
            int offSize = table < 0x100 ? 1 : table < 0x10000 ? 2 : 4;
            for (long o : off) beWrite(b, o, offSize);
            for (int i = 0; i < 6; i++) b.write(0);
            b.write(offSize);
            b.write(refSize);
            beWrite(b, n, 8);
            beWrite(b, 0, 8);
            beWrite(b, table, 8);
            return b.toByteArray();
        }

        private static int flatten(Object o, ArrayList<Object> objs, ArrayList<int[]> refs) {
            int i = objs.size();
            objs.add(o);
            refs.add(null);
            if (o instanceof List) {
                List<?> l = (List<?>) o;
                int[] r = new int[l.size()];
                for (int k = 0; k < r.length; k++) r[k] = flatten(l.get(k), objs, refs);
                refs.set(i, r);
            } else if (o instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) o;
                int n = m.size(), k = 0;
                int[] r = new int[2 * n];
                for (Object key : m.keySet()) r[k++] = flatten(String.valueOf(key), objs, refs);
                for (Object v : m.values()) r[k++] = flatten(v, objs, refs);
                refs.set(i, r);
            }
            return i;
        }

        private static void head(ByteArrayOutputStream b, int type, int n) {
            if (n < 0xF) { b.write((type << 4) | n); return; }
            b.write((type << 4) | 0xF);
            writeInt(b, n);
        }

        private static void writeInt(ByteArrayOutputStream b, long v) {
            if (v >= 0 && v < 0x100) { b.write(0x10); beWrite(b, v, 1); }
            else if (v >= 0 && v < 0x10000) { b.write(0x11); beWrite(b, v, 2); }
            else if (v >= 0 && v < 0x100000000L) { b.write(0x12); beWrite(b, v, 4); }
            else { b.write(0x13); beWrite(b, v, 8); }
        }

        private static void beWrite(ByteArrayOutputStream b, long v, int n) { for (int i = n - 1; i >= 0; i--) b.write((int) (v >>> (8 * i)) & 0xff); }

        /**
         * Suit un chemin de cles depuis $top en resolvant les Uid dans $objects (keyed_archiver.read_archive_properties) ;
         * null si une cle ou un index manque.
         */
        static Object archiveProperty(Object archive, String... path) {
            if (!(archive instanceof Map)) throw new IllegalArgumentException("archive sans dictionnaire");
            Map<?, ?> root = (Map<?, ?>) archive;
            Object objects = root.get("$objects");
            if (!(objects instanceof List)) throw new IllegalArgumentException("archive sans $objects");
            List<?> objs = (List<?>) objects;
            Object e = root.get("$top");
            for (String key : path) {
                if (!(e instanceof Map)) {
                    if (e == null) return null;
                    throw new IllegalArgumentException("archive : " + key + " dans un non-dictionnaire");
                }
                if (!((Map<?, ?>) e).containsKey(key)) return null;
                e = ((Map<?, ?>) e).get(key);
                if (e instanceof Uid) {
                    int u = ((Uid) e).value;
                    if (u < 0 || u >= objs.size()) return null;
                    e = objs.get(u);
                }
            }
            return e;
        }
    }
}
