package dev;

import com.wearswipe.core.ApkInspector;
import com.wearswipe.core.ApkSignerTool;
import com.wearswipe.core.PatchOptions;
import com.wearswipe.core.PatchReport;
import com.wearswipe.core.Progress;
import com.wearswipe.core.SigningConfig;
import com.wearswipe.core.SigningKeyStore;
import com.wearswipe.core.WearSwipePatcher;

import java.io.File;

/**
 * 桌面端调试用 CLI。Android 端 UI 用的是同一套 core 代码。
 *
 * <pre>
 *   inspect &lt;apk&gt;
 *   verify  &lt;apk&gt; [--min-sdk N] [--scheme v1|v2|v1+v2]
 *   patch   &lt;in.apk&gt; &lt;out.apk&gt; [--style NAME] [--parent STYLE]
 *                              [--all-activities] [--application]
 *                              [--no-align] [--no-verify]
 *                              [--scheme v1|v2|v1+v2]
 *                              [--keystore FILE --storetype JKS --storepass P
 *                               --alias A --keypass P]
 *                              [--save-key FILE] [--key FILE [--keypass P]]
 * </pre>
 *
 * <p>{@code --save-key} 的用法就是"第二次带同一个路径"：文件不存在时生成一把新密钥
 * 并存下来（补丁成功后），已存在时直接复用。这样同一个应用重复补丁的输出签名一致，
 * 新版本可以直接覆盖安装。
 */
