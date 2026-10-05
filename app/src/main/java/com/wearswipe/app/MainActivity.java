package com.wearswipe.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.wearswipe.core.ApkInspector;
import com.wearswipe.core.PatchOptions;
import com.wearswipe.core.PatchReport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 单屏界面：选 APK → 看分析结果 → 打补丁 → 保存/分享/安装。
 *
 * <p>刻意不使用 AndroidX：只用 framework API 与 {@code Theme.Material}，
 * 这样 APK 不引入任何外部依赖，体积小、兼容面广，也不受依赖仓库可用性影响。
 */
public final class MainActivity extends Activity implements PatchSession.Listener {

    private static final int REQ_PICK_APK = 1001;
    private static final int REQ_SAVE_APK = 1002;
    private static final int REQ_NOTIFICATION = 1003;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Button btnPick;
    private Button btnStart;
    private Button btnSave;
    private Button btnInstall;
    private Button btnRestart;
    private CheckBox chkApplication;
    private CheckBox chkAllActivities;
    private EditText edtParent;
    private RadioGroup radSigning;
    private ProgressBar progress;
    private TextView txtInput;
    private TextView txtWarning;
    private TextView txtProgress;
    private TextView txtLogTitle;
    private TextView txtLog;
    private TextView txtResultTitle;
    private TextView txtResult;

