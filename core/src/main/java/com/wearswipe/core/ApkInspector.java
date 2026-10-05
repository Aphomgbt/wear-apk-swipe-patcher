package com.wearswipe.core;

import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResConfig;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.arsc.value.style.StyleBag;
import com.reandroid.arsc.value.style.StyleBagItem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 只读分析一个 APK，回答三个问题：
 * <ol>
 *   <li>它的启动 Activity 用的是哪个主题？</li>
 *   <li>它是否已经打过本工具的补丁？</li>
 *   <li>如果不打补丁，能不能打（有没有 resources.arsc / 启动 Activity）？</li>
 * </ol>
 */
public final class ApkInspector {

    /** 分析结果。 */
    public static final class Inspection {
        private String packageName;
        private int versionCode;
        private String versionName;
        private int minSdkVersion;
        private int targetSdkVersion;

        private String launcherActivity;
        private int launcherThemeId;
        private String launcherThemeDescription;
        private int applicationThemeId;
        private String applicationThemeDescription;

        private boolean patchStylePresent;
        private String patchStyleName;
        private int patchStyleId;
        private int patchStyleParentId;
        private String patchStyleParentDescription;
        private boolean swipeToDismissDisabled;

        private boolean patchable;
        private String patchableReason;

        private final List<String> warnings = new ArrayList<String>();

        public String getPackageName() {
            return packageName;
        }

        public int getVersionCode() {
            return versionCode;
        }

        public String getVersionName() {
            return versionName;
        }

        public int getMinSdkVersion() {
            return minSdkVersion;
        }

        public int getTargetSdkVersion() {
            return targetSdkVersion;
        }

        public String getLauncherActivity() {
            return launcherActivity;
        }

        public int getLauncherThemeId() {
            return launcherThemeId;
        }

        public String getLauncherThemeDescription() {
            return launcherThemeDescription;
        }

        public int getApplicationThemeId() {
            return applicationThemeId;
        }

        public String getApplicationThemeDescription() {
            return applicationThemeDescription;
        }

        /** 已经存在同名补丁样式。 */
        public boolean isPatchStylePresent() {
            return patchStylePresent;
        }

        public String getPatchStyleName() {
            return patchStyleName;
        }

        public int getPatchStyleId() {
            return patchStyleId;
        }

        public int getPatchStyleParentId() {
            return patchStyleParentId;
        }

        public String getPatchStyleParentDescription() {
            return patchStyleParentDescription;
        }

        /** 补丁样式里确实写了 {@code android:windowSwipeToDismiss=false}。 */
        public boolean isSwipeToDismissDisabled() {
            return swipeToDismissDisabled;
        }

        /** 该 APK 可以被本工具处理。 */
        public boolean isPatchable() {
            return patchable;
        }

        public String getPatchableReason() {
            return patchableReason;
        }

        public List<String> getWarnings() {
            return Collections.unmodifiableList(warnings);
        }

        /** 启动 Activity 也已指向补丁样式，且样式里关掉了 swipe-to-dismiss。 */
        public boolean isFullyPatched() {
            return swipeToDismissDisabled && launcherThemeId != 0 && launcherThemeId == patchStyleId;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("包名          : ").append(packageName)
              .append(" (").append(versionName).append(" / ").append(versionCode).append(")\n");
            sb.append("minSdk        : ").append(minSdkVersion)
              .append("   targetSdk: ").append(targetSdkVersion).append('\n');
            sb.append("启动 Activity : ").append(launcherActivity).append('\n');
            sb.append("  主题        : ").append(hex(launcherThemeId));
            if (launcherThemeDescription != null) {
                sb.append(" -> ").append(launcherThemeDescription);
            }
            sb.append('\n');
            sb.append("application 主题: ").append(hex(applicationThemeId));
            if (applicationThemeDescription != null) {
                sb.append(" -> ").append(applicationThemeDescription);
            }
            sb.append('\n');
            sb.append("补丁样式      : ");
            if (patchStylePresent) {
                sb.append(patchStyleName).append(' ').append(hex(patchStyleId))
                  .append(" parent=").append(hex(patchStyleParentId));
                if (patchStyleParentDescription != null) {
                    sb.append(" -> ").append(patchStyleParentDescription);
                }
                sb.append('\n');
                sb.append("  windowSwipeToDismiss=false: ")
                  .append(swipeToDismissDisabled).append('\n');
            } else {
                sb.append("不存在\n");
            }
            sb.append("可处理        : ").append(patchable)
              .append(patchableReason == null ? "" : " (" + patchableReason + ")").append('\n');
            sb.append("已完整打补丁  : ").append(isFullyPatched()).append('\n');
            for (String w : warnings) {
                sb.append("  ! ").append(w).append('\n');
            }
            return sb.toString();
        }

