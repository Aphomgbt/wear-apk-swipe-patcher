package com.wearswipe.core;

import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.ResConfig;
import com.reandroid.arsc.value.ValueType;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/** 清单 / 资源表常用只读操作的共享实现。 */
public final class ManifestUtil {

    /** {@code android:theme} 的框架属性资源 ID。 */
    public static final int ATTR_ANDROID_THEME = 0x01010000;

    private static final int FRAMEWORK_PACKAGE_ID = 0x01;

    private ManifestUtil() {
    }

    public static String attrValue(ResXmlElement element, String name) {
        Iterator<ResXmlAttribute> it = element.getAttributes();
        while (it.hasNext()) {
            ResXmlAttribute a = it.next();
            if (name.equals(a.getName()) || name.equals(a.decodeName(false))) {
                return a.getValueAsString();
            }
        }
        return null;
    }

    public static ResXmlAttribute findAttribute(ResXmlElement element, int nameId) {
        Iterator<ResXmlAttribute> it = element.getAttributes();
        while (it.hasNext()) {
            ResXmlAttribute a = it.next();
            if (a.getNameId() == nameId) {
                return a;
            }
        }
        return null;
    }

    /** 读取元素上的 {@code android:theme} 引用到的资源 ID；没有或不是引用时返回 0。 */
    public static int themeAttributeId(ResXmlElement element) {
        if (element == null) {
            return 0;
        }
        ResXmlAttribute attr = findAttribute(element, ATTR_ANDROID_THEME);
        if (attr == null || attr.getValueType() != ValueType.REFERENCE) {
            return 0;
        }
        return attr.getData();
    }

    public static boolean hasMainLauncherFilter(ResXmlElement activity) {
        List<?> filters = activity.listElements("intent-filter");
        for (Object o : filters) {
            ResXmlElement filter = (ResXmlElement) o;
            boolean main = false;
            boolean launcher = false;
            for (Object a : filter.listElements("action")) {
                if ("android.intent.action.MAIN".equals(attrValue((ResXmlElement) a, "name"))) {
                    main = true;
                }
            }
            for (Object c : filter.listElements("category")) {
                if ("android.intent.category.LAUNCHER".equals(attrValue((ResXmlElement) c, "name"))) {
                    launcher = true;
                }
            }
            if (main && launcher) {
                return true;
            }
        }
        return false;
    }

    /** 定位启动 Activity；找不到返回 {@code null}。 */
    public static ResXmlElement findLauncherActivity(AndroidManifestBlock manifest) {
        ResXmlElement main = manifest.getMainActivity();
        if (main != null) {
            return main;
        }
        for (ResXmlElement activity : manifest.listActivities()) {
            if (hasMainLauncherFilter(activity)) {
                return activity;
            }
        }
        return null;
    }

    /** 定位应用自身的资源包（排除 {@code 0x01} 框架包）。 */
    public static PackageBlock findAppPackage(TableBlock table, String manifestPackage) {
        PackageBlock current = table.getCurrentPackage();
        if (isAppPackage(current)) {
            return current;
        }
        PackageBlock byName = null;
        PackageBlock first = null;
        for (PackageBlock pkg : table) {
            if (!isAppPackage(pkg)) {
                continue;
            }
            if (first == null) {
                first = pkg;
            }
            String name = pkg.getName();
            if (name != null && name.equals(manifestPackage)) {
                byName = pkg;
                break;
            }
        }
        return byName != null ? byName : first;
    }

    public static boolean isAppPackage(PackageBlock pkg) {
        return pkg != null && pkg.getId() != FRAMEWORK_PACKAGE_ID;
    }

    /** 按默认配置取应用包里的某个条目；没有则返回 {@code null}。 */
    public static ResourceEntry getStyle(PackageBlock appPackage, String name) {
        return appPackage.getResource("style", name);
    }

    /** 把资源 ID 转成 {@code "style/Name"} 形式的可读描述；解析不到返回 {@code null}。 */
    public static String describeResource(TableBlock table, int resourceId) {
        if (resourceId == 0) {
            return null;
        }
        try {
            ResourceEntry entry = table.getResource(resourceId);
            if (entry == null) {
                return null;
            }
            return entry.getType() + "/" + entry.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@code 0x7f030005} -> {@code "WearNoSwipeUnityTheme"}；不是样式则返回 {@code null}。 */
    public static String styleNameOf(TableBlock table, int resourceId) {
        String description = describeResource(table, resourceId);
        final String prefix = "style/";
        if (description == null || !description.startsWith(prefix)) {
            return null;
        }
        String name = description.substring(prefix.length());
        return name.isEmpty() ? null : name;
    }

    static void requireManifest(AndroidManifestBlock manifest) throws IOException {
        if (manifest == null) {
            throw new IOException("该 APK 没有 AndroidManifest.xml");
        }
    }
}