public final class PatcherCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        if ("inspect".equals(command)) {
            inspect(args[1]);
        } else if ("verify".equals(command)) {
            verify(args);
        } else if ("patch".equals(command)) {
            patch(args);
        } else {
            usage();
            System.exit(2);
        }
    }

    private static void inspect(String path) throws Exception {
        File apk = new File(path);
        ApkInspector.Inspection a = ApkInspector.inspect(apk);
        System.out.println("###### INSPECT " + apk.getAbsolutePath());
        System.out.print(a.describe());
        System.out.println("###### END");
    }

    private static void verify(String[] args) throws Exception {
        File apk = new File(args[1]);
        int minSdk = 21;
        PatchOptions.SigningScheme scheme = PatchOptions.SigningScheme.V1_AND_V2;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if ("--min-sdk".equals(a)) {
                minSdk = Integer.parseInt(args[++i]);
            } else if ("--scheme".equals(a)) {
                scheme = parseScheme(args[++i]);
            } else {
                throw new IllegalArgumentException("未知参数: " + a);
            }
        }

        ApkSignerTool.VerifyResult result = ApkSignerTool.verify(apk, minSdk, scheme);
        System.out.println("###### VERIFY " + apk.getAbsolutePath());
        System.out.println("verified = " + result.isVerified());
        System.out.println("schemes  = " + result.describeSchemes());
        System.out.println("校验起点 = API " + result.getMinCheckedPlatformVersion());
        for (String error : result.getErrors()) {
            System.out.println("  error: " + error);
        }
        System.out.println("###### END");
    }

    /** 解析签名方案，认不出来时直接报错而不是悄悄退回默认值。 */
    private static PatchOptions.SigningScheme parseScheme(String value) {
        PatchOptions.SigningScheme scheme = PatchOptions.SigningScheme.parse(value);
        if (scheme == null) {
            throw new IllegalArgumentException(
                    "无法识别的签名方案: " + value + "（可选 v1 / v2 / v1+v2）");
        }
        return scheme;
    }

    private static void patch(String[] args) throws Exception {
        File in = new File(args[1]);
        File out = new File(args[2]);

        PatchOptions options = new PatchOptions();
        SigningConfig signing = null;
        File keyStore = null;
        String storeType = null;
        char[] storePass = null;
        String alias = null;
        char[] keyPass = null;
        File keyFile = null;
        File saveKeyFile = null;

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("--style".equals(a)) {
                options.setStyleName(args[++i]);
            } else if ("--parent".equals(a)) {
                options.setParentStyleName(args[++i]);
            } else if ("--all-activities".equals(a)) {
                options.setPatchAllActivities(true);
            } else if ("--application".equals(a)) {
                options.setPatchApplicationElement(true);
            } else if ("--no-auto-parent".equals(a)) {
                options.setAutoDetectParent(false);
            } else if ("--no-align".equals(a)) {
                options.setAlignOutput(false);
            } else if ("--no-verify".equals(a)) {
                options.setVerifySignature(false);
            } else if ("--scheme".equals(a)) {
                options.setSigningScheme(parseScheme(args[++i]));
            } else if ("--keystore".equals(a)) {
                keyStore = new File(args[++i]);
            } else if ("--storetype".equals(a)) {
                storeType = args[++i];
            } else if ("--storepass".equals(a)) {
                storePass = args[++i].toCharArray();
            } else if ("--alias".equals(a)) {
                alias = args[++i];
            } else if ("--keypass".equals(a)) {
                keyPass = args[++i].toCharArray();
            } else if ("--key".equals(a)) {
                keyFile = new File(args[++i]);
            } else if ("--save-key".equals(a)) {
                saveKeyFile = new File(args[++i]);
            } else {
                throw new IllegalArgumentException("未知参数: " + a);
            }
        }

        if (keyStore != null && (keyFile != null || saveKeyFile != null)) {
            throw new IllegalArgumentException(
                    "--keystore 与 --key / --save-key 只能选一种");
        }

        // 复用密钥的两种来源：--key 指定的文件，以及 --save-key 指向的已存在文件
        File reuseFile = keyFile;
        if (reuseFile == null && saveKeyFile != null && saveKeyFile.isFile()) {
            reuseFile = saveKeyFile;
        }

        // --save-key 指向的密钥还不存在时，先在这里生成（补丁成功后再落盘）
        boolean saveNewKey = saveKeyFile != null && reuseFile == null;

        if (keyStore != null) {
            signing = SigningConfig.fromKeyStore(keyStore, storeType, storePass, alias, keyPass);
        } else if (reuseFile != null) {
            if (!reuseFile.isFile()) {
                throw new IllegalArgumentException(
                        "找不到密钥文件: " + reuseFile.getAbsolutePath());
            }
            signing = SigningKeyStore.fromAnyFormat(SigningKeyStore.readAll(reuseFile), keyPass);
            System.out.println("复用密钥文件: " + reuseFile.getAbsolutePath());
        } else if (saveNewKey) {
            signing = SigningConfig.generateSelfSigned("WearSwipePatcher");
        }

        System.out.println("###### PATCH");
        System.out.println(options);
        if (signing != null) {
            System.out.println("签名密钥: " + SigningKeyStore.describe(signing));
        }
        System.out.println("in : " + in.getAbsolutePath() + " (" + in.length() + " bytes)");

        final long start = System.currentTimeMillis();
        WearSwipePatcher patcher = new WearSwipePatcher(options);
        WearSwipePatcher.Result result = patcher.patch(in, out, signing, new Progress() {
            @Override
            public void onProgress(int percent, String message) {
                System.out.println("  [" + percent + "%] " + message);
            }
        });

        PatchReport report = result.getReport();
        System.out.println();
        System.out.println("---- 补丁报告 ----");
        System.out.print(report);
        System.out.println("---- 输出 ----");
        System.out.println("out : " + result.getOutputApk().getAbsolutePath()
                + " (" + result.getOutputSize() + " bytes)");
        if (result.getVerification() != null) {
            System.out.println("签名: " + result.getVerification().describeSchemes()
                    + " verified=" + result.getVerification().isVerified());
        }
        if (report.getSignerFingerprint() != null) {
            System.out.println("签名指纹: " + report.getSignerFingerprint()
                    + (report.isSignerFingerprintFromStoredKey()
                            ? "（指定/复用的密钥）" : "（本次临时生成）"));
        }
        if (saveNewKey) {
            File parent = saveKeyFile.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                System.out.println("警告: 无法创建目录 " + parent.getAbsolutePath());
            }
            SigningKeyStore.saveBundle(signing, saveKeyFile);
            System.out.println("密钥已保存: " + saveKeyFile.getAbsolutePath()
                    + "（下次带同样的 --save-key 即复用这把密钥）");
        }
        System.out.println("耗时: " + (System.currentTimeMillis() - start) + " ms");
        System.out.println("###### END");
    }

    private static void usage() {
        System.out.println("用法:");
        System.out.println("  inspect <apk>");
        System.out.println("  verify  <apk> [--min-sdk N] [--scheme v1|v2|v1+v2]");
        System.out.println("  patch   <in.apk> <out.apk> [--style NAME] [--parent STYLE]"
                + " [--all-activities] [--application] [--no-auto-parent]"
                + " [--no-align] [--no-verify] [--scheme v1|v2|v1+v2]"
                + " [--keystore FILE --storetype JKS --storepass P --alias A --keypass P]"
                + " [--save-key FILE] [--key FILE [--keypass P]]");
    }
}
