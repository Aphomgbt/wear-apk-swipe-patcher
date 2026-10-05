package dev;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 比较两个 APK：按"解压后内容的 SHA-256"逐条目对比。
 *
 * <p>刻意不看压缩后字节 / CRC，因为 zipalign 会重写整个 zip 的所有偏移与压缩结果，
 * 只有解压后的内容才代表"应用是否真的被改了"。
 */
public final class ApkDiff {

    public static void main(String[] args) throws Exception {
        File left = new File(args[0]);
        File right = new File(args[1]);
        new ApkDiff(left, right).run();
    }

    private final File leftFile;
    private final File rightFile;

    private ApkDiff(File leftFile, File rightFile) {
        this.leftFile = leftFile;
        this.rightFile = rightFile;
    }

    private void run() throws IOException {
        Map<String, String> left = digest(leftFile);
        Map<String, String> right = digest(rightFile);

        TreeSet<String> allNames = new TreeSet<String>();
        allNames.addAll(left.keySet());
        allNames.addAll(right.keySet());

        List<String> changed = new ArrayList<String>();
        List<String> same = new ArrayList<String>();
        List<String> onlyLeft = new ArrayList<String>();
        List<String> onlyRight = new ArrayList<String>();

        long sameBytes = 0;
        for (String name : allNames) {
            String h1 = left.get(name);
            String h2 = right.get(name);
            if (h1 == null) {
                onlyRight.add(name);
            } else if (h2 == null) {
                onlyLeft.add(name);
            } else if (h1.equals(h2)) {
                same.add(name);
                sameBytes += h1.length();
            } else {
                changed.add(name);
            }
        }

        System.out.println("===== APK DIFF =====");
        System.out.println("左: " + leftFile.getAbsolutePath() + "  (" + leftFile.length() + " B, "
                + left.size() + " 条目)");
        System.out.println("右: " + rightFile.getAbsolutePath() + "  (" + rightFile.length() + " B, "
                + right.size() + " 条目)");
        System.out.println();
        System.out.println("内容相同条目 : " + same.size());
        System.out.println("内容变化条目 : " + changed.size());
        System.out.println("仅左有       : " + onlyLeft.size());
        System.out.println("仅右有       : " + onlyRight.size());

        print("仅左有（被删除）", onlyLeft);
        print("仅右有（新增）", onlyRight);
        print("内容变化", changed);

        int nonMetaChanged = 0;
        for (String name : changed) {
            if (!name.startsWith("META-INF/")) {
                nonMetaChanged++;
            }
        }
        System.out.println();
        System.out.println(">>> 除 META-INF 签名相关文件外的内容变化条目数: " + nonMetaChanged);
        System.out.println(">>> 这些才是「应用真正被改动」的范围，其余全部逐字节未变");
        System.out.println("===== END =====");
    }

    private static void print(String title, List<String> items) {
        System.out.println();
        System.out.println("---- " + title + " (" + items.size() + ") ----");
        int shown = 0;
        for (String name : items) {
            if (shown++ >= 60) {
                System.out.println("  ... 其余 " + (items.size() - 60) + " 项省略");
                break;
            }
            System.out.println("  " + name);
        }
    }

    private static Map<String, String> digest(File apk) throws IOException {
        Map<String, String> result = new TreeMap<String, String>();
        ZipFile zip = new ZipFile(apk);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                sha.reset();
                InputStream in = zip.getInputStream(entry);
                try {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        sha.update(buffer, 0, read);
                    }
                } finally {
                    in.close();
                }
                result.put(entry.getName(), toHex(sha.digest()));
            }
            return result;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            zip.close();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
