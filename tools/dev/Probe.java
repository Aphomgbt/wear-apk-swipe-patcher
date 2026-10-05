package dev;

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

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Read-only probe. Dumps the facts we need to design the patcher:
 *  - manifest package / launcher activity / its android:theme
 *  - the golden reference style bag contents
 *  - resolution of android:attr/windowSwipeToDismiss
 */
public class Probe {

    static String hex(int v) {
        return "0x" + Integer.toHexString(v);
    }

    static String safe(String s) {
        return s == null ? "null" : s.replace('\n', ' ');
    }

    static void dumpAttrs(String indent, ResXmlElement el) {
        Iterator<ResXmlAttribute> it = el.getAttributes();
        while (it.hasNext()) {
            ResXmlAttribute a = it.next();
            System.out.println(indent + "  attr nameId=" + hex(a.getNameId())
                    + " getName()=" + a.getName()
                    + " decodeName(false)=" + a.decodeName(false)
                    + " decodeName(true)=" + a.decodeName(true)
                    + " vType=" + a.getValueType()
                    + " data=" + hex(a.getData())
                    + " str=" + safe(a.getValueAsString()));
        }
    }

    static String attrValue(ResXmlElement el, String name) {
        Iterator<ResXmlAttribute> it = el.getAttributes();
        while (it.hasNext()) {
            ResXmlAttribute a = it.next();
            if (name.equals(a.getName()) || name.equals(a.decodeName(false))) {
                return a.getValueAsString();
            }
        }
        return null;
    }

