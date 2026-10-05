package com.wearswipe.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 补丁参数。全部为可选项，默认值面向"最保守、不破坏原应用"的通用场景。
 */
public final class PatchOptions {

    /** 默认新建的补丁主题名。 */
    public static final String DEFAULT_STYLE_NAME = "WearNoSwipeUnityTheme";

    /** 平台兜底主题：{@code @android:style/Theme}（即"未指定主题"时框架使用的值）。 */
    public static final String PLATFORM_FALLBACK_THEME = "@android:style/Theme";

    /**
     * 输出 APK 使用的签名方案。
     *
     * <p>两个方案在文件层面的差别：
     * <ul>
     *   <li><b>V1</b>（JAR 签名）在 {@code META-INF/} 下新增 {@code *.SF} / {@code *.RSA} 条目；</li>
     *   <li><b>V2</b>（APK Signature Scheme v2）在中央目录之前写入独立的 APK Signing Block，
     *       不新增任何 zip 条目。</li>
     * </ul>
     *
     * <p>默认 {@link #V1_AND_V2}，即"修改右滑返回"前后行为完全不变。用户可以在开始打补丁之前
     * 换成只签一种，用于排查某些 ROM 对特定签名方案校验异常的情况。
     */
    public enum SigningScheme {
        /** 同时进行 V1 与 V2 签名。兼容面最广，是默认值。 */
        V1_AND_V2("V1 + V2"),
        /** 只进行 V2 签名。不新增 META-INF 条目，但只能装到 Android 7.0 (API 24) 及以上。 */
        V2_ONLY("仅 V2"),
        /** 只进行 V1 签名。不写 v2 签名块，用于对 v2 校验行为异常的旧 ROM。 */
        V1_ONLY("仅 V1");

        private final String label;

        SigningScheme(String label) {
            this.label = label;
        }

        /** 给界面显示用的短标签。 */
        public String getLabel() {
            return label;
        }

        /** 是否进行 V1（JAR）签名。 */
        public boolean isV1Enabled() {
            return this != V2_ONLY;
        }

        /** 是否进行 V2 签名。 */
        public boolean isV2Enabled() {
            return this != V1_ONLY;
        }

        /**
         * 宽松解析。
         *
         * <p>接受 {@code v1} / {@code v1only} / {@code v2} / {@code v2only} / {@code v1+v2} /
         * {@code both}（忽略大小写与空格），也接受枚举名本身。
         *
         * @return 无法识别时返回 {@code null}，由调用方决定退回默认值还是报错
         */
        public static SigningScheme parse(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            String normalized = trimmed.toLowerCase(java.util.Locale.US).replace(" ", "");
            if ("v1".equals(normalized) || "v1only".equals(normalized) || "v1_only".equals(normalized)) {
                return V1_ONLY;
            }
            if ("v2".equals(normalized) || "v2only".equals(normalized) || "v2_only".equals(normalized)) {
                return V2_ONLY;
            }
            if ("v1+v2".equals(normalized) || "v1v2".equals(normalized)
                    || "v1_and_v2".equals(normalized) || "both".equals(normalized)) {
                return V1_AND_V2;
            }
            for (SigningScheme scheme : values()) {
                if (scheme.name().equalsIgnoreCase(trimmed)) {
                    return scheme;
                }
            }
            return null;
        }
    }

