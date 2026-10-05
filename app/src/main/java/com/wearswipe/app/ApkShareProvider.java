package com.wearswipe.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider，只把 {@link OutputStore} 目录里的成品 APK 交给系统安装器或分享面板。
 *
 * <p>{@code exported="false"} + {@code grantUriPermissions="true"}：外部只能通过我们主动
 * 授予的临时读权限访问，并且路径会被严格限制在应用自己的输出目录内，不做任意文件暴露。
 */
public final class ApkShareProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".apks";
    public static final String MIME_APK = "application/vnd.android.package-archive";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_APK;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (TextUtils.isEmpty(name) || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.contains("..")) {
            throw new FileNotFoundException("非法的文件名: " + name);
        }
        File dir = OutputStore.dir(getContext());
        File file = new File(dir, name);
        try {
            String dirPath = dir.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(dirPath)) {
                throw new FileNotFoundException("路径越界");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("无法解析路径: " + e);
        }
        if (!file.isFile()) {
            throw new FileNotFoundException("文件不存在: " + name);
        }
        return file;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("只读");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读");
    }
}
