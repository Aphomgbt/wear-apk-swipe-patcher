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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

    /**
     * 给"其余 Activity"生成补丁样式时用的名字分隔符：
     * {@code WearNoSwipeUnityTheme.SecondActivity}。
     */
    private static final String ACTIVITY_STYLE_SEPARATOR = ".";

    /** 给 {@code <application>} 单独生成补丁样式时的名字后缀。 */
    private static final String APPLICATION_STYLE_SUFFIX = ".App";

    /**
     * 原 APK 自己就有同名样式时，改用这个后缀避让（绝不覆盖别人的样式）：
     * {@code WearNoSwipeUnityTheme_WearSwipe}。
     */
    private static final String FALLBACK_STYLE_SUFFIX = "_WearSwipe";

    /** 避让同名样式的最大尝试次数；正常 APK 不会连续撞名这么多次。 */
    private static final int MAX_STYLE_NAME_ATTEMPTS = 50;

    private static final int FRAMEWORK_PACKAGE_ID = 0x01;

    private final ApkModule apk;
    private final PatchOptions options;
    private final PatchReport report;
    private final Progress progress;

    /** 本次运行里已经分配出去的补丁样式名 -> 它继承的父主题 ID。 */
    private final Map<String, Integer> allocatedParents = new HashMap<String, Integer>();

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
        ResXmlElement application = manifest.getApplicationElement();
        int fallbackBaseThemeId = resolveBaseThemeId(launcher);
        // <application> 自己原本的主题：其它 Activity 没声明主题时真正生效的就是它
        int applicationThemeId = unwrapPatchTheme(themeAttributeId(application));

        step(50, "创建补丁主题 " + options.getStyleName());
        PatchTheme launcherTheme = createPatchTheme(options.getStyleName(),
                fallbackBaseThemeId, "启动 Activity");

        step(65, "把补丁主题写入清单");
        int applied = applyThemeToManifest(launcher, application, launcherTheme,
                applicationThemeId, fallbackBaseThemeId);
        if (applied == 0) {
            throw new IOException("未能在清单中找到可应用主题的 Activity");
        }
        report.setPatchStyleName(launcherTheme.name);
        report.setPatchStyleResourceId(launcherTheme.id);

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
            // 二次打补丁时启动 Activity 上那个主题就是本工具的补丁样式，要取它继承的原主题
            int id = unwrapPatchTheme(themeAttributeId(launcher));
            if (id != 0) {
                setBaseTheme(id, "启动 Activity 声明的主题");
                return id;
            }
            ResXmlElement application = manifest.getApplicationElement();
            if (application != null) {
                id = unwrapPatchTheme(themeAttributeId(application));
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

    /** 一次补丁过程里创建（或复用）的补丁主题。 */
    private static final class PatchTheme {
        final String name;
        final int id;
        final int parentId;

        PatchTheme(String name, int id, int parentId) {
            this.name = name;
            this.id = id;
            this.parentId = parentId;
        }
    }

    /**
     * 建一个补丁主题。每个被改写的元素都用<b>属于自己</b>的补丁样式，
     * 而且继承的是它自己原本会用的主题 —— 这样给多个 Activity 打补丁时，
     * 不会把启动 Activity 的主题（外观、窗口行为）一并套给别的页面。
     *
     * @param baseName      想要的样式名（撞名时会被自动改名）
     * @param parentStyleId 该元素原本会用的主题 ID；0 表示找不到
     * @param owner         是谁在用这个主题，只用于报告
     */
    private PatchTheme createPatchTheme(String baseName, int parentStyleId, String owner)
            throws IOException {
        String styleName = allocateStyleName(baseName, parentStyleId, owner);
        if (parentStyleId == 0) {
            report.addWarning(owner + " 找不到可继承的原主题，补丁样式 " + styleName
                    + " 将不带 parent，外观可能与原版不同");
        }
        int id = createOrUpdatePatchStyle(styleName, parentStyleId);
        allocatedParents.put(styleName, Integer.valueOf(parentStyleId));
        return new PatchTheme(styleName, id, parentStyleId);
    }

    /**
     * 挑一个安全的补丁样式名。
     *
     * <p>名字空闲、或者已经有同名样式且它确实是本工具以前写下的补丁样式（可以就地更新）时直接用；
     * 如果<b>原 APK 自己</b>就定义了同名样式，为了不破坏它原有的配置，改用带后缀的名字。
     */
    private String allocateStyleName(String baseName, int parentStyleId, String owner)
            throws IOException {
        String styleName = baseName;
        for (int attempt = 1; attempt <= MAX_STYLE_NAME_ATTEMPTS; attempt++) {
            Entry existing = getDefaultStyleEntry(styleName);
            if (existing == null || !existing.isDefined()
                    || isReusablePatchStyle(existing, parentStyleId)) {
                return styleName;
            }
            report.addWarning("原 APK 里已经有同名样式 style/" + styleName
                    + "，它不是本工具生成的补丁样式；为免清掉它原有的配置，" + owner
                    + " 改用另一个名字。");
            styleName = baseName + FALLBACK_STYLE_SUFFIX
                    + (attempt == 1 ? "" : String.valueOf(attempt));
        }
        throw new IOException("找不到可用的补丁样式名（基名 " + baseName + " 连续撞名 "
                + MAX_STYLE_NAME_ATTEMPTS + " 次）");
    }

    /**
     * 已存在的同名样式能不能直接拿来当补丁样式用？
     *
     * <p>只有本工具以前写下的补丁样式才可以（特征是 {@code android:windowSwipeToDismiss}
     * 已经是 false）。别的样式一律不碰：旧实现会先 {@code bag.clear()} 再写，
     * 那会把原样式里的其它配置一起清掉。
     */
    private boolean isReusablePatchStyle(Entry existing, int parentStyleId) {
        if (!isOwnPatchStyle(existing)) {
            return false;
        }
        Integer previous = allocatedParents.get(existing.getName());
        return previous == null || previous.intValue() == parentStyleId;
    }

    /** 这个条目是不是本工具生成的补丁样式（只看内容，不看名字）。 */
    private boolean isOwnPatchStyle(Entry entry) {
        if (entry == null || !entry.isDefined()) {
            return false;
        }
        try {
            if (!StyleBag.isStyle(entry)) {
                return false;
            }
            StyleBag bag = StyleBag.create(entry);
            return bag != null && isSwipeToDismissDisabled(bag);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSwipeToDismissDisabled(StyleBag bag) {
        StyleBagItem item = bag.get(ATTR_ANDROID_WINDOW_SWIPE_TO_DISMISS);
        return item != null
                && item.getValueType() == ValueType.BOOLEAN
                && item.getValue() == 0;
    }

    /** 读取应用资源包里某个样式的默认配置条目；不存在返回 {@code null}。 */
    private Entry getDefaultStyleEntry(String styleName) {
        try {
            return appPackage.getEntry(ResConfig.getDefault(), "style", styleName);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 写入（或就地更新）补丁样式本体，等价于：
     * <pre>
     *   &lt;style name="..." parent="&lt;parentStyleId&gt;"&gt;
     *       &lt;item name="android:windowSwipeToDismiss"&gt;false&lt;/item&gt;
     *   &lt;/style&gt;
     * </pre>
     *
     * <p>只有<b>新建</b>的条目才 {@code clear()}；已经存在的补丁样式只更新 parent 与
     * {@code android:windowSwipeToDismiss} 两条，其它内容原样保留。
     *
     * @return 补丁样式最终拿到的资源 ID
     */
    private int createOrUpdatePatchStyle(String styleName, int parentStyleId) throws IOException {
        Entry styleEntry = getDefaultStyleEntry(styleName);
        boolean existed = styleEntry != null && styleEntry.isDefined();
        if (existed && !isOwnPatchStyle(styleEntry)) {
            // allocateStyleName 已经避开这种名字，这里只是兜底：绝不清空别人的样式
            throw new IOException("style/" + styleName + " 已存在且不是本工具生成的补丁样式，拒绝覆盖");
        }
        if (!existed) {
            styleEntry = appPackage.getOrCreate(ResConfig.getDefault(), "style", styleName);
            if (styleEntry == null) {
                throw new IOException("无法在资源表中创建 style/" + styleName);
            }
            styleEntry.ensureComplex(true);
        }

        StyleBag bag;
        try {
            bag = StyleBag.create(styleEntry);
        } catch (Throwable t) {
            throw new IOException("创建 style/" + styleName + " 的样式袋失败: " + t, t);
        }
        if (bag == null) {
            throw new IOException("style/" + styleName + " 无法作为样式袋处理");
        }

        if (!existed) {
            bag.clear();
        }
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

    /**
     * 如果 {@code resourceId} 指向本工具以前写下的补丁样式，返回它继承的那个原主题；否则原样返回。
     *
     * <p>二次打补丁时启动 Activity 上已经挂着补丁样式了，必须先把这层"剥掉"，
     * 否则补丁样式会把自己当成 parent。
     */
    private int unwrapPatchTheme(int resourceId) {
        if (resourceId == 0) {
            return 0;
        }
        String styleName = styleNameOf(resourceId);
        if (styleName == null || !isPatchStyleName(styleName)) {
            return resourceId;
        }
        Entry entry = getDefaultStyleEntry(styleName);
        if (!isOwnPatchStyle(entry)) {
            return resourceId; // 同名但不是我们写的，保持原样
        }
        try {
            StyleBag bag = StyleBag.create(entry);
            return bag == null ? resourceId : bag.getParentId();
        } catch (Throwable t) {
            return resourceId;
        }
    }

    /** {@code 0x7f030005} -> {@code "WearNoSwipeUnityTheme"}；不是样式则返回 {@code null}。 */
    private String styleNameOf(int resourceId) {
        return ManifestUtil.styleNameOf(table, resourceId);
    }

    /** 名字是不是本工具会用的补丁样式名（含按 Activity 派生的与避让撞名产生的变体）。 */
    private boolean isPatchStyleName(String name) {
        String base = options.getStyleName();
        return name.equals(base)
                || name.startsWith(base + ACTIVITY_STYLE_SEPARATOR)
                || name.startsWith(base + FALLBACK_STYLE_SUFFIX);
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

    private int applyThemeToManifest(ResXmlElement launcher, ResXmlElement application,
                                     PatchTheme launcherTheme, int applicationThemeId,
                                     int fallbackBaseThemeId) throws IOException {
        int applied = 0;
        applied += setElementTheme(launcher, launcherTheme,
                "启动 Activity " + launcherActivityName(launcher));

        if (options.isPatchAllActivities()) {
            applied += applyThemeToOtherActivities(launcher, launcherTheme,
                    applicationThemeId, fallbackBaseThemeId);
        }
        if (options.isPatchApplicationElement() && application != null) {
            int base = applicationThemeId != 0 ? applicationThemeId : fallbackBaseThemeId;
            PatchTheme theme = base == launcherTheme.parentId
                    ? launcherTheme
                    : createPatchTheme(launcherTheme.name + APPLICATION_STYLE_SUFFIX,
                            base, "<application>");
            applied += setElementTheme(application, theme, "<application>");
        }
        return applied;
    }

    /**
     * 给其余 Activity 各自建一个继承它自己原主题的补丁主题。
     *
     * <p>不能把启动 Activity 的补丁主题直接套给它们 —— 那会连外观和窗口行为一起改掉。
     * 原主题与启动 Activity 相同的（例如都没声明主题、都继承 {@code <application>} 的），
     * 复用同一个补丁主题，不额外增加资源。
     */
    private int applyThemeToOtherActivities(ResXmlElement launcher, PatchTheme launcherTheme,
                                            int applicationThemeId, int fallbackBaseThemeId)
            throws IOException {
        int applied = 0;
        int index = 0;
        for (ResXmlElement activity : manifest.listActivities()) {
            if (activity == launcher) {
                continue;
            }
            String label = "Activity " + attrValue(activity, "name");
            int base = resolveActivityBaseTheme(activity, applicationThemeId, fallbackBaseThemeId);
            PatchTheme theme = launcherTheme;
            if (base != launcherTheme.parentId) {
                theme = createPatchTheme(
                        activityStyleName(launcherTheme.name, activity, index), base, label);
            }
            index++;
            applied += setElementTheme(activity, theme, label);
        }
        return applied;
    }

    /** 单个 Activity 原本会用的主题：自己声明的 -> {@code <application>} 的 -> 兜底。 */
    private int resolveActivityBaseTheme(ResXmlElement activity, int applicationThemeId,
                                         int fallbackBaseThemeId) {
        int id = unwrapPatchTheme(themeAttributeId(activity));
        if (id != 0) {
            return id;
        }
        if (applicationThemeId != 0) {
            return applicationThemeId;
        }
        return fallbackBaseThemeId;
    }

    /** 按 Activity 名派生补丁样式名，例如 {@code WearNoSwipeUnityTheme.SecondActivity}。 */
    private static String activityStyleName(String launcherStyleName, ResXmlElement activity,
                                            int index) {
        String name = attrValue(activity, "name");
        if (name == null || name.isEmpty()) {
            name = "Activity" + index;
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            name = name.substring(dot + 1);
        }
        StringBuilder sb = new StringBuilder(launcherStyleName.length() + name.length() + 1);
        sb.append(launcherStyleName).append(ACTIVITY_STYLE_SEPARATOR);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        if (sb.length() == launcherStyleName.length() + ACTIVITY_STYLE_SEPARATOR.length()) {
            sb.append("Activity").append(index);
        }
        return sb.toString();
    }

    /** 给单个元素设置 {@code android:theme="@style/<补丁样式>"}。 */
    private int setElementTheme(ResXmlElement element, PatchTheme theme, String what) {
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
        attr.setData(theme.id);
        report.addAppliedTarget(what + " -> @style/" + theme.name);
        return 1;
    }
}