    static boolean isLauncher(ResXmlElement activity) {
        List<?> filters = activity.listElements("intent-filter");
        for (Object o : filters) {
            ResXmlElement f = (ResXmlElement) o;
            boolean main = false, launcher = false;
            for (Object ao : f.listElements("action")) {
                if ("android.intent.action.MAIN".equals(attrValue((ResXmlElement) ao, "name"))) {
                    main = true;
                }
            }
            for (Object co : f.listElements("category")) {
                if ("android.intent.category.LAUNCHER".equals(attrValue((ResXmlElement) co, "name"))) {
                    launcher = true;
                }
            }
            if (main && launcher) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        File apkFile = new File(args[0]);
        System.out.println("###### APK: " + apkFile + " size=" + apkFile.length());

        ApkModule apk = ApkModule.loadApkFile(apkFile);
        AndroidManifestBlock mf = apk.getAndroidManifestBlock();
        System.out.println("packageName = " + mf.getPackageName());
        System.out.println("versionCode = " + mf.getVersionCode());
        System.out.println("versionName = " + mf.getVersionName());
        System.out.println("minSdk      = " + mf.getMinSdkVersion());
        System.out.println("targetSdk   = " + mf.getTargetSdkVersion());
        System.out.println("compileSdk  = " + mf.getCompileSdkVersion());

        TableBlock table = apk.getTableBlock();
        System.out.println("hasTableBlock=" + apk.hasTableBlock());
        System.out.println("currentPackage=" + (table.getCurrentPackage() == null ? "null"
                : table.getCurrentPackage().getName()
                  + " id=" + hex(table.getCurrentPackage().getId())));

        printManifest(mf);
        printAttrResolution(table);
        printStyles(table);

        System.out.println();
        System.out.println("###### ValueType bytes");
        for (ValueType vt : new ValueType[]{ValueType.NULL, ValueType.REFERENCE,
                ValueType.ATTRIBUTE, ValueType.STRING, ValueType.BOOLEAN, ValueType.DEC}) {
            System.out.println("  " + vt + " = " + hex(vt.getByte()));
        }

        System.out.println();
        System.out.println("###### END");
        apk.close();
    }

    static void printManifest(AndroidManifestBlock mf) {
        System.out.println();
        System.out.println("###### ACTIVITIES");
        for (ResXmlElement a : mf.listActivities()) {
            boolean launcher = isLauncher(a);
            System.out.println("activity name=" + attrValue(a, "name")
                    + " launcher=" + launcher
                    + " theme=" + attrValue(a, "theme"));
            if (launcher) {
                System.out.println("  >>> LAUNCHER attrs:");
                dumpAttrs("  ", a);
            }
        }
        System.out.println();
        System.out.println("###### APPLICATION");
        ResXmlElement app = mf.getApplicationElement();
        if (app != null) {
            dumpAttrs("  ", app);
        }
    }

    static void printAttrResolution(TableBlock table) {
        System.out.println();
        System.out.println("###### android attr resolution");
        try {
            for (PackageBlock p : table) {
                if (p.getId() != 0x01) {
                    continue;
                }
                System.out.println("  pkg android: " + p.getName()
                        + " id=" + hex(p.getId()));
                System.out.println("    resolveResourceId(attr, windowSwipeToDismiss) = "
                        + hex(p.resolveResourceId("attr", "windowSwipeToDismiss")));
                ResourceEntry re = p.getAttrResource("windowSwipeToDismiss");
                System.out.println("    getAttrResource = " + re
                        + (re == null ? "" : " id=" + hex(re.getResourceId())));
            }
        } catch (Throwable t) {
            System.out.println("  [error] " + t);
        }
        System.out.println("  StyleBag.resolve(table, \"android:windowSwipeToDismiss\") = "
                + hex(StyleBag.resolve(table, "android:windowSwipeToDismiss")));
        try {
            System.out.println("  StyleBag.resolve(table, \"windowSwipeToDismiss\") = "
                    + hex(StyleBag.resolve(table, "windowSwipeToDismiss")));
        } catch (Throwable t) {
            System.out.println("  StyleBag.resolve(table, \"windowSwipeToDismiss\") -> "
                    + t.getClass().getSimpleName() + " (expected: needs package prefix)");
        }
    }

    static void printStyles(TableBlock table) {
        System.out.println();
        System.out.println("###### style lookup");
        for (PackageBlock p : table) {
            System.out.println("  -- package " + p.getName() + " id=" + hex(p.getId()));
            for (String tn : new String[]{"WearNoSwipeUnityTheme", "UnityThemeSelector",
                                          "WearSwipePatchTheme"}) {
                ResourceEntry se = p.getResource("style", tn);
                System.out.println("    style/" + tn + " -> "
                        + (se == null ? "null" : "id=" + hex(se.getResourceId())));
                if (se == null) {
                    continue;
                }
                Iterator<Entry> eit = se.iterator();
                while (eit.hasNext()) {
                    printStyleEntry(eit.next());
                }
            }
            if (isAppPackage(p)) {
                // also report the parent style the golden theme derives from
                ResourceEntry pe = p.getResource("style", "UnityThemeSelector");
                if (pe != null) {
                    Iterator<Entry> it = pe.iterator();
                    if (it.hasNext()) {
                        printStyleEntry(it.next());
                    }
                }
            }
        }
    }

    static boolean isAppPackage(PackageBlock p) {
        return p.getId() != 0x01;
    }

    static void printStyleEntry(Entry e) {
        ResConfig c = e.getResConfig();
        System.out.println("      entry cfg=" + (c == null ? "null" : c.getQualifiers())
                + " id=" + hex(e.getResourceId())
                + " name=" + e.getName()
                + " valueType=" + e.getValueType()
                + " isComplex=" + e.isComplex());
        try {
            StyleBag bag = StyleBag.create(e);
            if (bag == null) {
                System.out.println("        (StyleBag.create -> null)");
                return;
            }
            System.out.println("        parentId=" + hex(bag.getParentId())
                    + " parentName=" + bag.getParentResourceName());
            for (Map.Entry<Integer, StyleBagItem> be : bag.entrySet()) {
                StyleBagItem bi = be.getValue();
                System.out.println("        item attrId=" + hex(be.getKey())
                        + " name=" + bi.getName()
                        + " valueType=" + bi.getValueType()
                        + " value=" + bi.getValue()
                        + " valueHex=" + hex(bi.getValue()));
            }
        } catch (Throwable t) {
            System.out.println("        StyleBag error: " + t);
        }
    }
}
