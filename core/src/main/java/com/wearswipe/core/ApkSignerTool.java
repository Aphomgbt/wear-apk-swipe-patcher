package com.wearswipe.core;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

/**
 * 用 Google apksig 对 APK 重新签名并校验。
 *
 * <p>启用哪些方案由调用方通过 {@link PatchOptions.SigningScheme} 指定（默认 V1 + V2），
 * 在此基础上再跟随 APK 的 minSdkVersion 自动调整：
 * <ul>
 *   <li>V1（JAR 签名）—— 按用户选择启用，兼容 minSdk &lt; 24 的老设备；</li>
 *   <li>V2 —— 按用户选择启用，Android 7.0+ 的设备优先使用它；</li>
 *   <li>V3 —— 只要启用了 V2 且 minSdk &ge; 28 就一并启用。</li>
 * </ul>
 */
public final class ApkSignerTool {

    /** Android 7.0 之前只认 V1 签名，所以这个版本是"必须有 V1"的分界线。 */
    public static final int API_LEVEL_N = 24;

    /** 校验结果。 */
    public static final class VerifyResult {
        private final boolean verified;
        private final boolean v1;
        private final boolean v2;
        private final boolean v3;
        private final int minCheckedPlatformVersion;
        private final List<String> errors;

        VerifyResult(boolean verified, boolean v1, boolean v2, boolean v3,
                     int minCheckedPlatformVersion, List<String> errors) {
            this.verified = verified;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.minCheckedPlatformVersion = minCheckedPlatformVersion;
            this.errors = Collections.unmodifiableList(errors);
        }

        public boolean isVerified() {
            return verified;
        }

        public boolean isVerifiedUsingV1() {
            return v1;
        }

        public boolean isVerifiedUsingV2() {
            return v2;
        }

        public boolean isVerifiedUsingV3() {
            return v3;
        }

        /**
         * 校验时使用的最低平台版本。
         *
         * <p>只签 V2 的产物从 Android 7.0 (API 24) 起才有效，所以这时它会大于 APK 自己的
         * minSdkVersion —— 否则 apksig 会一直报"缺少 V1 签名"而误判为失败。
         */
        public int getMinCheckedPlatformVersion() {
            return minCheckedPlatformVersion;
        }

        public List<String> getErrors() {
            return errors;
        }

        public String describeSchemes() {
            StringBuilder sb = new StringBuilder();
            if (v1) {
                sb.append("V1 ");
            }
            if (v2) {
                sb.append("V2 ");
            }
            if (v3) {
                sb.append("V3 ");
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? "(无)" : s;
        }
    }

    private ApkSignerTool() {
    }

    /** 把 {@code input} 重新签名输出到 {@code output}，使用默认的 V1 + V2 方案。 */
    public static void sign(File input, File output, SigningConfig signing, int minSdkVersion)
            throws IOException, GeneralSecurityException, ApkFormatException {
        sign(input, output, signing, minSdkVersion, PatchOptions.SigningScheme.V1_AND_V2);
    }

    /**
     * 把 {@code input} 按指定签名方案重新签名输出到 {@code output}。
     *
     * @param scheme 用户选的签名方案；{@code null} 时按 V1 + V2 处理
     */
    public static void sign(File input, File output, SigningConfig signing, int minSdkVersion,
                            PatchOptions.SigningScheme scheme)
            throws IOException, GeneralSecurityException, ApkFormatException {
        PatchOptions.SigningScheme effective =
                scheme == null ? PatchOptions.SigningScheme.V1_AND_V2 : scheme;

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                signing.getSignerName(),
                signing.getPrivateKey(),
                signing.getCertificateChain())
                .build();

        int minSdk = minSdkVersion <= 0 ? 21 : minSdkVersion;

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(effective.isV1Enabled())
                .setV2SigningEnabled(effective.isV2Enabled())
                // V3 属于 v2 那一族：只签 V1 时不能单独挂上 V3
                .setV3SigningEnabled(effective.isV2Enabled() && minSdk >= 28)
                .setOtherSignersSignaturesPreserved(false)
                .build();
        signer.sign();
    }

    /** 用 apksig 独立校验签名（按 V1 + V2 方案的最低平台要求）。 */
    public static VerifyResult verify(File apk, int minSdkVersion)
            throws IOException, GeneralSecurityException, ApkFormatException {
        return verify(apk, minSdkVersion, PatchOptions.SigningScheme.V1_AND_V2);
    }

    /**
     * 用 apksig 独立校验签名。
     *
     * <p>校验用的"最低平台版本"必须跟着签名方案走：只签 V2 的产物从 Android 7.0 (API 24)
     * 起才有效，如果还按 21 去校验，apksig 会因为"缺少 V1 签名"直接判失败。
     *
     * @param scheme 签名时使用的方案；{@code null} 时按 V1 + V2 处理
     */
    public static VerifyResult verify(File apk, int minSdkVersion, PatchOptions.SigningScheme scheme)
            throws IOException, GeneralSecurityException, ApkFormatException {
        int minSdk = minSdkVersion <= 0 ? 21 : minSdkVersion;
        PatchOptions.SigningScheme effective =
                scheme == null ? PatchOptions.SigningScheme.V1_AND_V2 : scheme;
        int minChecked = effective.isV1Enabled() ? minSdk : Math.max(minSdk, API_LEVEL_N);

        ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(minChecked)
                .build()
                .verify();

        java.util.ArrayList<String> errors = new java.util.ArrayList<String>();
        for (Object issue : result.getErrors()) {
            errors.add(String.valueOf(issue));
        }
        return new VerifyResult(result.isVerified(),
                result.isVerifiedUsingV1Scheme(),
                result.isVerifiedUsingV2Scheme(),
                result.isVerifiedUsingV3Scheme(),
                minChecked,
                errors);
    }
}
