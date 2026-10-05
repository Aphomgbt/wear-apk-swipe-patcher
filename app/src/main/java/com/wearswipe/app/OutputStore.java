package com.wearswipe.app;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 成品 APK 的落地目录。
 *
 * <p>用应用专属外部目录（{@code getExternalFilesDir}）：不需要任何存储权限，
 * 又能被文件管理器 / 分享面板 / 安装器访问到。卸载 App 时一起清掉，不会留垃圾。
 */
public final class OutputStore {

    private static final String DIR_NAME = "patched";

    private OutputStore() {
    }

    public static File dir(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        File dir = new File(base, DIR_NAME);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            // 极端情况下退回内部目录
            dir = new File(context.getFilesDir(), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new IllegalStateException("无法创建输出目录 " + dir);
            }
        }
        return dir;
    }

    /** 根据输入文件名生成一个不会覆盖已有文件的输出路径。 */
    public static File createOutputFile(Context context, String inputDisplayName) {
        String base = sanitize(inputDisplayName);
        if (base.toLowerCase(Locale.US).endsWith(".apk")) {
            base = base.substring(0, base.length() - 4);
        }
        if (base.isEmpty()) {
            base = "app";
        }
        String stamp = new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date());
        File dir = dir(context);
        File candidate = new File(dir, base + "-noswipe-" + stamp + ".apk");
        int index = 2;
        while (candidate.exists()) {
            candidate = new File(dir, base + "-noswipe-" + stamp + "-" + index + ".apk");
            index++;
        }
        return candidate;
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    public static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ApkShareProvider.AUTHORITY_SUFFIX)
                .appendPath(file.getName())
                .build();
    }
}