    /**
     * 当启动 Activity 与 application 都没有显式 {@code android:theme} 时，按顺序尝试这些
     * 应用内主题名作为父主题。仅当同名 style 确实存在于被处理 APK 中才会命中。
     */
    private static final List<String> AUTO_PARENT_CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            "UnityThemeSelector",   // Unity 导出工程的标准主题
            "AppTheme",
            "AppBaseTheme",
            "BaseAppTheme",
            "Theme.AppCompat.Light.NoActionBar",
            "Theme.AppCompat.NoActionBar",
            "Theme.DeviceDefault.NoActionBar"
    ));

    private String styleName = DEFAULT_STYLE_NAME;
    private String parentStyleName = null;
    private boolean autoDetectParent = true;
    private boolean patchApplicationElement = false;
    private boolean patchAllActivities = false;
    private boolean alignOutput = true;
    private boolean verifySignature = true;
    private SigningScheme signingScheme = SigningScheme.V1_AND_V2;

    /** 新建主题的名字，例如 {@code WearSwipePatchTheme}。 */
    public String getStyleName() {
        return styleName;
    }

    public PatchOptions setStyleName(String styleName) {
        if (styleName == null || styleName.trim().isEmpty()) {
            throw new IllegalArgumentException("styleName 不能为空");
        }
        this.styleName = styleName.trim();
        return this;
    }

    /**
     * 强制指定父主题（可写 {@code UnityThemeSelector} 或 {@code @android:style/Theme}）。
     * 为 {@code null} 且 {@link #isAutoDetectParent()} 为真时自动探测。
     */
    public String getParentStyleName() {
        return parentStyleName;
    }

    public PatchOptions setParentStyleName(String parentStyleName) {
        this.parentStyleName = (parentStyleName == null || parentStyleName.trim().isEmpty())
                ? null : parentStyleName.trim();
        return this;
    }

    public boolean isAutoDetectParent() {
        return autoDetectParent;
    }

    public PatchOptions setAutoDetectParent(boolean autoDetectParent) {
        this.autoDetectParent = autoDetectParent;
        return this;
    }

    /** 是否同时给 {@code <application>} 打上补丁主题（未显式设置主题时才有意义）。 */
    public boolean isPatchApplicationElement() {
        return patchApplicationElement;
    }

    public PatchOptions setPatchApplicationElement(boolean value) {
        this.patchApplicationElement = value;
        return this;
    }

    /** 是否给清单里所有 Activity 都打上补丁主题；默认只改启动 Activity。 */
    public boolean isPatchAllActivities() {
        return patchAllActivities;
    }

    public PatchOptions setPatchAllActivities(boolean value) {
        this.patchAllActivities = value;
        return this;
    }

    /** 输出 APK 是否执行 4 字节对齐（zipalign）。 */
    public boolean isAlignOutput() {
        return alignOutput;
    }

    public PatchOptions setAlignOutput(boolean alignOutput) {
        this.alignOutput = alignOutput;
        return this;
    }

    /** 签名后是否用 apksig 重新校验。 */
    public boolean isVerifySignature() {
        return verifySignature;
    }

    public PatchOptions setVerifySignature(boolean verifySignature) {
        this.verifySignature = verifySignature;
        return this;
    }

    /**
     * 输出 APK 的签名方案。默认 {@link SigningScheme#V1_AND_V2}，即同时进行 V1 与 V2 签名，
     * 与"是否进行 V2 签名"这个开关等价：想跳过 V2 就设为 {@link SigningScheme#V1_ONLY}。
     */
    public SigningScheme getSigningScheme() {
        return signingScheme;
    }

    public PatchOptions setSigningScheme(SigningScheme signingScheme) {
        if (signingScheme == null) {
            throw new IllegalArgumentException("signingScheme 不能为 null");
        }
        this.signingScheme = signingScheme;
        return this;
    }

    static List<String> autoParentCandidates() {
        return new ArrayList<String>(AUTO_PARENT_CANDIDATES);
    }

    @Override
    public String toString() {
        return "PatchOptions{styleName='" + styleName + '\''
                + ", parentStyleName=" + parentStyleName
                + ", autoDetectParent=" + autoDetectParent
                + ", patchApplicationElement=" + patchApplicationElement
                + ", patchAllActivities=" + patchAllActivities
                + ", alignOutput=" + alignOutput
                + ", verifySignature=" + verifySignature
                + ", signingScheme=" + signingScheme + '}';
    }
}
