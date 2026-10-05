package dev;

import com.wearswipe.core.ApkSignerTool;
import com.wearswipe.core.SigningConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把 aapt2 产出的 {@code base.apk} 与 d8 产出的 {@code classes*.dex} 合并成一个 APK，
 * 再做对齐与签名。
 *
 * <p>除了 dex，还必须把依赖 jar 里的**运行时资源**原样带进去。最典型的是 ARSCLib 的
 * {@code frameworks/android/android-23..36.apk} —— 它在运行时这样加载：
 * <pre>
 *   AndroidFrameworks.class.getResourceAsStream("/frameworks/android/android-34.apk")
 * </pre>
 * Android 的 {@code PathClassLoader} 会从 APK zip 里读这些条目，所以缺了它们，
 * 一切需要框架资源解析的 APK（启动 Activity 与 application 都没显式主题、
 * 又没命中自动候选时）都会在设备上抛 IOException。用 {@code --res-jar} 指定即可。
 *
 * <p>最后两步直接用本项目的 core（{@link com.reandroid.archive.ZipAlign} +
 * {@link ApkSignerTool}），等于顺带把 core 的“打包 → 对齐 → 签名”链路又验一遍。
 *
 * <pre>
 *   PackageApk &lt;base.apk&gt; &lt;out.apk&gt; &lt;dex|目录&gt;... [--res-jar x.jar]... [--unsigned]
 * </pre>
 */
public final class PackageApk {

    /** 与 app/build.gradle 和 build-apk.sh 保持一致。 */
    private static final int MIN_SDK = 26;

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<String>();
        List<File> resourceJars = new ArrayList<File>();
        boolean sign = true;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--unsigned".equals(arg)) {
                sign = false;
            } else if ("--res-jar".equals(arg)) {
                resourceJars.add(new File(args[++i]));
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() < 3) {
            System.out.println("用法: PackageApk <base.apk> <out.apk> <dex|目录>..."
                    + " [--res-jar x.jar]... [--unsigned]");
            System.exit(2);
        }

        File base = new File(positional.get(0));
        File output = new File(positional.get(1));
        File parent = output.getAbsoluteFile().getParentFile();
        List<File> dexFiles = new ArrayList<File>();
        for (int i = 2; i < positional.size(); i++) {
            collectDex(new File(positional.get(i)), dexFiles);
        }
        if (dexFiles.isEmpty()) {
            throw new IllegalStateException("没有找到任何 dex");
        }
        System.out.println("base  : " + base.getAbsolutePath() + " (" + base.length() + " B)");
        for (File dex : dexFiles) {
            System.out.println("dex   : " + dex.getAbsolutePath() + " (" + dex.length() + " B)");
        }

        for (File jar : resourceJars) {
            System.out.println("resjar: " + jar.getAbsolutePath());
        }

        File merged = new File(parent, output.getName() + ".merged.apk");
        merge(base, merged, dexFiles, resourceJars);

        File aligned = new File(parent, output.getName() + ".aligned.apk");
        com.reandroid.archive.ZipAlign.alignApk(merged, aligned);

        if (sign) {
            SigningConfig signing = SigningConfig.generateSelfSigned("WearSwipePatcherApp");
            ApkSignerTool.sign(aligned, output, signing, MIN_SDK);
            ApkSignerTool.VerifyResult verify = ApkSignerTool.verify(output, MIN_SDK);
            System.out.println("签名  : " + verify.describeSchemes()
                    + " verified=" + verify.isVerified());
            if (!verify.isVerified()) {
                throw new IllegalStateException("签名校验失败: " + verify.getErrors());
            }
        } else {
            copy(aligned, output);
        }

        if (!merged.delete()) {
            merged.deleteOnExit();
        }
        if (!aligned.delete()) {
            aligned.deleteOnExit();
        }
        System.out.println("out   : " + output.getAbsolutePath() + " (" + output.length() + " B)");
    }

    private static void collectDex(File file, List<File> out) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children);
                for (File child : children) {
                    collectDex(child, out);
                }
            }
            return;
        }
        if (file.isFile() && file.getName().endsWith(".dex")) {
            out.add(file);
        }
    }

    /** 把 base.apk 的所有条目、依赖 jar 的运行时资源、以及 dex 合并起来。 */
    private static void merge(File base, File target, List<File> dexFiles,
                              List<File> resourceJars) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法删除旧文件 " + target);
        }
        Set<String> names = new HashSet<String>();
        ZipFile source = new ZipFile(base);
        try {
            ZipOutputStream out = new ZipOutputStream(new FileOutputStream(target));
            try {
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || names.contains(entry.getName())) {
                        continue;
                    }
                    names.add(entry.getName());
                    writeEntry(out, entry.getName(), entry.getMethod(),
                            readAll(source.getInputStream(entry)), entry.getTime());
                }
                for (File jar : resourceJars) {
                    copyJarResources(out, jar, names);
                }
                for (File dex : dexFiles) {
                    String name = dex.getName();
                    if (names.contains(name)) {
                        throw new IOException("重复的 dex 名: " + name);
                    }
                    names.add(name);
                    writeEntry(out, name, ZipEntry.DEFLATED,
                            readAll(new FileInputStream(dex)), 0L);
                }
            } finally {
                out.close();
            }
        } finally {
            source.close();
        }
    }

    /**
     * 把 jar 里的运行时资源原样搬进 APK：跳过 {@code .class} 与 {@code META-INF/}，
     * 保留 {@code frameworks/android/*.apk}、{@code *.properties} 这类需要被
     * {@code Class.getResourceAsStream} 读到的文件。
     */
    private static void copyJarResources(ZipOutputStream out, File jar, Set<String> names)
            throws IOException {
        int copied = 0;
        long bytes = 0;
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()
                        || name.endsWith(".class")
                        || name.startsWith("META-INF/")
                        || names.contains(name)) {
                    continue;
                }
                byte[] data = readAll(zip.getInputStream(entry));
                names.add(name);
                writeEntry(out, name, ZipEntry.DEFLATED, data, entry.getTime());
                copied++;
                bytes += data.length;
            }
        } finally {
            zip.close();
        }
        if (copied > 0) {
            System.out.println("resjar: 从 " + jar.getName() + " 带入 " + copied
                    + " 个资源文件 (" + bytes + " B)");
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, int method,
                                   byte[] data, long time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(method);
        // dex 用固定时间戳，让构建结果可复现
        entry.setTime(time > 0 ? time : 0L);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1 << 16];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
