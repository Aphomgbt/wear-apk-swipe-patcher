package dev;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 解析 zip 结构，检查每个 STORED（未压缩）条目的数据偏移是否满足 zipalign 要求：
 * 普通条目 4 字节对齐，{@code lib/**}{@code .so} 需要页对齐（16 KB 兼容 4 KB/16 KB 设备）。
 *
 * <p>Java 的 ZipEntry 不暴露数据偏移，所以这里手工解析 EOCD + 中央目录 + 本地头。
 */
public final class AlignCheck {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    public static void main(String[] args) throws Exception {
        for (String path : args) {
            check(new File(path));
        }
    }

    private static void check(File file) throws IOException {
        byte[] data = readAll(file);
        int eocd = findEocd(data);
        if (eocd < 0) {
            System.out.println(file + ": 找不到 EOCD");
            return;
        }
        int count = u16(data, eocd + 10);
        int cdOffset = u32(data, eocd + 16);

        System.out.println("===== " + file.getName() + " (" + data.length + " B, "
                + count + " entries) =====");

        int bad4 = 0;
        int badSo = 0;
        int stored = 0;
        int named = 0;
        int p = cdOffset;
        for (int i = 0; i < count; i++) {
            int method = u16(data, p + 10);
            int nameLen = u16(data, p + 28);
            int extraLen = u16(data, p + 30);
            int commentLen = u16(data, p + 32);
            int localOffset = u32(data, p + 42);
            String name = new String(data, p + 46, nameLen, ASCII);

            int localNameLen = u16(data, localOffset + 26);
            int localExtraLen = u16(data, localOffset + 28);
            long dataOffset = (long) localOffset + 30 + localNameLen + localExtraLen;

            if (method == 0) {
                stored++;
                long mod4 = dataOffset % 4;
                long mod16k = dataOffset % 16384;
                if (mod4 != 0) {
                    bad4++;
                    if (bad4 <= 5) {
                        System.out.println("  [未 4 字节对齐] " + name + " offset=" + dataOffset);
                    }
                }
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    named++;
                    if (mod16k != 0) {
                        badSo++;
                        if (badSo <= 8) {
                            System.out.println("  [.so 未 16KB 对齐] " + name
                                    + " offset=" + dataOffset + " mod16384=" + mod16k
                                    + " mod4096=" + (dataOffset % 4096));
                        }
                    }
                }
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
        System.out.println("  STORED 条目: " + stored
                + ", 未 4 字节对齐: " + bad4
                + ", 未压缩 .so: " + named + ", 未 16KB 对齐: " + badSo);
    }

    private static int findEocd(byte[] data) {
        for (int i = data.length - 22; i >= 0 && i >= data.length - 22 - 65536; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4B && data[i + 2] == 0x05
                    && data[i + 3] == 0x06) {
                return i;
            }
        }
        return -1;
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8)
                | ((d[i + 2] & 0xFF) << 16) | ((d[i + 3] & 0xFF) << 24);
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