    /** 用户点了"保存到…"后等待落盘的数据源。 */
    private File pendingSaveSource;
    /** URI 授权是否已持久化，避免重复请求。 */
    private Uri lastPickedUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnPick = (Button) findViewById(R.id.btn_pick);
        btnStart = (Button) findViewById(R.id.btn_start);
        btnSave = (Button) findViewById(R.id.btn_save);
        btnInstall = (Button) findViewById(R.id.btn_install);
        btnRestart = (Button) findViewById(R.id.btn_restart);
        chkApplication = (CheckBox) findViewById(R.id.chk_application);
        chkAllActivities = (CheckBox) findViewById(R.id.chk_all_activities);
        edtParent = (EditText) findViewById(R.id.edt_parent);
        radSigning = (RadioGroup) findViewById(R.id.rad_signing);
        progress = (ProgressBar) findViewById(R.id.progress);
        txtInput = (TextView) findViewById(R.id.txt_input);
        txtWarning = (TextView) findViewById(R.id.txt_warning);
        txtProgress = (TextView) findViewById(R.id.txt_progress);
        txtLogTitle = (TextView) findViewById(R.id.txt_log_title);
        txtLog = (TextView) findViewById(R.id.txt_log);
        txtResultTitle = (TextView) findViewById(R.id.txt_result_title);
        txtResult = (TextView) findViewById(R.id.txt_result);

        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPatch();
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareOrSaveOutput();
            }
        });
        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installOutput();
            }
        });
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PatchSession.reset();
                refreshUi();
            }
        });

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PatchSession.addListener(this);
        refreshUi();
    }

    @Override
    protected void onPause() {
        PatchSession.removeListener(this);
        super.onPause();
    }

    @Override
    public void onSessionChanged() {
        // 写入方可能在任意线程，统一切回主线程刷新 UI
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                refreshUi();
            }
        });
    }

    // ------------------------------------------------------------------
    // 选择 APK
    // ------------------------------------------------------------------

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "application/zip"});
        try {
            startActivityForResult(intent, REQ_PICK_APK);
        } catch (ActivityNotFoundException e) {
            toast("系统里找不到文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_APK) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            Uri uri = data.getData();
            lastPickedUri = uri;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
                // 有些 provider 不支持持久授权，不影响本次读取
            }
            copyAndAnalyze(uri, queryDisplayName(uri));
            return;
        }

        if (requestCode == REQ_SAVE_APK) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingSaveSource = null;
                return;
            }
            if (pendingSaveSource != null) {
                copyFileToUri(pendingSaveSource, data.getData());
            }
            pendingSaveSource = null;
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 退回 URI 末段
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "app.apk" : last;
    }

    private void copyAndAnalyze(final Uri uri, final String displayName) {
        final File cache = new File(getCacheDir(), "input.apk");
        // 先进入"分析中"，UI 立刻有反馈
        PatchSession.setInput(cache, displayName);

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (cache.exists() && !cache.delete()) {
                        throw new IOException("无法删除旧缓存文件");
                    }
                    copyUriToFile(uri, cache);
                    ApkInspector.Inspection info = ApkInspector.inspect(cache);
                    PatchSession.log("已读取 " + cache.length() + " 字节");
                    for (String warning : info.getWarnings()) {
                        PatchSession.log("警告: " + warning);
                    }
                    PatchSession.setInspection(info);
                } catch (OutOfMemoryError error) {
                    PatchSession.failAnalysis("内存不足，无法读取这个 APK。请关掉其它应用后重试。");
                } catch (Throwable t) {
                    PatchSession.failAnalysis("读取失败：" + describe(t));
                }
            }
        }, "wear-swipe-analyze");
        thread.start();
    }

    private void copyUriToFile(Uri uri, File target) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法打开所选文件");
        }
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------
    // 打补丁
    // ------------------------------------------------------------------

    private void startPatch() {
        File input = PatchSession.getInputFile();
        if (input == null || !input.isFile()) {
            toast(getString(R.string.err_no_file));
            return;
        }
        ApkInspector.Inspection info = PatchSession.getInspection();
        if (info == null || !info.isPatchable()) {
            toast(getString(R.string.err_not_patchable,
                    info == null ? "分析未完成" : String.valueOf(info.getPatchableReason())));
            return;
        }

        PatchOptions options = new PatchOptions();
        String parent = edtParent.getText().toString().trim();
        if (!parent.isEmpty()) {
            options.setParentStyleName(parent);
        }
        options.setPatchApplicationElement(chkApplication.isChecked());
        options.setPatchAllActivities(chkAllActivities.isChecked());
        // 打补丁之前就把签名方案定下来：默认 V1 + V2，用户可改成只签一种
        options.setSigningScheme(selectedSigningScheme());

        File output;
        try {
            output = OutputStore.createOutputFile(this, PatchSession.getInputDisplayName());
        } catch (Throwable t) {
            toast("无法创建输出文件：" + describe(t));
            return;
        }

        Intent service = PatchService.createIntent(this, input, output, options);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Throwable t) {
            toast("无法启动补丁服务：" + describe(t));
        }
    }

    // ------------------------------------------------------------------
    // 保存 / 分享 / 安装
    // ------------------------------------------------------------------

    private File outputOrNull() {
        File output = PatchSession.getOutputFile();
        return (output != null && output.isFile()) ? output : null;
    }

    private void shareOrSaveOutput() {
        final File output = outputOrNull();
        if (output == null) {
            toast("还没有生成结果文件");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(output.getName())
                .setItems(new CharSequence[]{"保存到…（文件管理器可见）", "分享给其它应用"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    saveOutputTo(output);
                                } else {
                                    shareOutput(output);
                                }
                            }
                        })
                .show();
    }

    private void saveOutputTo(File output) {
        pendingSaveSource = output;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(ApkShareProvider.MIME_APK);
        intent.putExtra(Intent.EXTRA_TITLE, output.getName());
        try {
            startActivityForResult(intent, REQ_SAVE_APK);
        } catch (ActivityNotFoundException e) {
            pendingSaveSource = null;
            toast("系统里找不到保存对话框");
        }
    }

    private void shareOutput(File output) {
        Uri uri = OutputStore.uriFor(this, output);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(ApkShareProvider.MIME_APK);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "发送 " + output.getName()));
        } catch (Throwable t) {
            toast("没有可用的分享目标");
        }
    }

    private void installOutput() {
        File output = outputOrNull();
        if (output == null) {
            toast("还没有生成结果文件");
            return;
        }
        Uri uri = OutputStore.uriFor(this, output);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, ApkShareProvider.MIME_APK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("系统没有可安装 APK 的应用（请改用「保存到…」再自行安装）");
        }
    }

    private void copyFileToUri(final File source, final Uri target) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    InputStream in = new java.io.FileInputStream(source);
                    try {
                        OutputStream out = getContentResolver().openOutputStream(target, "wt");
                        if (out == null) {
                            throw new IOException("无法写入所选位置");
                        }
                        try {
                            byte[] buffer = new byte[1 << 16];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                out.write(buffer, 0, read);
                            }
                            out.flush();
                        } finally {
                            out.close();
                        }
                    } finally {
                        in.close();
                    }
                } catch (Throwable t) {
                    error = describe(t);
                }
                final String failure = error;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (failure == null) {
                            toast("已保存");
                        } else {
                            toast(getString(R.string.save_failed, failure));
                        }
                    }
                });
            }
        }, "wear-swipe-save").start();
    }

    // ------------------------------------------------------------------
    // 权限与工具
    // ------------------------------------------------------------------

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFICATION);
        } catch (Throwable ignored) {
            // 拿不到通知权限只是看不到进度通知，不影响补丁
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        return message == null ? name : (name + ": " + message);
    }

    /** 读界面上选中的签名方案，没选中任何一项时退回默认的 V1 + V2。 */
    private PatchOptions.SigningScheme selectedSigningScheme() {
        int checked = radSigning.getCheckedRadioButtonId();
        if (checked == R.id.rad_scheme_v2) {
            return PatchOptions.SigningScheme.V2_ONLY;
        }
        if (checked == R.id.rad_scheme_v1) {
            return PatchOptions.SigningScheme.V1_ONLY;
        }
        return PatchOptions.SigningScheme.V1_AND_V2;
    }

    /** RadioGroup 不会把 enabled 状态传给子项，运行中需要逐个禁用。 */
    private static void setEnabledRecursive(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledRecursive(group.getChildAt(i), enabled);
            }
        }
    }

    // ------------------------------------------------------------------
    // UI 刷新
    // ------------------------------------------------------------------

    private void refreshUi() {
        PatchSession.Phase phase = PatchSession.getPhase();
        ApkInspector.Inspection info = PatchSession.getInspection();
        boolean running = phase == PatchSession.Phase.ANALYZING
                || phase == PatchSession.Phase.PATCHING;

        renderInput(phase, info);

        chkApplication.setEnabled(!running);
        chkAllActivities.setEnabled(!running);
        edtParent.setEnabled(!running);
        setEnabledRecursive(radSigning, !running);
        btnPick.setEnabled(!running);
        btnStart.setEnabled(phase == PatchSession.Phase.READY);

        boolean showProgress = phase == PatchSession.Phase.PATCHING
                || phase == PatchSession.Phase.DONE;
        progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        txtProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            progress.setProgress(PatchSession.getPercent());
            txtProgress.setText(PatchSession.getPercent() + "%  " + PatchSession.getMessage());
        }

        String log = PatchSession.getLog();
        boolean hasLog = log.length() > 0;
        txtLogTitle.setVisibility(hasLog ? View.VISIBLE : View.GONE);
        txtLog.setVisibility(hasLog ? View.VISIBLE : View.GONE);
        if (hasLog) {
            txtLog.setText(log);
        }

        renderResult(phase);
    }

    private void renderInput(PatchSession.Phase phase, ApkInspector.Inspection info) {
        String name = PatchSession.getInputDisplayName();
        StringBuilder sb = new StringBuilder();
        if (name == null) {
            sb.append(getString(R.string.picked_none));
        } else {
            sb.append("文件: ").append(name).append('\n');
            if (info != null) {
                sb.append(info.describe());
            } else if (phase == PatchSession.Phase.ANALYZING) {
                sb.append(getString(R.string.analyzing));
            } else if (phase == PatchSession.Phase.FAILED) {
                sb.append("错误: ").append(String.valueOf(PatchSession.getError()));
            }
            if (info != null && info.isFullyPatched()) {
                sb.append('\n').append("注意：这个 APK 已经打过本工具的补丁，可以再次处理（不会重复分配资源）。");
            }
        }
        txtInput.setText(sb.toString());

        boolean showWarning = info != null && info.isPatchable();
        txtWarning.setVisibility(showWarning ? View.VISIBLE : View.GONE);
    }

    private void renderResult(PatchSession.Phase phase) {
        if (phase == PatchSession.Phase.DONE) {
            PatchReport report = PatchSession.getReport();
            StringBuilder sb = new StringBuilder();
            if (report != null) {
                sb.append(report);
            }
            if (PatchSession.getVerification() != null) {
                sb.append("签名          : ")
                  .append(PatchSession.getVerification().describeSchemes())
                  .append("  verified=")
                  .append(PatchSession.getVerification().isVerified())
                  .append('\n');
            }
            File output = PatchSession.getOutputFile();
            if (output != null) {
                sb.append("文件          : ").append(output.getName()).append('\n');
                sb.append("路径          : ").append(output.getAbsolutePath()).append('\n');
            }
            sb.append('\n').append(getString(R.string.warn_repack));

            txtResultTitle.setVisibility(View.VISIBLE);
            txtResult.setVisibility(View.VISIBLE);
            txtResult.setText(sb.toString());
            btnSave.setVisibility(View.VISIBLE);
            btnInstall.setVisibility(View.VISIBLE);
            btnRestart.setVisibility(View.VISIBLE);
            return;
        }

        if (phase == PatchSession.Phase.FAILED) {
            txtResultTitle.setVisibility(View.VISIBLE);
            txtResult.setVisibility(View.VISIBLE);
            txtResult.setText("失败：" + String.valueOf(PatchSession.getError()));
            btnSave.setVisibility(View.GONE);
            btnInstall.setVisibility(View.GONE);
            btnRestart.setVisibility(View.VISIBLE);
            return;
        }

        txtResultTitle.setVisibility(View.GONE);
        txtResult.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);
        btnInstall.setVisibility(View.GONE);
        btnRestart.setVisibility(View.GONE);
    }
}
