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

        private final List<String> activityThemes = new ArrayList<String>();

        private boolean patchable;
        private String patchableReason;

        /** 启动 Activity 最终生效的主题确实是本工具写的补丁样式（名字对得上，且内容关了 swipe）。 */
        private boolean launcherThemeIsPatchStyle;
        /** 有多少个 Activity 的生效主题关掉了 swipe-to-dismiss。 */
        private int activitiesWithSwipeDisabled;
        /** 清单里一共算了多少个 Activity。 */
        private int activityThemeCount;

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

        /**
         * 逐个列出清单里 Activity 的"生效主题"，格式 {@code .SecondActivity: style/OtherTheme (parent=...) [自身]}。
         * 用来确认补丁后每个 Activity 是否都继承到了自己原来的主题。
         */
        public List<String> getActivityThemes() {
            return Collections.unmodifiableList(activityThemes);
        }

        /** 清单里算了多少个 Activity。 */
        public int getActivityThemeCount() {
            return activityThemeCount;
        }

        /** 有多少个 Activity 的生效主题里 {@code android:windowSwipeToDismiss=false}。 */
        public int getActivitiesWithSwipeDisabled() {
            return activitiesWithSwipeDisabled;
        }

        /**
         * 启动 Activity 生效的主题是不是本工具的补丁样式。
         *
         * <p>按"内容 + 名字"判断：样式名是本工具的补丁样式名（含按 Activity 派生、
         * 以及为避开同名原样式而加后缀的变体），并且里面确实写了
         * {@code android:windowSwipeToDismiss=false}。
         * 这样即使因为撞名改了样式名，也能正确判断补丁是否已经生效。
         */
        public boolean isLauncherThemePatchStyle() {
            return launcherThemeIsPatchStyle;
        }

        /** 启动 Activity 也已指向补丁样式，且样式里关掉了 swipe-to-dismiss。 */
        public boolean isFullyPatched() {
            return launcherThemeIsPatchStyle;
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
            sb.append("已关闭侧滑    : ").append(activitiesWithSwipeDisabled)
              .append('/').append(activityThemeCount).append(" 个 Activity\n");
            sb.append("清单 Activity : ").append(activityThemes.size()).append(" 个\n");
            for (String line : activityThemes) {
                sb.append("  - ").append(line).append('\n');
            }
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
            readActivityThemes(manifest, table, appPackage, info);

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

    /**
     * 记录每个 Activity 的"生效主题"：自身 {@code android:theme} 优先，否则回落到 {@code <application>} 的主题。
     * 补丁后这里应当全部指向本工具生成的样式，且各自的 parent 仍是该 Activity 原来的主题。
     */
    private static void readActivityThemes(AndroidManifestBlock manifest, TableBlock table,
                                           PackageBlock appPackage, Inspection info) {
        for (ResXmlElement activity : manifest.listActivities()) {
            String name = ManifestUtil.attrValue(activity, "name");
            int ownThemeId = ManifestUtil.themeAttributeId(activity);
            int effectiveThemeId = ownThemeId != 0 ? ownThemeId : info.applicationThemeId;
            String source = ownThemeId != 0 ? "自身" : "继承 application";
            String styleName = ManifestUtil.styleNameOf(table, effectiveThemeId);
            boolean swipeDisabled = styleDisablesSwipe(table, appPackage, effectiveThemeId);

            info.activityThemeCount++;
            if (swipeDisabled) {
                info.activitiesWithSwipeDisabled++;
            }

            StringBuilder line = new StringBuilder();
            line.append(name == null ? "(未命名)" : name)
                .append(": ").append(describeThemeWithParent(table, appPackage, effectiveThemeId))
                .append(" [").append(source);
            if (swipeDisabled) {
                line.append(", swipe=off");
            }
            line.append(']');
            info.activityThemes.add(line.toString());

            boolean isLauncher = name != null && name.equals(info.launcherActivity);
            if (isLauncher && swipeDisabled && isPatchStyleName(info.patchStyleName, styleName)) {
                info.launcherThemeIsPatchStyle = true;
            }
        }
    }

    /**
     * 样式名是不是本工具会用的补丁样式名（相对基名 {@code base}）。
     *
     * <p>三种变体：基名 {@code WearNoSwipeUnityTheme}、按 Activity 派生的
     * {@code WearNoSwipeUnityTheme.SecondActivity}、为避开同名原样式而加后缀的
     * {@code WearNoSwipeUnityTheme_WearSwipe}（见 {@link ResourcePatcher}）。
     */
    private static boolean isPatchStyleName(String base, String styleName) {
        if (styleName == null) {
            return false;
        }
        String prefix = base == null ? PatchOptions.DEFAULT_STYLE_NAME : base;
        return styleName.equals(prefix)
                || styleName.startsWith(prefix + ".")
                || styleName.startsWith(prefix + "_WearSwipe");
    }

    /** 某个主题样式里是否写了 {@code android:windowSwipeToDismiss=false}。 */
    private static boolean styleDisablesSwipe(TableBlock table, PackageBlock appPackage, int themeId) {
        if (themeId == 0 || appPackage == null) {
            return false;
        }
        String styleName = ManifestUtil.styleNameOf(table, themeId);
        if (styleName == null) {
            return false;
        }
        try {
            Entry entry = appPackage.getEntry(ResConfig.getDefault(), "style", styleName);
            if (entry == null || !entry.isDefined()) {
                return false;
            }
            StyleBag bag = StyleBag.create(entry);
            if (bag == null) {
                return false;
            }
            StyleBagItem item = bag.get(ResourcePatcher.ATTR_ANDROID_WINDOW_SWIPE_TO_DISMISS);
            return item != null && item.getValueType() == ValueType.BOOLEAN && item.getValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code style/AppTheme (parent=style/Theme)}；解析不到 parent 时只给样式名。 */
    private static String describeThemeWithParent(TableBlock table, PackageBlock appPackage, int themeId) {
        String description = ManifestUtil.describeResource(table, themeId);
        if (description == null) {
            return "(无主题)";
        }
        String parent = parentStyleDescription(table, appPackage, themeId);
        return parent == null ? description : description + " (parent=" + parent + ")";
    }

    private static String parentStyleDescription(TableBlock table, PackageBlock appPackage, int themeId) {
        if (appPackage == null) {
            return null;
        }
        String styleName = ManifestUtil.styleNameOf(table, themeId);
        if (styleName == null) {
            return null;
        }
        try {
            Entry entry = appPackage.getEntry(ResConfig.getDefault(), "style", styleName);
            if (entry == null || !entry.isDefined()) {
                return null;
            }
            StyleBag bag = StyleBag.create(entry);
            if (bag == null || bag.getParentId() == 0) {
                return null;
            }
            String parent = ManifestUtil.describeResource(table, bag.getParentId());
            return parent == null ? ("0x" + Integer.toHexString(bag.getParentId())) : parent;
        } catch (Throwable t) {
            return null;
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
