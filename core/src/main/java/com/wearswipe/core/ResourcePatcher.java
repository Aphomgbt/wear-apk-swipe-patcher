package com.wearswipe.core;

import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResConfig;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.arsc.value.style.StyleBag;
import com.reandroid.arsc.value.style.StyleBagItem;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/**
 * 纯 Java 资源层补丁：不触碰 dex / so / 资源文件，只改
 * {@code AndroidManifest.xml}（二进制 XML）与 {@code resources.arsc}。
 *
 * <p>做的事情等价于手工：
 * <pre>
 *   &lt;style name="WearNoSwipeUnityTheme" parent="@style/&lt;原主题&gt;"&gt;
 *       &lt;item name="android:windowSwipeToDismiss"&gt;false&lt;/item&gt;
 *   &lt;/style&gt;
 *   &lt;activity android:name="..." android:theme="@style/WearNoSwipeUnityTheme"&gt;
 * </pre>
 */
public final class ResourcePatcher {

    /** {@code android:theme} 的框架属性资源 ID。 */
    public static final int ATTR_ANDROID_THEME = 0x01010000;

    /**
     * {@code android:windowSwipeToDismiss} 的框架属性资源 ID。
     * 已用 ARSCLib 从被处理 APK 捆绑的 android 框架资源包解析确认，
     * 与其邻居 {@code banner}=0x010103f2、{@code isGame}=0x010103f4 一致。
     */
    public static final int ATTR_ANDROID_WINDOW_SWIPE_TO_DISMISS = 0x010103f3;

    /** 框架默认主题 {@code @android:style/Theme}，即"清单未声明主题"时框架实际使用的主题。 */
    public static final int ANDROID_STYLE_THEME = 0x01030000;

    private static final int FRAMEWORK_PACKAGE_ID = 0x01;

    private final ApkModule apk;
    private final PatchOptions options;
    private final PatchReport report;
    private final Progress progress;

    private TableBlock table;
    private AndroidManifestBlock manifest;
    private PackageBlock appPackage;

    public ResourcePatcher(ApkModule apk, PatchOptions options, PatchReport report, Progress progress) {
        this.apk = apk;
        this.options = options;
        this.report = report;
        this.progress = progress;
    }

    /** 执行资源层补丁，就地修改传入的 {@link ApkModule}。 */
    public void apply() throws IOException {
        step(5, "读取资源表与清单");
        table = apk.getTableBlock();
        if (table == null) {
            throw new IOException("该 APK 没有 resources.arsc，无法在资源层添加主题");
        }
        manifest = apk.getAndroidManifestBlock();
        if (manifest == null) {
            throw new IOException("该 APK 没有 AndroidManifest.xml");
        }

        report.setPackageName(manifest.getPackageName());
        Integer vc = manifest.getVersionCode();
        report.setVersionCode(vc == null ? 0 : vc.intValue());
        report.setVersionName(manifest.getVersionName());

        appPackage = findAppPackage();
        report.setAppPackageEntryName(appPackage.getName());
        report.setAppPackageId(appPackage.getId());

        step(15, "定位启动 Activity");
        ResXmlElement launcher = findLauncherActivity();
        report.setLauncherActivity(launcherActivityName(launcher));

        step(30, "解析需要继承的父主题");
        int parentStyleId = resolveBaseThemeId(launcher);

        step(50, "创建补丁主题 " + options.getStyleName());
        int patchStyleId = createOrUpdatePatchStyle(parentStyleId);

        step(65, "把补丁主题写入清单");
        int applied = applyThemeToManifest(launcher, patchStyleId);
        if (applied == 0) {
            throw new IOException("未能在清单中找到可应用主题的 Activity");
        }
        report.setPatchStyleName(options.getStyleName());
        report.setPatchStyleResourceId(patchStyleId);

        step(75, "资源层补丁完成");
    }

    // ------------------------------------------------------------------
    // 资源包定位
    // ------------------------------------------------------------------

