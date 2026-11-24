package homes.mymate.gateway.protocol;

/**
 * Pure-Java RFC 4648 Base64 encoder/decoder compatible with Android API 21+ (Android 5.0+)
 * and standard JVM unit tests — avoids java.util.Base64 which crashes with NoClassDefFoundError
 * on Android 5.0-7.1 (API 21-25, including Phicomm R1 and legacy 86 wall panels).
 */
public final class Base64Codec {

    private static final char[] ENCODE_TABLE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static final int[] DECODE_TABLE = new int[128];

    static {
        for (int i = 0; i < DECODE_TABLE.length; i++) {
            DECODE_TABLE[i] = -1;
        }
        for (int i = 0; i < ENCODE_TABLE.length; i++) {
            DECODE_TABLE[ENCODE_TABLE[i]] = i;
        }
        DECODE_TABLE['-'] = 62; // URL-safe tolerance
        DECODE_TABLE['_'] = 63;
    }

    private Base64Codec() {
    }

    public static String encodeToString(byte[] src) {
        if (src == null || src.length == 0) {
            return "";
        }
        int len = src.length;
        int outLen = ((len + 2) / 3) * 4;
        char[] out = new char[outLen];
        int oi = 0;
        int i = 0;
        while (i + 3 <= len) {
            int n = ((src[i] & 0xFF) << 16) | ((src[i + 1] & 0xFF) << 8) | (src[i + 2] & 0xFF);
            out[oi++] = ENCODE_TABLE[(n >>> 18) & 0x3F];
            out[oi++] = ENCODE_TABLE[(n >>> 12) & 0x3F];
            out[oi++] = ENCODE_TABLE[(n >>> 6) & 0x3F];
            out[oi++] = ENCODE_TABLE[n & 0x3F];
            i += 3;
        }
        int rem = len - i;
        if (rem == 1) {
            int n = (src[i] & 0xFF) << 16;
            out[oi++] = ENCODE_TABLE[(n >>> 18) & 0x3F];
            out[oi++] = ENCODE_TABLE[(n >>> 12) & 0x3F];
            out[oi++] = '=';
            out[oi] = '=';
        } else if (rem == 2) {
            int n = ((src[i] & 0xFF) << 16) | ((src[i + 1] & 0xFF) << 8);
            out[oi++] = ENCODE_TABLE[(n >>> 18) & 0x3F];
            out[oi++] = ENCODE_TABLE[(n >>> 12) & 0x3F];
            out[oi++] = ENCODE_TABLE[(n >>> 6) & 0x3F];
            out[oi] = '=';
        }
        return new String(out);
    }

    public static byte[] decode(String src) {
        if (src == null || src.isEmpty()) {
            return new byte[0];
        }
        int len = src.length();
        // Count valid base64 chars and trailing padding
        int validChars = 0;
        for (int i = 0; i < len; i++) {
            char c = src.charAt(i);
            if (c < 128 && DECODE_TABLE[c] >= 0) {
                validChars++;
            } else if (c == '=' || c == ' ' || c == '\r' || c == '\n' || c == '\t') {
                // ignore whitespace & padding
            } else {
                throw new IllegalArgumentException("Invalid Base64 character at index " + i);
            }
        }
        if (validChars == 0) {
            return new byte[0];
        }
        int outLen = (validChars * 3) / 4;
        byte[] out = new byte[outLen];
        int oi = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            char c = src.charAt(i);
            if (c == '=') {
                break;
            }
            if (c >= 128) {
                continue;
            }
            int v = DECODE_TABLE[c];
            if (v < 0) {
                continue;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (oi < outLen) {
                    out[oi++] = (byte) ((acc >>> bits) & 0xFF);
                }
            }
        }
        if (oi == outLen) {
            return out;
        }
        byte[] trimmed = new byte[oi];
        System.arraycopy(out, 0, trimmed, 0, oi);
        return trimmed;
    }
}
