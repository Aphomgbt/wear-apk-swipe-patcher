package com.wearswipe.core;

import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ZipAlign;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;

import java.io.File;
import java.io.IOException;

/**
 * 端到端入口：APK -> 资源层补丁 -> 重新打包 -> zipalign -> 签名 -> 校验。
 *
 * <p>写入哪些签名方案由 {@link PatchOptions#getSigningScheme()} 决定，默认 V1 + V2。
 *
 * <p>全程纯 Java，不依赖 aapt2 / zipalign / apksigner 等外部可执行文件，
 * 因此可以直接跑在 Android 设备上。
 */
public final class WearSwipePatcher {

    /** 一次完整补丁的结果。 */
    public static final class Result {
        private final File outputApk;
        private final PatchReport report;
        private final ApkSignerTool.VerifyResult verification;

        Result(File outputApk, PatchReport report, ApkSignerTool.VerifyResult verification) {
            this.outputApk = outputApk;
            this.report = report;
            this.verification = verification;
        }

        public File getOutputApk() {
            return outputApk;
        }

        public PatchReport getReport() {
            return report;
        }

        /** 关闭校验时可能为 {@code null}。 */
        public ApkSignerTool.VerifyResult getVerification() {
            return verification;
        }

        public long getOutputSize() {
            return outputApk.length();
        }
    }

    private final PatchOptions options;

    public WearSwipePatcher() {
        this(new PatchOptions());
    }

    public WearSwipePatcher(PatchOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options 不能为 null");
        }
        this.options = options;
    }

    public PatchOptions getOptions() {
        return options;
    }

    /**
     * 执行补丁。
     *
     * @param inputApk     用户选择的原始 APK
     * @param outputApk    目标输出文件（会被覆盖）
     * @param signingConfig 签名配置；为 {@code null} 时自动生成一张自签名证书
     */
    public Result patch(File inputApk, File outputApk, SigningConfig signingConfig, Progress progress)
            throws Exception {
        if (inputApk == null || !inputApk.isFile()) {
            throw new IOException("找不到输入 APK: " + inputApk);
        }
        Progress callback = progress == null ? Progress.NOOP : progress;

        File parent = outputApk.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录: " + parent);
        }

        PatchReport report = new PatchReport();
        int minSdk;
        File workDir = createWorkDir(parent, outputApk.getName());
        File unsigned = new File(workDir, "patched-unaligned.apk");

        callback.onProgress(2, "读取 APK");
        ApkModule apk = ApkModule.loadApkFile(inputApk);
        try {
            minSdk = readMinSdkVersion(apk);
            new ResourcePatcher(apk, options, report, callback).apply();
            callback.onProgress(80, "重新打包 APK");
            apk.writeApk(unsigned);
        } finally {
            closeQuietly(apk);
        }

        try {
            File toSign = unsigned;
            if (options.isAlignOutput()) {
                callback.onProgress(85, "4 字节对齐");
                File aligned = new File(workDir, "patched-aligned.apk");
                ZipAlign.alignApk(unsigned, aligned);
                toSign = aligned;
            }

            callback.onProgress(90, "重新签名");
            boolean fromStoredKey = signingConfig != null;
            if (signingConfig == null) {
                signingConfig = SigningConfig.generateSelfSigned("WearSwipePatcher");
            }
            report.setSignerFingerprint(SigningKeyStore.fingerprintOf(signingConfig));
            report.setSignerFingerprintFromStoredKey(fromStoredKey);
            PatchOptions.SigningScheme scheme = options.getSigningScheme();
            if (!scheme.isV1Enabled() && minSdk < ApkSignerTool.API_LEVEL_N) {
                // 只签 V2 就意味着没有 V1 签名，Android 7.0 以下装不上 —— 这是用户选择的必然代价
                report.addWarning("签名方案选了「" + scheme.getLabel() + "」，产物没有 V1 签名，"
                        + "无法安装到 Android 7.0 (API 24) 以下；本 APK 的 minSdkVersion="
                        + minSdk + "。如需兼顾老设备请改回「V1 + V2」。");
            }
            ApkSignerTool.sign(toSign, outputApk, signingConfig, minSdk, scheme);

            ApkSignerTool.VerifyResult verification = null;
            if (options.isVerifySignature()) {
                callback.onProgress(97, "校验签名");
                verification = ApkSignerTool.verify(outputApk, minSdk, scheme);
                if (!verification.isVerified()) {
                    throw new IOException("输出 APK 签名校验失败: " + verification.getErrors());
                }
            }
            callback.onProgress(100, "完成");
            return new Result(outputApk, report, verification);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static int readMinSdkVersion(ApkModule apk) {
        try {
            AndroidManifestBlock manifest = apk.getAndroidManifestBlock();
            if (manifest != null) {
                Integer minSdk = manifest.getMinSdkVersion();
                if (minSdk != null && minSdk.intValue() > 0) {
                    return minSdk.intValue();
                }
            }
        } catch (Throwable ignored) {
            // 退回默认值
        }
        return 21;
    }

    private static File createWorkDir(File parent, String outputName) throws IOException {
        File base = parent != null ? parent : new File(System.getProperty("java.io.tmpdir"));
        File dir = new File(base, "wear-swipe-work-" + System.nanoTime());
        if (!dir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + dir);
        }
        return dir;
    }

    private static void closeQuietly(ApkModule apk) {
        if (apk != null) {
            try {
                apk.close();
            } catch (Throwable ignored) {
                // 忽略关闭异常
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
