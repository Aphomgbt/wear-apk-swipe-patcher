package com.wearswipe.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次补丁操作的结果快照。所有 {@code 0x...} 形式的 ID 都只是诊断信息。
 */
public final class PatchReport {

    private String packageName;
    private int versionCode;
    private String versionName;

    private String appPackageEntryName;
    private int appPackageId;

    private String launcherActivity;
    private String baseThemeDescription;
    private int baseThemeResourceId;

    private String patchStyleName;
    private int patchStyleResourceId;

    private int appliedCount;
    private final List<String> patchedTargets = new ArrayList<String>();
    private final List<String> warnings = new ArrayList<String>();

    public String getPackageName() {
        return packageName;
    }

    void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public int getVersionCode() {
        return versionCode;
    }

    void setVersionCode(int versionCode) {
        this.versionCode = versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getAppPackageEntryName() {
        return appPackageEntryName;
    }

    void setAppPackageEntryName(String appPackageEntryName) {
        this.appPackageEntryName = appPackageEntryName;
    }

    public int getAppPackageId() {
        return appPackageId;
    }

    void setAppPackageId(int appPackageId) {
        this.appPackageId = appPackageId;
    }

    public String getLauncherActivity() {
        return launcherActivity;
    }

    void setLauncherActivity(String launcherActivity) {
        this.launcherActivity = launcherActivity;
    }

    public String getBaseThemeDescription() {
        return baseThemeDescription;
    }

    void setBaseThemeDescription(String baseThemeDescription) {
        this.baseThemeDescription = baseThemeDescription;
    }

    public int getBaseThemeResourceId() {
        return baseThemeResourceId;
    }

    void setBaseThemeResourceId(int baseThemeResourceId) {
        this.baseThemeResourceId = baseThemeResourceId;
    }

    public String getPatchStyleName() {
        return patchStyleName;
    }

    void setPatchStyleName(String patchStyleName) {
        this.patchStyleName = patchStyleName;
    }

    public int getPatchStyleResourceId() {
        return patchStyleResourceId;
    }

    void setPatchStyleResourceId(int patchStyleResourceId) {
        this.patchStyleResourceId = patchStyleResourceId;
    }

    public int getAppliedCount() {
        return appliedCount;
    }

    void addAppliedTarget(String description) {
        patchedTargets.add(description);
        appliedCount = patchedTargets.size();
    }

    public List<String> getPatchedTargets() {
        return Collections.unmodifiableList(patchedTargets);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    void addWarning(String warning) {
        warnings.add(warning);
    }

    public boolean isPatchApplied() {
        return appliedCount > 0;
    }

    static String hex(int value) {
        return "0x" + Integer.toHexString(value);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("package        : ").append(packageName)
          .append(" (versionCode=").append(versionCode)
          .append(", versionName=").append(versionName).append(")\n");
        sb.append("资源包          : ").append(appPackageEntryName)
          .append(" id=").append(hex(appPackageId)).append('\n');
        sb.append("启动 Activity   : ").append(launcherActivity).append('\n');
        sb.append("继承的父主题    : ").append(baseThemeDescription)
          .append(" id=").append(hex(baseThemeResourceId)).append('\n');
        sb.append("补丁主题        : ").append(patchStyleName)
          .append(" id=").append(hex(patchStyleResourceId)).append('\n');
        sb.append("已应用位置      : ").append(appliedCount).append(" 处\n");
        for (String t : patchedTargets) {
            sb.append("  - ").append(t).append('\n');
        }
        if (!warnings.isEmpty()) {
            sb.append("警告            :\n");
            for (String w : warnings) {
                sb.append("  ! ").append(w).append('\n');
            }
        }
        return sb.toString();
    }
}