    private PackageBlock findAppPackage() throws IOException {
        PackageBlock result = ManifestUtil.findAppPackage(table, manifest.getPackageName());
        if (result == null) {
            throw new IOException("resources.arsc 中找不到应用自身的资源包（只有框架包？）");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 清单操作
    // ------------------------------------------------------------------

    private ResXmlElement findLauncherActivity() throws IOException {
        ResXmlElement launcher = ManifestUtil.findLauncherActivity(manifest);
        if (launcher == null) {
            throw new IOException("清单里找不到带 MAIN/LAUNCHER 的启动 Activity");
        }
        return launcher;
    }

    static String attrValue(ResXmlElement element, String name) {
        return ManifestUtil.attrValue(element, name);
    }

    static ResXmlAttribute findAttribute(ResXmlElement element, int nameId) {
        return ManifestUtil.findAttribute(element, nameId);
    }

    private static String launcherActivityName(ResXmlElement launcher) {
        String name = attrValue(launcher, "name");
        return name == null ? "(未命名)" : name;
    }

    private void step(int percent, String message) {
        if (progress != null) {
            progress.onProgress(percent, message);
        }
    }

    // ------------------------------------------------------------------
    // 父主题解析
    // ------------------------------------------------------------------

    /**
     * 决定补丁主题要继承哪个主题。
     *
     * <p>优先级：
     * <ol>
     *   <li>用户在 {@link PatchOptions#setParentStyleName} 中强制指定的主题；</li>
     *   <li>启动 Activity 上已有的 {@code android:theme}；</li>
     *   <li>{@code <application>} 上的 {@code android:theme}；</li>
     *   <li>应用内可识别的常见基础主题（Unity 的 {@code UnityThemeSelector} 等）；</li>
     *   <li>框架默认 {@code @android:style/Theme}（"清单未声明主题"时框架真正的取值）。</li>
     * </ol>
     */
    private int resolveBaseThemeId(ResXmlElement launcher) {
        String forced = options.getParentStyleName();
        if (forced != null) {
            int id = resolveStyleSpec(forced);
            if (id == 0) {
                report.addWarning("指定的父主题 " + forced + " 在 APK 中找不到，已退回自动探测");
            } else {
                report.setBaseThemeDescription(forced);
                report.setBaseThemeResourceId(id);
                return id;
            }
        }

        if (options.isAutoDetectParent()) {
            int id = themeAttributeId(launcher);
            if (id != 0) {
                setBaseTheme(id, "启动 Activity 声明的主题");
                return id;
            }
            ResXmlElement application = manifest.getApplicationElement();
            if (application != null) {
                id = themeAttributeId(application);
                if (id != 0) {
                    setBaseTheme(id, "application 声明的主题");
                    return id;
                }
            }
            for (String candidate : PatchOptions.autoParentCandidates()) {
                ResourceEntry entry = appPackage.getResource("style", candidate);
                if (entry != null && entry.getResourceId() != 0) {
                    setBaseTheme(entry.getResourceId(), "@style/" + candidate);
                    return entry.getResourceId();
                }
            }
        }

        if (table.getResource(ANDROID_STYLE_THEME) != null
                || resolveAndroidStyle("Theme") != 0) {
            setBaseTheme(ANDROID_STYLE_THEME, "@android:style/Theme");
            return ANDROID_STYLE_THEME;
        }
        report.setBaseThemeDescription("(无父主题)");
        report.setBaseThemeResourceId(0);
        report.addWarning("找不到任何可继承的主题，补丁主题将不带 parent");
        return 0;
    }

    private void setBaseTheme(int id, String how) {
        String decoded = decodeResource(id);
        report.setBaseThemeResourceId(id);
        report.setBaseThemeDescription(decoded == null ? how : (how + " -> " + decoded));
    }

    private String decodeResource(int id) {
        return ManifestUtil.describeResource(table, id);
    }

    /** 读取元素上已有的 {@code android:theme}，返回其引用到的资源 ID；没有则返回 0。 */
    private int themeAttributeId(ResXmlElement element) {
        return ManifestUtil.themeAttributeId(element);
    }

    /**
     * 把 {@code "UnityThemeSelector"} / {@code "@style/X"} / {@code "@android:style/X"}
     * 解析成资源 ID；解析不到返回 0。
     */
    private int resolveStyleSpec(String spec) {
        String name = spec.trim();
        String packageName = null;
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        int slash = name.indexOf('/');
        if (slash >= 0) {
            String prefix = name.substring(0, slash);
            name = name.substring(slash + 1);
            int colon = prefix.indexOf(':');
            if (colon >= 0) {
                packageName = prefix.substring(0, colon);
            }
            // 忽略 "style" 这一级（用户可能写 @style/X）
            if ("style".equals(prefix) || (colon >= 0 && "style".equals(prefix.substring(colon + 1)))) {
                packageName = colon >= 0 ? prefix.substring(0, colon) : null;
            }
        }
        if ("android".equals(packageName)) {
            return resolveAndroidStyle(name);
        }
        ResourceEntry entry = appPackage.getResource("style", name);
        return entry == null ? 0 : entry.getResourceId();
    }

    /**
     * 通过 {@code StyleBag.resolve} 解析框架样式。被处理 APK 里通常并不内嵌 android 资源包，
     * ARSCLib 会用自带的 framework APK 完成解析，因此不能靠遍历 {@code TableBlock}。
     */
    private int resolveAndroidStyle(String name) {
        try {
            return StyleBag.resolve(table, "android:style/" + name);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 补丁主题的创建 / 更新
    // ------------------------------------------------------------------

    /**
     * 在应用资源包里创建（已存在则原地更新）补丁样式：
     * <pre>
     *   &lt;style name="..." parent="&lt;parentStyleId&gt;"&gt;
     *       &lt;item name="android:windowSwipeToDismiss"&gt;false&lt;/item&gt;
     *   &lt;/style&gt;
     * </pre>
     *
     * @return 补丁样式最终拿到的资源 ID
     */
    private int createOrUpdatePatchStyle(int parentStyleId) throws IOException {
        final String styleName = options.getStyleName();
        Entry styleEntry = appPackage.getOrCreate(ResConfig.getDefault(), "style", styleName);
        if (styleEntry == null) {
            throw new IOException("无法在资源表中创建 style/" + styleName);
        }
        styleEntry.ensureComplex(true);

        StyleBag bag;
        try {
            bag = StyleBag.create(styleEntry);
        } catch (Throwable t) {
            throw new IOException("创建 style/" + styleName + " 的样式袋失败: " + t, t);
        }
        if (bag == null) {
            throw new IOException("style/" + styleName + " 无法作为样式袋处理");
        }

        bag.clear();
        if (parentStyleId != 0) {
            bag.setParentId(parentStyleId);
        }
        bag.put(ATTR_ANDROID_WINDOW_SWIPE_TO_DISMISS,
                StyleBagItem.create(ValueType.BOOLEAN, 0));

        int id = styleEntry.getResourceId();
        if (id == 0) {
            throw new IOException("style/" + styleName + " 没有获得资源 ID，资源表可能已损坏");
        }
        verifyPatchStyle(styleEntry, styleName);
        return id;
    }

    /** 读回刚写入的内容自检，避免"写了个空样式"这种静默失败。 */
    private void verifyPatchStyle(Entry styleEntry, String styleName) throws IOException {
        StyleBag readBack = StyleBag.create(styleEntry);
        if (readBack == null) {
            throw new IOException("补丁样式 " + styleName + " 回读失败");
        }
        StyleBagItem item = readBack.get(ATTR_ANDROID_WINDOW_SWIPE_TO_DISMISS);
        if (item == null) {
            throw new IOException("补丁样式 " + styleName
                    + " 里没有 android:windowSwipeToDismiss，写入未生效");
        }
        if (item.getValueType() != ValueType.BOOLEAN || item.getValue() != 0) {
            throw new IOException("补丁样式 " + styleName + " 的 android:windowSwipeToDismiss 值异常: "
                    + item.getValueType() + "=" + item.getValue());
        }
        Integer hasParent = Integer.valueOf(readBack.getParentId());
        if (options.isAutoDetectParent() && hasParent.intValue() == 0
                && report.getBaseThemeResourceId() != 0) {
            throw new IOException("补丁样式 " + styleName + " 的 parent 写入失败");
        }
    }

    // ------------------------------------------------------------------
    // 把主题写进清单
    // ------------------------------------------------------------------

    private int applyThemeToManifest(ResXmlElement launcher, int styleId) {
        int applied = 0;
        applied += setElementTheme(launcher, styleId,
                "启动 Activity " + launcherActivityName(launcher));

        if (options.isPatchAllActivities()) {
            for (ResXmlElement activity : manifest.listActivities()) {
                if (activity == launcher) {
                    continue;
                }
                applied += setElementTheme(activity, styleId,
                        "Activity " + attrValue(activity, "name"));
            }
        }
        if (options.isPatchApplicationElement()) {
            ResXmlElement application = manifest.getApplicationElement();
            if (application != null) {
                applied += setElementTheme(application, styleId, "<application>");
            }
        }
        return applied;
    }

    /** 给单个元素设置 {@code android:theme="@style/<补丁样式>"}。 */
    private int setElementTheme(ResXmlElement element, int styleId, String what) {
        if (element == null) {
            return 0;
        }
        ResXmlAttribute attr;
        try {
            attr = element.getOrCreateAndroidAttribute("theme", ATTR_ANDROID_THEME);
        } catch (Throwable t) {
            report.addWarning(what + " 无法创建 android:theme 属性: " + t);
            return 0;
        }
        if (attr == null) {
            report.addWarning(what + " 找不到 android 命名空间，跳过");
            return 0;
        }
        attr.setValueType(ValueType.REFERENCE);
        attr.setData(styleId);
        report.addAppliedTarget(what + " -> @style/" + options.getStyleName());
        return 1;
    }
}
