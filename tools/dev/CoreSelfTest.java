package dev;

import com.wearswipe.core.ApkInspector;
import com.wearswipe.core.ApkSignerTool;
import com.wearswipe.core.PatchOptions;
import com.wearswipe.core.SigningConfig;
import com.wearswipe.core.WearSwipePatcher;
import com.wearswipe.core.crypto.SelfSignedCertificates;

import java.io.File;
import java.io.FileOutputStream;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * 无框架自检测试（core 不依赖 JUnit，Android 端也要能跑）。
 * 用法：{@code CoreSelfTest <original.apk> <workdir>}
 */
public final class CoreSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File originalApk = new File(args[0]);
        File workDir = new File(args[1]);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IllegalStateException("无法创建目录 " + workDir);
        }

        testSelfSignedCertificate(workDir);
        testPatchOptionsValidation();
        testSigningSchemeSelection();
        testInspectOriginal(originalApk);
        testEndToEndPatch(originalApk, workDir);
        testSigningSchemes(originalApk, workDir);

        System.out.println();
        System.out.println("===== SELF TEST: passed=" + passed + " failed=" + failed + " =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testSelfSignedCertificate(File workDir) throws Exception {
        System.out.println("---- testSelfSignedCertificate ----");

        SelfSignedCertificates.KeyAndCert generated =
                SelfSignedCertificates.generate("WearSwipePatcherTest", 365 * 30);
        X509Certificate cert = generated.getCertificate();

        check("cert 不为 null", cert != null);
        check("CN 正确", cert.getSubjectX500Principal().getName().contains("WearSwipePatcherTest"));
        check("自签名 (subject == issuer)",
                cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()));
        check("密钥算法 RSA", "RSA".equals(cert.getPublicKey().getAlgorithm()));
        check("签名算法 SHA256withRSA",
                "SHA256withRSA".equalsIgnoreCase(cert.getSigAlgName()));
        check("X.509 v3", cert.getVersion() == 3);

        Date notBefore = cert.getNotBefore();
        Date notAfter = cert.getNotAfter();
        System.out.println("      notBefore = " + notBefore);
        System.out.println("      notAfter  = " + notAfter);
        check("notAfter 晚于 notBefore", notAfter.after(notBefore));
        check("notAfter 在未来", notAfter.after(new Date()));
        long years = (notAfter.getTime() - notBefore.getTime()) / (365L * 24 * 3600 * 1000);
        check("有效期约 30 年 (实际 " + years + ")", years >= 29 && years <= 31);

        X509Certificate shortLived = SelfSignedCertificates
                .createCertificate(generated.getKeyPair(), "ShortLived", 365 * 5);
        check("5 年证书 notAfter 在未来", shortLived.getNotAfter().after(new Date()));
        check("5 年证书 notAfter 晚于 notBefore",
                shortLived.getNotAfter().after(shortLived.getNotBefore()));

        File der = new File(workDir, "selftest-cert.der");
        FileOutputStream out = new FileOutputStream(der);
        try {
            out.write(cert.getEncoded());
        } finally {
            out.close();
        }
        System.out.println("      证书已导出: " + der.getAbsolutePath());

        SigningConfig config = SigningConfig.generateSelfSigned("WearSwipePatcherTest");
        check("SigningConfig 有私钥", config.getPrivateKey() != null);
        check("SigningConfig 有 1 张证书", config.getCertificateChain().size() == 1);
    }

    private static void testPatchOptionsValidation() {
        System.out.println("---- testPatchOptionsValidation ----");
        PatchOptions options = new PatchOptions();
        check("默认样式名", PatchOptions.DEFAULT_STYLE_NAME.equals(options.getStyleName()));
        check("默认不改 application 主题", !options.isPatchApplicationElement());
        check("默认不改所有 Activity", !options.isPatchAllActivities());
        check("默认开启对齐", options.isAlignOutput());
        check("默认开启校验", options.isVerifySignature());
        check("默认签名方案 V1 + V2",
                options.getSigningScheme() == PatchOptions.SigningScheme.V1_AND_V2);

        boolean threw = false;
        try {
            options.setStyleName("  ");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("空样式名被拒绝", threw);
    }

    private static void testSigningSchemeSelection() {
        System.out.println("---- testSigningSchemeSelection ----");
        PatchOptions.SigningScheme both = PatchOptions.SigningScheme.V1_AND_V2;

        check("V1 + V2 同时启用两种", both.isV1Enabled() && both.isV2Enabled());
        check("仅 V2 不启用 V1", !PatchOptions.SigningScheme.V2_ONLY.isV1Enabled()
                && PatchOptions.SigningScheme.V2_ONLY.isV2Enabled());
        check("仅 V1 不启用 V2", PatchOptions.SigningScheme.V1_ONLY.isV1Enabled()
                && !PatchOptions.SigningScheme.V1_ONLY.isV2Enabled());

        check("parse(\"v1\") -> 仅 V1",
                PatchOptions.SigningScheme.parse("v1") == PatchOptions.SigningScheme.V1_ONLY);
        check("parse(\" V2 \") -> 仅 V2",
                PatchOptions.SigningScheme.parse(" V2 ") == PatchOptions.SigningScheme.V2_ONLY);
        check("parse(\"v1 + v2\") -> V1 + V2",
                PatchOptions.SigningScheme.parse("v1 + v2") == both);
        check("parse(\"both\") -> V1 + V2",
                PatchOptions.SigningScheme.parse("both") == both);
        check("parse(V1_ONLY) -> 仅 V1",
                PatchOptions.SigningScheme.parse("V1_ONLY") == PatchOptions.SigningScheme.V1_ONLY);
        check("parse(乱码) -> null", PatchOptions.SigningScheme.parse("nonsense") == null);
        check("parse(null) -> null", PatchOptions.SigningScheme.parse(null) == null);
        check("parse(空串) -> null", PatchOptions.SigningScheme.parse("   ") == null);

        boolean threw = false;
        try {
            new PatchOptions().setSigningScheme(null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("setSigningScheme(null) 被拒绝", threw);

        check("switch 只改签名方案时其它选项保持默认",
                new PatchOptions().setSigningScheme(PatchOptions.SigningScheme.V1_ONLY)
                        .isAlignOutput());
    }

    private static void testInspectOriginal(File originalApk) throws Exception {
        System.out.println("---- testInspectOriginal ----");
        ApkInspector.Inspection info = ApkInspector.inspect(originalApk);
        System.out.println("      package=" + info.getPackageName()
                + " launcher=" + info.getLauncherActivity());
        check("可处理", info.isPatchable());
        check("包名非空", info.getPackageName() != null);
        check("启动 Activity 非空", info.getLauncherActivity() != null
                && !"(未找到)".equals(info.getLauncherActivity()));
        check("原始 APK 尚未打补丁", !info.isSwipeToDismissDisabled());
    }

    private static void testEndToEndPatch(File originalApk, File workDir) throws Exception {
        System.out.println("---- testEndToEndPatch ----");
        File output = new File(workDir, "selftest-patched.apk");
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException("无法删除旧输出 " + output);
        }

        WearSwipePatcher patcher = new WearSwipePatcher();
        WearSwipePatcher.Result result = patcher.patch(originalApk, output, null, null);

        check("输出文件存在", output.isFile() && output.length() > 0);
        check("补丁已应用", result.getReport().isPatchApplied());
        check("补丁样式拿到资源 ID", result.getReport().getPatchStyleResourceId() != 0);
        check("签名校验通过", result.getVerification() != null
                && result.getVerification().isVerified());

        ApkInspector.Inspection info = ApkInspector.inspect(output);
        check("输出里补丁样式存在", info.isPatchStylePresent());
        check("输出里 swipeToDismiss=false", info.isSwipeToDismissDisabled());
        check("输出已完整打补丁", info.isFullyPatched());
        check("启动 Activity 指向补丁样式",
                info.getLauncherThemeId() == info.getPatchStyleId());
        check("包名未被改动", info.getPackageName()
                .equals(ApkInspector.inspect(originalApk).getPackageName()));

        File twice = new File(workDir, "selftest-patched-twice.apk");
        WearSwipePatcher.Result second = patcher.patch(output, twice, null, null);
        check("二次补丁仍成功", second.getReport().isPatchApplied());
        check("二次补丁不重复分配资源 ID",
                second.getReport().getPatchStyleResourceId()
                        == result.getReport().getPatchStyleResourceId());

        System.out.println("      输出大小 = " + output.length() + " B");
        System.out.println("      二次输出大小 = " + twice.length() + " B");
    }

    /**
     * 三种签名方案各跑一次真实补丁，并分别用 apksig 与 zip 条目两种独立手段确认
     * "到底写没写 V1 / V2"。
     */
    private static void testSigningSchemes(File originalApk, File workDir) throws Exception {
        System.out.println("---- testSigningSchemes ----");
        int inputMinSdk = readMinSdkVersion(originalApk);
        System.out.println("      输入 APK minSdkVersion = " + inputMinSdk);

        PatchOptions.SigningScheme[] schemes = {
                PatchOptions.SigningScheme.V1_AND_V2,
                PatchOptions.SigningScheme.V1_ONLY,
                PatchOptions.SigningScheme.V2_ONLY
        };

        for (PatchOptions.SigningScheme scheme : schemes) {
            String tag = scheme.name().toLowerCase(java.util.Locale.US);
            File output = new File(workDir, "selftest-scheme-" + tag + ".apk");
            if (output.exists() && !output.delete()) {
                throw new IllegalStateException("无法删除旧输出 " + output);
            }

            PatchOptions options = new PatchOptions().setSigningScheme(scheme);
            WearSwipePatcher.Result result =
                    new WearSwipePatcher(options).patch(originalApk, output, null, null);
            ApkSignerTool.VerifyResult verification = result.getVerification();
            int v1Entries = countJarSignatureEntries(output);

            System.out.println("      " + scheme.getLabel()
                    + " -> " + output.length() + " B"
                    + ", META-INF 签名条目 " + v1Entries
                    + ", 校验起点 API "
                    + (verification == null ? -1 : verification.getMinCheckedPlatformVersion())
                    + ", schemes="
                    + (verification == null ? "-" : verification.describeSchemes()));
            for (String warning : result.getReport().getWarnings()) {
                System.out.println("        ! " + warning);
            }

            check(scheme.getLabel() + ": 补丁已应用", result.getReport().isPatchApplied());
            check(scheme.getLabel() + ": 签名校验通过",
                    verification != null && verification.isVerified());

            // 不依赖 apksig 的独立见证：V1 签名必须在 META-INF 下留下 .SF/.RSA 等条目
            check(scheme.getLabel() + (scheme.isV1Enabled()
                            ? ": 写入了 V1 签名（META-INF 条目 " + v1Entries + " 个）"
                            : ": 未写入 V1 签名"),
                    scheme.isV1Enabled() ? v1Entries > 0 : v1Entries == 0);

            check(scheme.getLabel() + (scheme.isV2Enabled()
                            ? ": 写入了 v2 签名块"
                            : ": 未写入 v2 签名块"),
                    verification != null
                            && verification.isVerifiedUsingV2() == scheme.isV2Enabled());

            check(scheme.getLabel() + ": 补丁样式仍然生效",
                    ApkInspector.inspect(output).isFullyPatched());

            if (!scheme.isV1Enabled()) {
                boolean warned = false;
                for (String warning : result.getReport().getWarnings()) {
                    if (warning.contains("API 24")) {
                        warned = true;
                    }
                }
                check(scheme.getLabel() + (inputMinSdk < ApkSignerTool.API_LEVEL_N
                                ? ": 给出了老系统兼容性警告"
                                : ": 无需老系统兼容性警告"),
                        (inputMinSdk < ApkSignerTool.API_LEVEL_N) == warned);
            }
        }
    }

    /** 数一数 APK 里有多少 V1（JAR）签名条目 —— 与 apksig 完全独立的见证。 */
    private static int countJarSignatureEntries(File apk) throws Exception {
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk);
        try {
            int count = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().toUpperCase(java.util.Locale.US);
                if (name.startsWith("META-INF/")
                        && (name.endsWith(".SF") || name.endsWith(".RSA")
                                || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    count++;
                }
            }
            return count;
        } finally {
            zip.close();
        }
    }

    /** 读原始 APK 的 minSdkVersion，读不到时返回 0。 */
    private static int readMinSdkVersion(File apk) {
        try {
            com.reandroid.apk.ApkModule module = com.reandroid.apk.ApkModule.loadApkFile(apk);
            try {
                Integer value = module.getAndroidManifestBlock().getMinSdkVersion();
                return value == null ? 0 : value.intValue();
            } finally {
                module.close();
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
