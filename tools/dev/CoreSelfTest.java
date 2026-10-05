package dev;

import com.wearswipe.core.ApkInspector;
import com.wearswipe.core.ApkSignerTool;
import com.wearswipe.core.ManifestUtil;
import com.wearswipe.core.PatchOptions;
import com.wearswipe.core.PatchReport;
import com.wearswipe.core.ResourcePatcher;
import com.wearswipe.core.SigningConfig;
import com.wearswipe.core.SigningKeyStore;
import com.wearswipe.core.WearSwipePatcher;
import com.wearswipe.core.crypto.SelfSignedCertificates;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResConfig;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.arsc.value.style.StyleBag;
import com.reandroid.arsc.value.style.StyleBagItem;

import java.io.File;
import java.io.FileOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * 无框架自检测试（core 不依赖 JUnit，Android 端也要能跑）。
 * 用法：{@code CoreSelfTest <original.apk> <workdir> [多Activity夹具.apk]}
 *
 * <p>第三个参数可选，由 {@code tools/dev/make-testfixture.sh} 生成，
 * 用来验证"每个 Activity 各自继承原主题"与"不覆盖同名原样式"。
 *
 * <p>用例分两类：纯算法（自签名证书、选项、密钥载体/加解密/导入格式）与
 * 真实 APK 端到端（补丁、三种签名方案、密钥复用后两次输出签名一致、多 Activity 夹具）。
 */
public final class CoreSelfTest {

