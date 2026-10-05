package com.wearswipe.core.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 极简 DER 编码器，只覆盖"生成一张自签名 X.509 证书"所需的类型。
 *
 * <p>之所以不引入 BouncyCastle：Android 端要能直接跑，且 JDK 的
 * {@code sun.security.x509.*} 属于内部 API，被模块系统挡住。手写 DER 只有几十行，
 * 且生成结果可以用 {@code keytool -printcert} 与 apksig 双重校验。
 */
final class DerWriter {

    static final String OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1";
    static final String OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11";
    static final String OID_COMMON_NAME = "2.5.4.3";

    private DerWriter() {
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
            return;
        }
        int count = 0;
        for (int v = length; v > 0; v >>>= 8) {
            count++;
        }
        out.write(0x80 | count);
        for (int i = count - 1; i >= 0; i--) {
            out.write((length >>> (8 * i)) & 0xFF);
        }
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    static byte[] sequence(byte[]... items) {
        return tlv(0x30, concat(items));
    }

    static byte[] set(byte[]... items) {
        return tlv(0x31, concat(items));
    }

    static byte[] integer(BigInteger value) {
        return tlv(0x02, value.toByteArray());
    }

    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    static byte[] booleanValue(boolean value) {
        return new byte[]{0x01, 0x01, (byte) (value ? 0xFF : 0x00)};
    }

    static byte[] nullValue() {
        return new byte[]{0x05, 0x00};
    }

    static byte[] octetString(byte[] content) {
        return tlv(0x04, content);
    }

    static byte[] bitString(byte[] content) {
        byte[] body = new byte[content.length + 1];
        body[0] = 0x00; // 未使用的比特数
        System.arraycopy(content, 0, body, 1, content.length);
        return tlv(0x03, body);
    }

    static byte[] utf8String(String text) {
        return tlv(0x0C, text.getBytes(Charset.forName("UTF-8")));
    }

    static byte[] utcTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(date).getBytes(Charset.forName("US-ASCII")));
    }

    static byte[] generalizedTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x18, format.format(date).getBytes(Charset.forName("US-ASCII")));
    }

    /**
     * 按 RFC 5280 选择时间编码：1950..2049 用 UTCTime（两位年），其余用 GeneralizedTime。
     *
     * <p>这里踩过坑：UTCTime 的两位年按 "YY &gt;= 50 视为 19YY" 解释，所以 2056 会被读成 1956，
     * 导致证书"有效期反向"。凡是可能超过 2049 的有效期都必须走 GeneralizedTime。
     */
    static byte[] time(Date date) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTime(date);
        int year = calendar.get(Calendar.YEAR);
        if (year >= 1950 && year <= 2049) {
            return utcTime(date);
        }
        return generalizedTime(date);
    }

    /** {@code [n] EXPLICIT ...} */
    static byte[] explicit(int tagNumber, byte[] content) {
        return tlv(0xA0 | tagNumber, content);
    }

    static byte[] oid(String dotted) {
        return tlv(0x06, encodeOid(dotted));
    }

    private static byte[] encodeOid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream(parts.length);
        out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long value = Long.parseLong(parts[i]);
            if (value < 0x80) {
                out.write((int) value);
                continue;
            }
            int shift = 0;
            while ((value >>> (shift + 7)) != 0) {
                shift += 7;
            }
            while (shift > 0) {
                out.write((int) (((value >>> shift) & 0x7F) | 0x80));
                shift -= 7;
            }
            out.write((int) (value & 0x7F));
        }
        return out.toByteArray();
    }
}
