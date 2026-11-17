package tv.zenhome;

/**
 * Decodeur Apple Lossless (ALAC), 16 bits, 1 ou 2 canaux : ce qu'envoie un iPhone en AirPlay audio.
 * Portage Java du decodeur de reference d'Apple (github.com/macosforge/alac, licence Apache 2.0) :
 * ALACDecoder.cpp (Decode), ag_dec.c (dyn_decomp), dp_dec.c (unpc_block), matrix_dec.c (unmix16).
 * La TV n'a pas de decodeur ALAC dans MediaCodec, d'ou ce portage.
 */
final class Alac {

    static final int ID_SCE = 0, ID_CPE = 1, ID_CCE = 2, ID_LFE = 3, ID_DSE = 4, ID_PCE = 5, ID_FIL = 6, ID_END = 7;
    static final int QBSHIFT = 9, QB = 1 << QBSHIFT, MMULSHIFT = 2, MDENSHIFT = QBSHIFT - MMULSHIFT - 1,
            MOFF = 1 << (MDENSHIFT - 2), BITOFF = 24, MAX_PREFIX_16 = 9, MAX_PREFIX_32 = 9,
            MAX_DATATYPE_BITS_16 = 16, N_MAX_MEAN_CLAMP = 0xffff, N_MEAN_CLAMP_VAL = 0xffff;

    final int frameLength, bitDepth, pb, mb, kb, numChannels, maxRun;
    final int[] predictor, mixU, mixV;
    final short[] coefsU = new short[32], coefsV = new short[32];

    // flux de bits : position globale en bits dans buf (complete de zeros pour les lectures de 32 bits en fin)
    byte[] buf = new byte[0];
    int bitPos, endBits;

    /** fmtp AirPlay : "352 0 16 40 10 14 2 255 0 0 44100" = frameLength compat bitDepth pb mb kb channels maxRun ... */
    Alac(int frameLength, int bitDepth, int pb, int mb, int kb, int numChannels, int maxRun) {
        this.frameLength = frameLength; this.bitDepth = bitDepth; this.pb = pb; this.mb = mb; this.kb = kb;
        this.numChannels = numChannels; this.maxRun = maxRun;
        predictor = new int[frameLength]; mixU = new int[frameLength]; mixV = new int[frameLength];
    }

    // ---- lecture de bits (ALACBitUtilities) ----
    int u8(int i) { return i < buf.length ? buf[i] & 0xff : 0; }
    int read32At(int byteOff) { return (u8(byteOff) << 24) | (u8(byteOff + 1) << 16) | (u8(byteOff + 2) << 8) | u8(byteOff + 3); }

    /** lit n bits (1..32), non signes */
    int read(int n) {
        if (n == 0) return 0;
        long v = 0;
        int byteOff = bitPos >> 3, shift = bitPos & 7;
        for (int i = 0; i < 5; i++) v = (v << 8) | u8(byteOff + i);
        v = (v << shift) & 0xFFFFFFFFFFL;          // 40 bits, les bits utiles en tete
        bitPos += n;
        return (int) ((v >>> (40 - n)) & ((1L << n) - 1));
    }
    void byteAlign() { bitPos = (bitPos + 7) & ~7; }

    // ---- ag_dec.c ----
    static int lead(int m) {
        return m == 0 ? 32 : Integer.numberOfLeadingZeros(m);
    }
    static int lg3a(int x) { return 31 - lead(x + 3); }

    int getStreamBits(int bitoffset, int numbits) {
        int byteoffset = bitoffset >>> 3;
        long load = 0;
        for (int i = 0; i < 5; i++) load = (load << 8) | u8(byteoffset + i);
        load = (load << (bitoffset & 7)) & 0xFFFFFFFFFFL;
        long r = load >>> (40 - numbits);
        return numbits >= 32 ? (int) r : (int) (r & ((1L << numbits) - 1));
    }