    /** {@code android:textColor} 的框架属性资源 ID，用来确认别人的样式没被清空。 */
    private static final int ATTR_ANDROID_TEXT_COLOR = 0x01010098;

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File originalApk = new File(args[0]);
        File workDir = new File(args[1]);
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new IllegalStateException("无法创建目录 " + workDir);
        }
        File fixtureApk = args.length > 2 && args[2] != null && !args[2].isEmpty()
                ? new File(args[2]) : null;

        testSelfSignedCertificate(workDir);
        testPatchOptionsValidation();
        testSigningSchemeSelection();
        testSigningKeyStore(workDir);
        testInspectOriginal(originalApk);
        testEndToEndPatch(originalApk, workDir);
        testSigningSchemes(originalApk, workDir);
        testPersistentKeyReuse(fixtureApk != null && fixtureApk.isFile() ? fixtureApk : originalApk,
                workDir);
        testMultiActivityFixture(fixtureApk, workDir);

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

    /**
     * 密钥持久化相关的纯算法用例（不需要 APK）：自有载体往返、带口令备份的
     * 加解密与防篡改、格式自动识别，以及导入用户自己的 PKCS#12 密钥库。
     */
    private static void testSigningKeyStore(File workDir) throws Exception {
        System.out.println("---- testSigningKeyStore ----");
        SigningConfig config = SigningConfig.generateSelfSigned("WearSwipePatcherTest");
        String fingerprint = SigningKeyStore.fingerprintOf(config);
        check("指纹非空", fingerprint != null && !fingerprint.isEmpty());
        check("指纹是 SHA-256 的冒号分隔形式 (实际长度 " + (fingerprint == null ? 0 : fingerprint.length()) + ")",
                fingerprint != null && fingerprint.length() == 95 && fingerprint.charAt(2) == ':');
        check("描述里带指纹与有效期", SigningKeyStore.describe(config).contains(fingerprint));

        byte[] bundle = SigningKeyStore.toBundle(config);
        check("明文载体文件头是 " + SigningKeyStore.BUNDLE_MAGIC,
                startsWithMagic(bundle, SigningKeyStore.BUNDLE_MAGIC));
        check("明文载体往返后指纹不变",
                fingerprint.equals(SigningKeyStore.fingerprintOf(SigningKeyStore.fromBundle(bundle))));

        File bundleFile = new File(workDir, "selftest-key" + SigningKeyStore.BACKUP_EXTENSION);
        SigningKeyStore.saveBundle(config, bundleFile);
        check("saveBundle 写出非空文件", bundleFile.isFile() && bundleFile.length() > 0);
        check("saveBundle/loadBundle 往返后指纹不变",
                fingerprint.equals(SigningKeyStore.fingerprintOf(
                        SigningKeyStore.loadBundle(bundleFile))));

        char[] password = "self-test-pass".toCharArray();
        byte[] encrypted = SigningKeyStore.toEncryptedBundle(config, password);
        check("备份文件头是 " + SigningKeyStore.ENCRYPTED_MAGIC,
                startsWithMagic(encrypted, SigningKeyStore.ENCRYPTED_MAGIC));
        check("口令正确时解出的指纹不变",
                fingerprint.equals(SigningKeyStore.fingerprintOf(
                        SigningKeyStore.fromEncryptedBundle(encrypted, password))));

        boolean wrongPasswordRejected = false;
        try {
            SigningKeyStore.fromEncryptedBundle(encrypted, "wrong-password".toCharArray());
        } catch (GeneralSecurityException expected) {
            wrongPasswordRejected = true;
        }
        check("口令错误时明确报错，而不是给出坏密钥", wrongPasswordRejected);

        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 0x01;
        boolean tamperedRejected = false;
        try {
            SigningKeyStore.fromEncryptedBundle(tampered, password);
        } catch (GeneralSecurityException | java.io.IOException expected) {
            tamperedRejected = true;
        }
        check("密文被改动一个字节就解不开（AES-GCM 认证）", tamperedRejected);

        check("fromAnyFormat 认得明文载体",
                fingerprint.equals(SigningKeyStore.fingerprintOf(
                        SigningKeyStore.fromAnyFormat(bundle, null))));
        check("fromAnyFormat 认得带口令备份",
                fingerprint.equals(SigningKeyStore.fingerprintOf(
                        SigningKeyStore.fromAnyFormat(encrypted, password))));

        // 用户拿着自己原来的 PKCS#12 密钥库来接管：指纹必须与原库一致
        File p12 = new File(workDir, "selftest-import.p12");
        writePkcs12(config, p12, password);
        SigningConfig imported = SigningKeyStore.fromAnyFormat(
                SigningKeyStore.readAll(p12), password);
        check("能导入用户自己的 PKCS#12（指纹与原库一致）",
                fingerprint.equals(SigningKeyStore.fingerprintOf(imported)));
        check("导入后仍能签名（私钥可用）", imported.getPrivateKey() != null);

        check("换一把密钥，指纹必然不同",
                !fingerprint.equals(SigningKeyStore.fingerprintOf(
                        SigningConfig.generateSelfSigned("WearSwipePatcherTest"))));
        check("空数据被明确拒绝", isEmptyRejected());
    }

    /** 用生成的密钥建一个 PKCS#12 密钥库，模拟"用户自带密钥库"。 */
    private static void writePkcs12(SigningConfig config, File file, char[] password)
            throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(SigningKeyStore.DEFAULT_ALIAS, config.getPrivateKey(), password,
                config.getCertificateChain().toArray(new X509Certificate[0]));
        FileOutputStream out = new FileOutputStream(file);
        try {
            keyStore.store(out, password);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static boolean isEmptyRejected() {
        try {
            SigningKeyStore.fromAnyFormat(new byte[0], null);
            return false;
        } catch (Exception expected) {
            return true;
        }
    }

    private static boolean startsWithMagic(byte[] data, String magic) {
        if (data == null || data.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if ((data[i] & 0xff) != magic.charAt(i)) {
                return false;
            }
        }
        return true;
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

    /**
     * 端到端验证"密钥复用"这条最关键的行为：同一个密钥文件打两次补丁，
     * 两次输出的<b>签名证书必须完全一样</b>，这样新包才能覆盖安装到旧包上。
     *
     * <p>指纹同时从两处取证：{@code PatchReport} 里我们自己记的指纹，
     * 以及用 apksig 直接从输出 APK 里读出来的签名证书指纹（独立来源，不信任自己的报告）。
     */
    private static void testPersistentKeyReuse(File apk, File workDir) throws Exception {
        System.out.println("---- testPersistentKeyReuse ----");
        System.out.println("      用它来验证的输入 = " + apk.getName());
        int minSdk = readMinSdkVersion(apk);

        File keyFile = new File(workDir, "selftest-persistent-key" + SigningKeyStore.BACKUP_EXTENSION);
        deleteIfExists(keyFile);

        // 第一次：还没有密钥文件 -> 现生成一把，落盘（等价于 App 里第一次运行）
        SigningConfig generated = SigningConfig.generateSelfSigned("WearSwipePatcher");
        SigningKeyStore.saveBundle(generated, keyFile);
        String keyFingerprint = SigningKeyStore.fingerprintOf(generated);
        check("持久密钥文件已写出", keyFile.isFile() && keyFile.length() > 0);

        WearSwipePatcher patcher = new WearSwipePatcher();

        File first = new File(workDir, "selftest-key-reuse-1.apk");
        deleteIfExists(first);
        WearSwipePatcher.Result firstResult =
                patcher.patch(apk, first, SigningKeyStore.loadBundle(keyFile), null);

        // 第二次：同一个文件再打一次（等价于半年后给同一个 App 打新版本）
        File second = new File(workDir, "selftest-key-reuse-2.apk");
        deleteIfExists(second);
        WearSwipePatcher.Result secondResult =
                patcher.patch(apk, second, SigningKeyStore.loadBundle(keyFile), null);

        check("复用密钥的两次补丁都校验通过",
                firstResult.getVerification() != null && firstResult.getVerification().isVerified()
                        && secondResult.getVerification() != null
                        && secondResult.getVerification().isVerified());

        String reportFirst = firstResult.getReport().getSignerFingerprint();
        String reportSecond = secondResult.getReport().getSignerFingerprint();
        check("报告里的签名指纹 = 密钥文件的指纹",
                keyFingerprint.equals(reportFirst) && keyFingerprint.equals(reportSecond));
        check("报告标记为复用已保存的密钥",
                firstResult.getReport().isSignerFingerprintFromStoredKey()
                        && secondResult.getReport().isSignerFingerprintFromStoredKey());

        // 独立取证：直接从两个输出 APK 里读签名证书
        String outFirst = ApkSignerTool.verify(first, minSdk).getSignerFingerprint();
        String outSecond = ApkSignerTool.verify(second, minSdk).getSignerFingerprint();
        check("两个输出 APK 的签名证书指纹一致（可原地覆盖安装）",
                outFirst != null && outFirst.equals(outSecond));
        check("输出 APK 的证书指纹 = 密钥文件的指纹（复用确实生效了）",
                keyFingerprint.equals(outFirst));

        // 反证：不指定密钥时用的是临时密钥，指纹必须和持久密钥不同
        File temp = new File(workDir, "selftest-key-temp.apk");
        deleteIfExists(temp);
        WearSwipePatcher.Result tempResult = patcher.patch(apk, temp, null, null);
        String outTemp = ApkSignerTool.verify(temp, minSdk).getSignerFingerprint();
        check("不指定密钥时用临时密钥，指纹与持久密钥不同",
                outTemp != null && !keyFingerprint.equals(outTemp));
        check("临时密钥不会被标记为复用",
                !tempResult.getReport().isSignerFingerprintFromStoredKey());

        System.out.println("      持久密钥指纹 = " + keyFingerprint);
        System.out.println("      临时密钥指纹 = " + outTemp);
    }

    /**
     * 用多 Activity 夹具验证两件只看代码确认不了的事：
     * <ol>
     *   <li>{@code --all-activities} 时每个 Activity 各自继承<b>它自己</b>原来的主题；
     *       （旧实现一律套启动 Activity 的补丁主题，会改掉其它页面的外观与窗口行为）</li>
     *   <li>原 APK 自己就定义了同名样式 {@code style/WearNoSwipeUnityTheme} 时，
     *       工具必须避让而不是把它 {@code clear()} 掉。</li>
     * </ol>
     *
     * <p>夹具由 {@code tools/dev/make-testfixture.sh} 用 aapt2 生成，里面有 4 个 Activity：
     * 自己声明主题的、主题不同的、没声明主题的（继承 application）、用框架主题的。
     */
    private static void testMultiActivityFixture(File fixtureApk, File workDir) throws Exception {
        System.out.println("---- testMultiActivityFixture ----");
        if (fixtureApk == null || !fixtureApk.isFile()) {
            System.out.println("      (跳过：没有夹具 APK；"
                    + "可先运行 tools/dev/make-testfixture.sh 生成)");
            return;
        }

        ApkInspector.Inspection before = ApkInspector.inspect(fixtureApk);
        System.out.println("      夹具包名 = " + before.getPackageName());
        check("夹具: .ThirdActivity 原本没声明主题，靠继承 application",
                activityLine(before, ".ThirdActivity").contains("继承 application"));
        check("夹具: 清单里 4 个 Activity", before.getActivityThemeCount() == 4);
        check("夹具: 打补丁前没有 Activity 关掉侧滑",
                before.getActivitiesWithSwipeDisabled() == 0);
        check("夹具: 原 APK 自带同名样式 style/" + PatchOptions.DEFAULT_STYLE_NAME,
                before.isPatchStylePresent() && !before.isSwipeToDismissDisabled());
        int foreignParentId = before.getPatchStyleParentId();

        File output = new File(workDir, "selftest-multi-activity.apk");
        deleteIfExists(output);
        PatchOptions options = new PatchOptions()
                .setPatchAllActivities(true)
                .setPatchApplicationElement(true);
        WearSwipePatcher.Result result =
                new WearSwipePatcher(options).patch(fixtureApk, output, null, null);
        PatchReport report = result.getReport();
        String styleName = report.getPatchStyleName();
        System.out.println("      补丁样式名 = " + styleName
                + " (id=0x" + Integer.toHexString(report.getPatchStyleResourceId()) + ")");

        check("撞名时改用避让名 (实际 " + styleName + ")",
                !PatchOptions.DEFAULT_STYLE_NAME.equals(styleName));
        check("给出了同名样式告警", hasWarning(report, "同名样式"));
        check("4 个 Activity + application 共 5 处都写入了主题", report.getAppliedCount() == 5);

        ApkInspector.Inspection after = ApkInspector.inspect(output);
        System.out.print(after.describe());
        check("输出里 4 个 Activity 全部关掉侧滑",
                after.getActivitiesWithSwipeDisabled() == 4);
        check("启动 Activity 生效的是补丁样式", after.isFullyPatched());

        String mainLine = activityLine(after, ".MainActivity");
        String secondLine = activityLine(after, ".SecondActivity");
        String thirdLine = activityLine(after, ".ThirdActivity");
        String fourthLine = activityLine(after, ".FourthActivity");
        String derived = styleName + ".SecondActivity";
        check(".SecondActivity 拿到独立派生样式 " + derived, secondLine.contains(derived));
        check(".SecondActivity 继承它自己的 style/OtherTheme",
                secondLine.contains("parent=style/OtherTheme"));
        check(".MainActivity 继承原主题 style/AppTheme",
                mainLine.contains("parent=style/AppTheme"));
        check(".ThirdActivity（没声明主题）与启动 Activity 共用一个补丁样式",
                thirdLine.contains(styleName) && !thirdLine.contains(styleName + ".ThirdActivity"));
        check(".ThirdActivity 的 parent 是 application 的 style/AppTheme",
                thirdLine.contains("parent=style/AppTheme"));
        check(".FourthActivity 继承它自己的框架主题 style/Theme.DeviceDefault",
                fourthLine.contains("parent=style/Theme.DeviceDefault"));

        // 独立读回资源表（不经过 ResourcePatcher），确认别人的同名样式没被动过
        StyleFacts foreign = readStyleFacts(output, PatchOptions.DEFAULT_STYLE_NAME);
        System.out.println("      原同名样式 " + PatchOptions.DEFAULT_STYLE_NAME + ": " + foreign);
        check("原同名样式没有被清空（android:textColor 还在）", foreign.hasTextColor);
        check("原同名样式没有被写入 windowSwipeToDismiss", !foreign.swipeDisabled);
        check("原同名样式的 parent 没有被改", foreign.parentId == foreignParentId);

        StyleFacts patch = readStyleFacts(output, styleName);
        System.out.println("      补丁样式 " + styleName + ": " + patch);
        check("补丁样式里 windowSwipeToDismiss=false", patch.swipeDisabled);
        check("补丁样式继承启动 Activity 的原主题",
                patch.parentId == before.getLauncherThemeId());
        checkMultiActivityIdempotency(output, workDir, options, styleName, derived,
                report, patch.parentId);
    }

    /** 二次补丁：不能叠加后缀、不能重复分配资源、父主题不能漂移。 */
    private static void checkMultiActivityIdempotency(File patchedOnce, File workDir,
                                                      PatchOptions options, String styleName,
                                                      String derived, PatchReport first,
                                                      int firstParentId) throws Exception {
        File twice = new File(workDir, "selftest-multi-activity-twice.apk");
        deleteIfExists(twice);
        PatchReport second = new WearSwipePatcher(options)
                .patch(patchedOnce, twice, null, null).getReport();
        ApkInspector.Inspection again = ApkInspector.inspect(twice);
        check("二次补丁样式名不变 (" + second.getPatchStyleName() + ")",
                styleName.equals(second.getPatchStyleName()));
        check("二次补丁不重复分配资源 ID",
                second.getPatchStyleResourceId() == first.getPatchStyleResourceId());
        check("二次补丁 .SecondActivity 样式名不叠加后缀",
                activityLine(again, ".SecondActivity").contains(derived));
        check("二次补丁仍然 4 个 Activity 关掉侧滑",
                again.getActivitiesWithSwipeDisabled() == 4);
        check("二次补丁父主题没有漂移",
                readStyleFacts(twice, styleName).parentId == firstParentId);
        // 原 APK 的同名冲突一直都在，所以每次都会提示；关键是名字与资源 ID 没有变化
        check("二次补丁仍如实提示同名冲突", hasWarning(second, "同名样式"));
    }

    /** 独立读回某个样式的事实：是否还有 textColor、是否关了 swipe、parent 是谁。 */
    private static final class StyleFacts {
        boolean hasTextColor;
        boolean swipeDisabled;
        int parentId;

        @Override
        public String toString() {
            return "textColor=" + hasTextColor + ", swipe=" + swipeDisabled
                    + ", parent=0x" + Integer.toHexString(parentId);
        }
    }

    private static StyleFacts readStyleFacts(File apk, String styleName) throws Exception {
        StyleFacts facts = new StyleFacts();
        ApkModule module = ApkModule.loadApkFile(apk);
        try {
            TableBlock table = module.getTableBlock();
            PackageBlock pkg = ManifestUtil.findAppPackage(table,
                    module.getAndroidManifestBlock().getPackageName());
            if (pkg == null) {
                return facts;
            }
            Entry entry = pkg.getEntry(ResConfig.getDefault(), "style", styleName);
            if (entry == null || !entry.isDefined()) {
                return facts;
            }
            StyleBag bag = StyleBag.create(entry);
            if (bag == null) {
                return facts;
            }
            facts.parentId = bag.getParentId();
            facts.hasTextColor = bag.get(ATTR_ANDROID_TEXT_COLOR) != null;
            StyleBagItem item = bag.get(ResourcePatcher.ATTR_ANDROID_WINDOW_SWIPE_TO_DISMISS);
            facts.swipeDisabled = item != null
                    && item.getValueType() == ValueType.BOOLEAN && item.getValue() == 0;
            return facts;
        } finally {
            module.close();
        }
    }

    /** 从巡检结果里取出某个 Activity 的那一行；没有就返回空串。 */
    private static String activityLine(ApkInspector.Inspection info, String activityName) {
        for (String line : info.getActivityThemes()) {
            if (line.startsWith(activityName + ":")) {
                return line;
            }
        }
        return "";
    }

    private static boolean hasWarning(PatchReport report, String keyword) {
        for (String warning : report.getWarnings()) {
            if (warning.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteIfExists(File file) {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("无法删除旧输出 " + file);
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