        private static String hex(int value) {
            return "0x" + Integer.toHexString(value);
        }
    }

    private ApkInspector() {
    }

    public static Inspection inspect(File apkFile) throws IOException {
        return inspect(apkFile, PatchOptions.DEFAULT_STYLE_NAME);
    }

    public static Inspection inspect(File apkFile, String patchStyleName) throws IOException {
        Inspection info = new Inspection();
        info.patchStyleName = patchStyleName;

        ApkModule apk = ApkModule.loadApkFile(apkFile);
        try {
            TableBlock table = apk.getTableBlock();
            AndroidManifestBlock manifest = apk.getAndroidManifestBlock();
            if (manifest == null) {
                info.patchable = false;
                info.patchableReason = "没有 AndroidManifest.xml";
                return info;
            }

            info.packageName = manifest.getPackageName();
            Integer vc = manifest.getVersionCode();
            info.versionCode = vc == null ? 0 : vc.intValue();
            info.versionName = manifest.getVersionName();
            Integer minSdk = manifest.getMinSdkVersion();
            info.minSdkVersion = minSdk == null ? 0 : minSdk.intValue();
            Integer targetSdk = manifest.getTargetSdkVersion();
            info.targetSdkVersion = targetSdk == null ? 0 : targetSdk.intValue();

            ResXmlElement launcher = ManifestUtil.findLauncherActivity(manifest);
            info.launcherActivity = launcher == null ? "(未找到)"
                    : ManifestUtil.attrValue(launcher, "name");
            info.launcherThemeId = ManifestUtil.themeAttributeId(launcher);
            info.launcherThemeDescription =
                    ManifestUtil.describeResource(table, info.launcherThemeId);

            ResXmlElement application = manifest.getApplicationElement();
            info.applicationThemeId = ManifestUtil.themeAttributeId(application);
            info.applicationThemeDescription =
                    ManifestUtil.describeResource(table, info.applicationThemeId);

            if (table == null) {
                info.patchable = false;
                info.patchableReason = "没有 resources.arsc";
                return info;
            }

            PackageBlock appPackage = ManifestUtil.findAppPackage(table, info.packageName);
            if (appPackage == null) {
                info.patchable = false;
                info.patchableReason = "资源表里没有应用包";
                return info;
            }

            readPatchStyle(table, appPackage, info);

            if (launcher == null) {
                info.patchable = false;
                info.patchableReason = "清单里没有带 MAIN/LAUNCHER 的 Activity";
            } else {
                info.patchable = true;
            }
            return info;
        } finally {
            try {
                apk.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private static void readPatchStyle(TableBlock table, PackageBlock appPackage, Inspection info) {
        Entry entry;
        try {
            entry = appPackage.getEntry(ResConfig.getDefault(), "style", info.patchStyleName);
        } catch (Throwable t) {
            info.warnings.add("读取补丁样式失败: " + t);
            return;
        }
        if (entry == null || !entry.isDefined()) {
            return;
        }
        info.patchStylePresent = true;
        info.patchStyleId = entry.getResourceId();
        try {
            StyleBag bag = StyleBag.create(entry);
            if (bag == null) {
                return;
            }
            info.patchStyleParentId = bag.getParentId();
            info.patchStyleParentDescription =
                    ManifestUtil.describeResource(table, info.patchStyleParentId);
            StyleBagItem item = bag.get(ResourcePatcher.ATTR_ANDROID_WINDOW_SWIPE_TO_DISMISS);
            if (item != null && item.getValueType() == ValueType.BOOLEAN && item.getValue() == 0) {
                info.swipeToDismissDisabled = true;
            }
        } catch (Throwable t) {
            info.warnings.add("解析补丁样式失败: " + t);
        }
    }
}
