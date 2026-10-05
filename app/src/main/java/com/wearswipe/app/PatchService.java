package com.wearswipe.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.wearswipe.core.PatchOptions;
import com.wearswipe.core.Progress;
import com.wearswipe.core.WearSwipePatcher;

import java.io.File;

/**
 * 跑补丁的前台服务。
 *
 * <p>打包 + 重签一个几十 MB 的 APK 可能耗时数十秒到数分钟，必须放在前台服务里，
 * 否则用户切走时会被系统冻结/杀掉。同时持有 WakeLock，避免息屏时 CPU 被降频。
 *
 * <p>注意：{@code android:foregroundServiceType="dataSync"} 在 API 34+ 需要
 * {@code FOREGROUND_SERVICE_DATA_SYNC} 权限；旧版本走不带类型的
 * {@code startForeground}。
 */
public final class PatchService extends Service {

    public static final String EXTRA_INPUT = "input";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_STYLE_NAME = "styleName";
    public static final String EXTRA_PARENT_STYLE = "parentStyle";
    public static final String EXTRA_ALL_ACTIVITIES = "allActivities";
    public static final String EXTRA_APPLICATION = "application";
    public static final String EXTRA_SIGNING_SCHEME = "signingScheme";

    private static final String CHANNEL_ID = "patch_progress";
    private static final int NOTIFICATION_ID = 1001;

    private volatile boolean running;
    private PowerManager.WakeLock wakeLock;

    /** 组装启动本服务的 Intent。 */
    public static Intent createIntent(Context context, File input, File output,
                                      PatchOptions options) {
        Intent intent = new Intent(context, PatchService.class);
        intent.putExtra(EXTRA_INPUT, input.getAbsolutePath());
        intent.putExtra(EXTRA_OUTPUT, output.getAbsolutePath());
        intent.putExtra(EXTRA_STYLE_NAME, options.getStyleName());
        intent.putExtra(EXTRA_PARENT_STYLE, options.getParentStyleName());
        intent.putExtra(EXTRA_ALL_ACTIVITIES, options.isPatchAllActivities());
        intent.putExtra(EXTRA_APPLICATION, options.isPatchApplicationElement());
        intent.putExtra(EXTRA_SIGNING_SCHEME, options.getSigningScheme().name());
        return intent;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra(EXTRA_INPUT) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            return START_NOT_STICKY;
        }
        running = true;

        startForegroundCompat();
        acquireWakeLock();

        final File input = new File(intent.getStringExtra(EXTRA_INPUT));
        final File output = new File(intent.getStringExtra(EXTRA_OUTPUT));

        final PatchOptions options = new PatchOptions();
        String styleName = intent.getStringExtra(EXTRA_STYLE_NAME);
        if (styleName != null && !styleName.trim().isEmpty()) {
            options.setStyleName(styleName.trim());
        }
        options.setParentStyleName(intent.getStringExtra(EXTRA_PARENT_STYLE));
        options.setPatchAllActivities(intent.getBooleanExtra(EXTRA_ALL_ACTIVITIES, false));
        options.setPatchApplicationElement(intent.getBooleanExtra(EXTRA_APPLICATION, false));
        // 签名方案：认不出来的值一律退回默认的 V1 + V2，只有界面明确选了才会变
        PatchOptions.SigningScheme scheme =
                PatchOptions.SigningScheme.parse(intent.getStringExtra(EXTRA_SIGNING_SCHEME));
        if (scheme != null) {
            options.setSigningScheme(scheme);
        }
        // 用户填了父主题就强制用它，否则自动探测
        options.setAutoDetectParent(options.getParentStyleName() == null);

        PatchSession.startPatching();
        PatchSession.log("签名方案: " + options.getSigningScheme().getLabel());

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runPatch(input, output, options);
            }
        }, "wear-swipe-patch");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();

        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------
    // 补丁主体
    // ------------------------------------------------------------------

    private void runPatch(File input, File output, PatchOptions options) {
        try {
            final WearSwipePatcher patcher = new WearSwipePatcher(options);
            WearSwipePatcher.Result result = patcher.patch(input, output, null, new Progress() {
                @Override
                public void onProgress(int percent, String message) {
                    PatchSession.progress(percent, message);
                    updateNotification(percent, message);
                }
            });

            if (result.getVerification() != null && !result.getVerification().isVerified()) {
                PatchSession.fail("签名校验未通过：" + result.getVerification().getErrors());
                return;
            }
            PatchSession.log("输出: " + result.getOutputApk().getAbsolutePath()
                    + "  (" + result.getOutputSize() + " B)");
            PatchSession.finish(result.getOutputApk(), result.getReport(),
                    result.getVerification());
        } catch (OutOfMemoryError error) {
            PatchSession.fail("内存不足。这个 APK 太大或系统可用内存太少，请关掉其它应用后重试。");
        } catch (Throwable t) {
            PatchSession.fail(describe(t));
        } finally {
            running = false;
            releaseWakeLock();
            stopForegroundCompat();
            stopSelf();
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        String name = t.getClass().getSimpleName();
        return message == null ? name : (name + ": " + message);
    }

    // ------------------------------------------------------------------
    // 前台通知
    // ------------------------------------------------------------------

    private void startForegroundCompat() {
        Notification notification = buildNotification(0, "开始…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void updateNotification(int percent, String message) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(percent, message));
        } catch (Throwable ignored) {
            // 用户在 13+ 拒绝通知权限也不影响补丁本身
        }
    }

    private Notification buildNotification(int percent, String message) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle(getString(R.string.notif_title))
                .setContentText(percent > 0 ? (percent + "%  " + message) : message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(percent, 0), percent <= 0);

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        builder.setContentIntent(PendingIntent.getActivity(this, 0, open, flags));
        return builder.build();
    }

    // ------------------------------------------------------------------
    // WakeLock
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) {
                return;
            }
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearSwipePatch::patch");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(30 * 60 * 1000L);
        } catch (Throwable ignored) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        wakeLock = null;
    }
}