    int dynGet(int m, int k) {
        int tempbits = bitPos;
        int streamlong = read32At(tempbits >>> 3) << (tempbits & 7);
        int pre = lead(~streamlong);
        int result;
        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16;
            tempbits += pre;
            streamlong <<= pre;
            result = streamlong >>> (32 - MAX_DATATYPE_BITS_16);
            tempbits += MAX_DATATYPE_BITS_16;
        } else {
            tempbits += pre + 1;
            streamlong <<= pre + 1;
            int v = k == 0 ? 0 : streamlong >>> (32 - k);
            tempbits += k;
            result = pre * m + v - 1;
            if (Integer.compareUnsigned(v, 2) < 0) { result -= (v - 1); tempbits -= 1; }
        }
        bitPos = tempbits;
        return result;
    }

    int dynGet32(int m, int k, int maxbits) {
        int tempbits = bitPos;
        int streamlong = read32At(tempbits >>> 3) << (tempbits & 7);
        int result = lead(~streamlong);
        if (result >= MAX_PREFIX_32) {
            result = getStreamBits(tempbits + MAX_PREFIX_32, maxbits);
            tempbits += MAX_PREFIX_32 + maxbits;
        } else {
            tempbits += result + 1;
            if (k != 1) {
                streamlong <<= result + 1;
                int v = k == 0 ? 0 : streamlong >>> (32 - k);
                tempbits += k - 1;
                result = result * m;
                if (Integer.compareUnsigned(v, 2) >= 0) { result += (v - 1); tempbits += 1; }
            }
        }
        bitPos = tempbits;
        return result;
    }

    /** decode numSamples residus dans pc ; mb0/pb/kb comme set_ag_params */
    boolean dynDecomp(int mb0, int pbLocal, int kbLocal, int[] pc, int numSamples, int maxSize) {
        int wbLocal = (1 << kbLocal) - 1;
        int mbv = mb0, zmode = 0, c = 0, out = 0;
        while (c < numSamples) {
            if (bitPos >= endBits) return false;
            int m = mbv >>> QBSHIFT;
            int k = Math.min(lg3a(m), kbLocal);
            m = (1 << k) - 1;
            int n = dynGet32(m, k, maxSize);
            int ndecode = n + zmode;
            int multiplier = (-(ndecode & 1)) | 1;
            pc[out++] = ((ndecode + 1) >>> 1) * multiplier;
            c++;
            mbv = pbLocal * (n + zmode) + mbv - ((pbLocal * mbv) >>> QBSHIFT);
            if (Integer.compareUnsigned(n, N_MAX_MEAN_CLAMP) > 0) mbv = N_MEAN_CLAMP_VAL;
            zmode = 0;
            if (Integer.compareUnsigned(mbv << MMULSHIFT, QB) < 0 && c < numSamples) {
                zmode = 1;
                k = lead(mbv) - BITOFF + ((mbv + MOFF) >>> MDENSHIFT);
                int mz = ((1 << k) - 1) & wbLocal;
                n = dynGet(mz, k);
                if (c + n > numSamples) return false;
                for (int j = 0; j < n; j++) { pc[out++] = 0; c++; }
                if (Integer.compareUnsigned(n, 65535) >= 0) zmode = 0;
                mbv = 0;
            }
        }
        return true;
    }

    // ---- dp_dec.c ----
    static int signOfInt(int i) { return ((-i) >>> 31) | (i >> 31); }

    static void unpcBlock(int[] pc1, int[] out, int num, short[] coefs, int numactive, int chanbits, int denshift) {
        int chanshift = 32 - chanbits;
        int denhalf = denshift == 0 ? 0 : 1 << (denshift - 1);
        out[0] = pc1[0];
        if (numactive == 0) {
            if (num > 1 && pc1 != out) System.arraycopy(pc1, 1, out, 1, num - 1);
            return;
        }
        if (numactive == 31) {
            int prev = out[0];
            for (int j = 1; j < num; j++) {
                int del = pc1[j] + prev;
                prev = (del << chanshift) >> chanshift;
                out[j] = prev;
            }
            return;
        }
        for (int j = 1; j <= numactive && j < num; j++) {
            int del = pc1[j] + out[j - 1];
            out[j] = (del << chanshift) >> chanshift;
        }
        int lim = numactive + 1;
        for (int j = lim; j < num; j++) {
            int sum1 = 0;
            int p = j - 1;                       // pout = out + j - 1, pout[-k] = out[p - k]
            int top = out[j - lim];
            for (int k = 0; k < numactive; k++) sum1 += coefs[k] * (out[p - k] - top);
            int del = pc1[j];
            int del0 = del;
            int sg = signOfInt(del);
            del += top + ((sum1 + denhalf) >> denshift);
            out[j] = (del << chanshift) >> chanshift;
            if (sg > 0) {
                for (int k = numactive - 1; k >= 0; k--) {
                    int dd = top - out[p - k];
                    int sgn = signOfInt(dd);
                    coefs[k] = (short) (coefs[k] - sgn);
                    del0 -= (numactive - k) * ((sgn * dd) >> denshift);
                    if (del0 <= 0) break;
                }
            } else if (sg < 0) {
                for (int k = numactive - 1; k >= 0; k--) {
                    int dd = top - out[p - k];
                    int sgn = signOfInt(dd);
                    coefs[k] = (short) (coefs[k] + sgn);
                    del0 -= (numactive - k) * ((-sgn * dd) >> denshift);
                    if (del0 >= 0) break;
                }
            }
        }
    }

    /**
     * Decode une trame ALAC (in[off..off+len)) en PCM 16 bits entrelace dans out (numChannels * frameLength).
     * Renvoie le nombre d'echantillons par canal, ou -1 si la trame est invalide.
     */
    int decode(byte[] in, int off, int len, short[] out) {
        if (buf.length < len + 8) buf = new byte[len + 8];
        System.arraycopy(in, off, buf, 0, len);
        java.util.Arrays.fill(buf, len, buf.length, (byte) 0);
        bitPos = 0; endBits = len * 8;
        int numSamples = frameLength, channelIndex = 0;

        while (channelIndex < numChannels) {
            if (bitPos >= endBits) return -1;
            int tag = read(3);
            if (tag == ID_SCE || tag == ID_LFE || tag == ID_CPE) {
                boolean stereo = tag == ID_CPE;
                if (stereo && channelIndex + 2 > numChannels) break;
                read(4);                                 // elementInstanceTag
                if (read(12) != 0) return -1;            // unusedHeader
                int headerByte = read(4);
                int partialFrame = headerByte >> 3;
                int bytesShifted = (headerByte >> 1) & 3;
                if (bytesShifted == 3) return -1;
                int escapeFlag = headerByte & 1;
                int chanBits = bitDepth - bytesShifted * 8 + (stereo ? 1 : 0);
                if (partialFrame != 0) {
                    numSamples = (read(16) << 16) | read(16);
                    if (numSamples <= 0 || numSamples > frameLength) return -1;
                }
                int mixBits = 0, mixRes = 0;
                if (escapeFlag == 0) {
                    mixBits = read(8);
                    mixRes = (byte) read(8);
                    int h = read(8);
                    int modeU = h >> 4, denShiftU = h & 0xf;
                    h = read(8);
                    int pbFactorU = h >> 5, numU = h & 0x1f;
                    for (int i = 0; i < numU; i++) coefsU[i] = (short) read(16);
                    int modeV = 0, denShiftV = 0, pbFactorV = 0, numV = 0;
                    if (stereo) {
                        h = read(8);
                        modeV = h >> 4; denShiftV = h & 0xf;
                        h = read(8);
                        pbFactorV = h >> 5; numV = h & 0x1f;
                        for (int i = 0; i < numV; i++) coefsV[i] = (short) read(16);
                    }
                    // 16 bits : bytesShifted vaut 0 en pratique ; sinon on saute les octets decales (precision perdue)
                    int shiftStart = bitPos;
                    if (bytesShifted != 0) bitPos += bytesShifted * 8 * (stereo ? 2 : 1) * numSamples;
                    if (!dynDecomp(mb, (pb * pbFactorU) / 4, kb, predictor, numSamples, chanBits)) return -1;
                    if (modeU != 0) unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
                    unpcBlock(predictor, mixU, numSamples, coefsU, numU, chanBits, denShiftU);
                    if (stereo) {
                        if (!dynDecomp(mb, (pb * pbFactorV) / 4, kb, predictor, numSamples, chanBits)) return -1;
                        if (modeV != 0) unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0);
                        unpcBlock(predictor, mixV, numSamples, coefsV, numV, chanBits, denShiftV);
                    }
                    if (bytesShifted != 0 && bitDepth == 16) return -1;   // impossible en 16 bits
                } else {
                    chanBits = bitDepth;
                    int shift = 32 - chanBits;
                    for (int i = 0; i < numSamples; i++) {
                        mixU[i] = (read(chanBits) << shift) >> shift;
                        if (stereo) mixV[i] = (read(chanBits) << shift) >> shift;
                    }
                }
                if (stereo) {
                    // unmix16
                    for (int j = 0, o = channelIndex; j < numSamples; j++, o += numChannels) {
                        if (mixRes != 0) {
                            int l = mixU[j] + mixV[j] - ((mixRes * mixV[j]) >> mixBits);
                            out[o] = (short) l;
                            out[o + 1] = (short) (l - mixV[j]);
                        } else {
                            out[o] = (short) mixU[j];
                            out[o + 1] = (short) mixV[j];
                        }
                    }
                    channelIndex += 2;
                } else {
                    for (int j = 0, o = channelIndex; j < numSamples; j++, o += numChannels) out[o] = (short) mixU[j];
                    channelIndex += 1;
                }
            } else if (tag == ID_DSE) {
                read(4);
                int align = read(1);
                int count = read(8);
                if (count == 255) count += read(8);
                if (align != 0) byteAlign();
                bitPos += count * 8;
            } else if (tag == ID_FIL) {
                int count = read(4);
                if (count == 15) count += read(8) - 1;
                bitPos += count * 8;
            } else if (tag == ID_END) {
                break;
            } else {
                return -1;   // CCE, PCE : non supportes
            }
        }
        // canaux manquants : silence
        for (int c = channelIndex; c < numChannels; c++)
            for (int j = 0, o = c; j < numSamples; j++, o += numChannels) out[o] = 0;
        return numSamples;
    }
}
